(ns agg.jobs.lifecycle
  (:require [agg.errors :as errors]
            [agg.admin.core :as admin]
            [agg.contracts.render :as contract])
  (:import (java.security MessageDigest)
           (java.time Clock Instant LocalDate YearMonth ZoneOffset)
           (java.util HexFormat UUID)
           (java.util.concurrent CancellationException)))

(defprotocol JobService
  (submit-job! [service idempotency-key request])
  (get-job [service job-id])
  (dispatch-job! [service job-id])
  (cancel-job! [service job-id])
  (retry-job! [service job-id])
  (run-job! [service job-id]))

(defprotocol JobAttemptRunner
  (run-job-attempt! [service job-id attempt]))

(defprotocol JobAccess
  (owns-job? [service job-id subject]))

(defprotocol JobReconciler
  (reconcile-jobs! [service]))

(defprotocol JobLauncher
  (launch-job! [launcher job-id])
  (cancel-execution! [launcher execution]))

(defn request-execution-cancellation!
  "Treat a cancelled operation future as acknowledgement of execution cancellation."
  [launcher execution]
  (try
    (cancel-execution! launcher execution)
    (catch CancellationException _
      nil)))

(defprotocol RecoverableJobLauncher
  (launch-job-attempt! [launcher job-id attempt])
  (find-active-execution [launcher job-id attempt]))

(defprotocol RenderWorker
  (perform-render! [worker job-id request]))

(defprotocol JobQueue
  (enqueue-job! [queue job-id attempt]))

(defprotocol RequestStore
  (save-request! [store job-id request])
  (load-request [store object-name]))

(defprotocol UploadSigner
  (signed-upload [signer object-name content-type expires-seconds]))

(def max-active-leases 5)
(def max-daily-submissions 100)
(def default-monthly-budget-minor-units 40000)
(def default-preview-reservation-minor-units 125)
(def default-render-reservation-minor-units 125)
(def default-preview-plus-render-exposure-minor-units
  (+ default-preview-reservation-minor-units
     default-render-reservation-minor-units))
(def ^:private billing-zone (ZoneOffset/ofHours -8))
(def lease-seconds (* 65 60))
(def max-output-bytes (* 18 1024 1024 1024))

(def preview-operation-version "key-moment-gallery-v2")
(def preview-retention-seconds (* 24 60 60))

(defn preview-request? [request]
  (= preview-operation-version (:previewOperation request)))

(defn reservation-minor-units
  [request preview-reservation-minor-units render-reservation-minor-units]
  (if (preview-request? request)
    preview-reservation-minor-units
    render-reservation-minor-units))

(def ^:private durable-failure-codes
  #{"request_load_failed"
    "request_prepare_failed"
    "source_metadata_failed"
    "source_download_failed"
    "source_content_failed"
    "drive_grant_required"
    "overlay_render_failed"
    "composition_encode_failed"
    "composition_timeout"
    "artifact_upload_failed"
    "drive_delivery_failed"
    "completion_persistence_failed"
    "output_too_large"
    "launch_failed"
    "stale_lease"
    "worker_failed"})

(def ^:private durable-stages
  #{"request_load"
    "request_prepare"
    "source_content"
    "overlay_render"
    "composition_encode"
    "artifact_upload"
    "drive_delivery"
    "completion_persistence"})

(def ^:private stage-failure-codes
  {"request_load" "request_load_failed"
   "request_prepare" "request_prepare_failed"
   "source_content" "source_content_failed"
   "overlay_render" "overlay_render_failed"
   "composition_encode" "composition_encode_failed"
   "artifact_upload" "artifact_upload_failed"
   "drive_delivery" "drive_delivery_failed"
   "completion_persistence" "completion_persistence_failed"})

(def ^:private durable-failure-reasons
  #{"preview_decode_failed" "source_stream_failed"
    "source_duration_too_short"})

(def ^:private preview-count-limit-keys
  [:requested-moment-count :generated-moment-count :omitted-moment-count
   :requested-duration-seconds])

(defn- valid-preview-limits? [limits]
  (let [{:keys [requested-moment-count generated-moment-count
                omitted-moment-count requested-duration-seconds]}
        limits]
    (and (= (set preview-count-limit-keys) (set (keys limits)))
         (every? integer? [requested-moment-count generated-moment-count
                           omitted-moment-count])
         (<= 1 requested-moment-count 32)
         (<= 0 generated-moment-count requested-moment-count)
         (<= 1 omitted-moment-count requested-moment-count)
         (= requested-moment-count
            (+ generated-moment-count omitted-moment-count))
         (number? requested-duration-seconds)
         (<= 1/25 requested-duration-seconds 480)
         (let [duration-frames (* 25.0
                                  (double requested-duration-seconds))]
           (< (Math/abs (- duration-frames
                           (Math/rint duration-frames)))
              1.0e-9)))))

(defn- throwable-data [cause]
  (loop [current cause
         result []]
    (if current
      (recur (.getCause ^Throwable current)
             (conj result (ex-data current)))
      result)))

(defn failure-diagnostics
  "Extracts the bounded durable diagnosis from an exception chain."
  [cause elapsed-ms]
  (let [data (throwable-data cause)
        first-safe (fn [key predicate]
                     (when-let [entry
                                (some (fn [entry]
                                        (when (predicate (get entry key))
                                          entry))
                                      data)]
                       (get entry key)))
        failure-code (or (first-safe :failure-code
                                     #(contains? durable-failure-codes %))
                         "worker_failed")
        stage (first-safe :stage #(contains? durable-stages %))
        reason (first-safe :reason #(contains? durable-failure-reasons %))
        status (first-safe :status #(and (integer? %) (<= 100 % 599)))
        timeout-ms (first-safe :timeout-ms
                               #(and (integer? %) (<= 0 % 1000000000000)))
        retryable (first-safe :retryable boolean?)
        preview-limits
        (some (fn [entry]
                (let [limits (:limits entry)]
                  (when (and (= "source_duration_too_short" (:reason entry))
                             (map? limits)
                             (valid-preview-limits? limits))
                    (select-keys limits preview-count-limit-keys))))
              data)]
    (cond-> {:failure-code failure-code
             :retryable (true? retryable)
             :elapsed-ms (max 0 (long elapsed-ms))}
      stage (assoc :stage stage)
      reason (assoc :reason reason)
      preview-limits (merge preview-limits)
      status (assoc :status status)
      timeout-ms (assoc :timeout-ms timeout-ms))))

(defn- typed-failure-code [data stage]
  (let [types (into #{} (keep :type) data)]
    (cond
      (contains? types :agg.auth.core/drive-grant-required)
      "drive_grant_required"

      (contains? types :agg.drive.gcp/source-metadata-failed)
      "source_metadata_failed"

      (contains? types :agg.drive.gcp/source-download-failed)
      "source_download_failed"

      (some #(and (keyword? %) (namespace %)) types)
      (get stage-failure-codes stage)

      :else "worker_failed")))

(defn with-durable-stage
  "Runs one durable boundary and adds only a bounded stage diagnosis."
  [stage operation]
  (when-not (contains? durable-stages stage)
    (throw (IllegalArgumentException. "Unknown durable render stage")))
  (try
    (operation)
    (catch Throwable cause
      (let [data (throwable-data cause)
            existing (some #(when (contains? durable-stages (:stage %)) %)
                           data)]
        (if existing
          (throw cause)
          (let [failure-code (typed-failure-code data stage)
                status (some #(let [value (:status %)]
                                (when (and (integer? value)
                                           (<= 100 value 599))
                                  value))
                             data)
                retryable-entry
                (some #(when (boolean? (:retryable %)) %) data)
                retryable (get retryable-entry :retryable)]
            (errors/raise! "Durable render stage failed"
                           (cond-> {:type ::stage-failed
                                    :failure-code failure-code
                                    :stage stage
                                    :retryable (true? retryable)}
                             status (assoc :status status))
                           cause)))))))

(defn public-failure-code [failure]
  (cond
    (nil? failure) nil
    (string? failure) (if (contains? durable-failure-codes failure)
                        failure
                        "worker_failed")
    (= ::worker-failed failure) "worker_failed"
    (= ::launch-failed failure) "launch_failed"
    (= ::invalid-transition failure) "invalid_job_transition"
    :else "worker_failed"))

(defn validate-admission-limits!
  ([daily-limit monthly-budget-minor-units render-reservation-minor-units]
   (validate-admission-limits! daily-limit monthly-budget-minor-units
                               default-preview-reservation-minor-units
                               render-reservation-minor-units))
  ([daily-limit monthly-budget-minor-units preview-reservation-minor-units
    render-reservation-minor-units]
   (when-not (every? #(and (integer? %) (pos? %))
                     [daily-limit monthly-budget-minor-units
                      preview-reservation-minor-units
                      render-reservation-minor-units])
     (throw (errors/raise! "Admission limits must be positive integers"
                           {:type ::invalid-admission-configuration})))
   true))

(defn require-render-attempt! [expected attempt]
  (when-not (and (integer? attempt)
                 (pos? attempt)
                 (= expected attempt))
    (throw (errors/raise! "Renderer attempt does not match the durable job"
                          {:type ::invalid-render-attempt})))
  attempt)

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map) (map (fn [[key item]]
                                           [key (canonical item)])) value)
    (sequential? value) (mapv canonical value)
    :else value))

(defn request-digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical value)) "UTF-8"))))

(defn render-request-digest [request]
  (request-digest
   (dissoc (contract/normalize-request request)
           :previewOperation :sourceVideoServerMetadata
           :requesterSubject :requesterEmail :requesterMembershipVersion)))

(defn job-resource
  [{:keys [id state attempt created-at updated-at failure failure-diagnostics
           output operation-kind]}]
  (cond-> {:id id
           :state (name state)
           :attempt attempt
           :statusUrl (str "/v1/jobs/" id)
           :cancelUrl (str "/v1/jobs/" id "/cancel")
           :retryUrl (str "/v1/jobs/" id "/retry")
           :createdAt (str created-at)
           :updatedAt (str updated-at)}
    operation-kind (assoc :operationKind (name operation-kind))
    failure (assoc :failureCode (public-failure-code failure))
    (:stage failure-diagnostics) (assoc :stage (:stage failure-diagnostics))
    (:reason failure-diagnostics) (assoc :reason (:reason failure-diagnostics))
    (:requested-moment-count failure-diagnostics)
    (assoc :requestedMomentCount (:requested-moment-count failure-diagnostics))
    (some? (:generated-moment-count failure-diagnostics))
    (assoc :generatedMomentCount (:generated-moment-count failure-diagnostics))
    (:omitted-moment-count failure-diagnostics)
    (assoc :omittedMomentCount (:omitted-moment-count failure-diagnostics))
    (:requested-duration-seconds failure-diagnostics)
    (assoc :requestedDurationSeconds
           (:requested-duration-seconds failure-diagnostics))
    (:status failure-diagnostics) (assoc :status (:status failure-diagnostics))
    (:timeout-ms failure-diagnostics)
    (assoc :timeoutMs (:timeout-ms failure-diagnostics))
    failure-diagnostics (assoc :retryable (:retryable failure-diagnostics)
                               :elapsedMs (:elapsed-ms failure-diagnostics))
    output (assoc :output output)))

(defn retry-eligible?
  "Returns whether a public or stored durable job may start another attempt."
  [{:keys [state retryable failure-diagnostics] :as job}]
  (let [failed? (contains? #{:failed "failed"} state)
        cancelled? (contains? #{:cancelled "cancelled"} state)
        failure-retryable (if (contains? job :retryable)
                            retryable
                            (:retryable failure-diagnostics))]
    (or cancelled?
        (and failed? (not (false? failure-retryable))))))

(defn- active-lease? [now job]
  (when-let [expires-at (get-in job [:lease :expires-at])]
    (.isAfter ^Instant expires-at now)))

(defn- stale-lease? [now job]
  (when-let [expires-at (get-in job [:lease :expires-at])]
    (not (.isAfter ^Instant expires-at now))))

(defn- cancelled-job [job now]
  (-> job
      (assoc :state :cancelled :updated-at now)
      (dissoc :lease :execution :failure :failure-diagnostics)))

(defn- utc-day [^Clock clock]
  (str (LocalDate/now (.withZone clock ZoneOffset/UTC))))

(defn- billing-month [^Clock clock]
  (str (YearMonth/now (.withZone clock billing-zone))))

(defn- member-identity [request]
  {:subject (:requesterSubject request)
   :email (:requesterEmail request)
   :membership-version (:requesterMembershipVersion request)})

(defn- with-member-action [member-directory identity action]
  (if-not member-directory
    (action)
    (try
      (admin/with-active-member! member-directory identity action)
      (catch clojure.lang.ExceptionInfo error
        (if (= ::admin/not-allowlisted (:type (ex-data error)))
          (throw (errors/raise! "Member is no longer allowlisted"
                                {:type ::member-not-allowlisted}
                                error))
          (throw error))))))

(defrecord InMemoryJobService [state enqueued launcher worker ^Clock clock
                               daily-limit monthly-budget-minor-units
                               preview-reservation-minor-units
                               render-reservation-minor-units member-directory]
  JobAccess
  (owns-job? [_ job-id subject]
    (= subject (get-in @state [:jobs job-id :request :requesterSubject])))
  JobReconciler
  (reconcile-jobs! [_]
    (let [now (Instant/now clock)]
      (locking state
        (let [stale-jobs (into {}
                               (filter (fn [[_ job]] (stale-lease? now job)))
                               (:jobs @state))
              repairable-states #{:launching :running :cancellation-requested}
              repaired-jobs (count (filter (fn [[_ job]]
                                             (contains? repairable-states
                                                        (:state job)))
                                           stale-jobs))
              repaired-state
              (reduce-kv
               (fn [current job-id job]
                 (let [updated
                       (case (:state job)
                         :cancellation-requested (cancelled-job job now)
                         (:launching :running)
                         (-> job
                             (assoc :state :failed
                                    :failure "stale_lease"
                                    :updated-at now)
                             (dissoc :lease :execution :output))
                         (dissoc job :lease :execution))]
                   (assoc-in current [:jobs job-id] updated)))
               @state
               stale-jobs)]
          (reset! state repaired-state)
          {:repaired-jobs repaired-jobs
           :released-leases (count stale-jobs)}))))
  JobService
  (submit-job! [_ idempotency-key request]
    (when-not (and (string? idempotency-key)
                   (<= 1 (count idempotency-key) 128))
      (throw (errors/raise! "A bounded Idempotency-Key header is required"
                            {:type ::invalid-idempotency-key})))
    (let [request (contract/normalize-request request)
          _ (contract/prepare request)
          request-digest (request-digest request)
          submit
          (fn []
            (locking state
              (if-let [job-id (get-in @state [:idempotency idempotency-key
                                              :job-id])]
                (let [{stored-digest :digest}
                      (get-in @state [:idempotency idempotency-key])]
                  (when-not (= stored-digest request-digest)
                    (throw (errors/raise!
                            "Idempotency key already belongs to another request"
                            {:type ::idempotency-conflict})))
                  {:created? false :job (get-in @state [:jobs job-id])})
                (let [job-id (str (UUID/randomUUID))
                      now (Instant/now clock)
                      day (utc-day clock)
                      month (billing-month clock)
                      submitted (get-in @state [:admission :daily day] 0)
                      reserved (get-in @state [:admission :monthly month] 0)
                      reservation (reservation-minor-units
                                   request preview-reservation-minor-units
                                   render-reservation-minor-units)
                      _ (when (>= (count (filter #(active-lease? now %)
                                                 (vals (:jobs @state))))
                                  max-active-leases)
                          (throw (errors/raise! "All render leases are held"
                                                {:type ::capacity-exhausted})))
                      _ (when (>= submitted daily-limit)
                          (throw (errors/raise! "Daily submission limit is exhausted"
                                                {:type ::daily-submission-limit-exhausted})))
                      _ (when (> (+ reserved reservation)
                                 monthly-budget-minor-units)
                          (throw (errors/raise! "Monthly compute budget is exhausted"
                                                {:type ::monthly-budget-exhausted})))
                      job (cond-> {:id job-id
                                   :state :queued
                                   :attempt 1
                                   :request request
                                   :reservation-kind
                                   (if (preview-request? request)
                                     :preview
                                     :render)
                                   :reservation-minor-units reservation
                                   :created-at now
                                   :updated-at now}
                            (preview-request? request)
                            (assoc :operation-kind :preview))]
                  (swap! state (fn [current]
                                 (-> current
                                     (assoc-in [:jobs job-id] job)
                                     (assoc-in [:idempotency idempotency-key]
                                               {:job-id job-id
                                                :digest request-digest})
                                     (assoc-in [:admission :daily day]
                                               (inc submitted))
                                     (assoc-in [:admission :monthly month]
                                               (+ reserved reservation)))))
                  {:created? true :job job}))))
          result (with-member-action member-directory
                   (member-identity request)
                   submit)]
      (when (:created? result)
        (swap! enqueued conj {:job-id (get-in result [:job :id])
                              :attempt 1}))
      (update result :job job-resource)))
  (get-job [_ job-id]
    (some-> (get-in @state [:jobs job-id]) job-resource))
  (dispatch-job! [_ job-id]
    (let [now (Instant/now clock)
          admission
          (locking state
            (let [job (get-in @state [:jobs job-id])]
              (when-not job
                (throw (errors/raise! "Job does not exist" {:type ::job-not-found})))
              (if (not= :queued (:state job))
                {:started? false :job job}
                (do
                  (when (>= (count (filter #(active-lease? now %)
                                           (vals (:jobs @state))))
                            max-active-leases)
                    (throw (errors/raise! "All render leases are held"
                                          {:type ::capacity-exhausted})))
                  (let [lease-token (str (UUID/randomUUID))
                        admitted (assoc job
                                        :state :launching
                                        :updated-at now
                                        :lease {:token lease-token
                                                :expires-at
                                                (.plusSeconds now lease-seconds)})]
                    (swap! state assoc-in [:jobs job-id] admitted)
                    {:started? true
                     :lease-token lease-token
                     :job admitted})))))]
      (if-not (:started? admission)
        (update admission :job job-resource)
        (try
          (let [execution (launch-job! launcher job-id)
                launch-result
                (locking state
                  (let [job (get-in @state [:jobs job-id])]
                    (cond
                      (not= (:lease-token admission) (get-in job [:lease :token]))
                      {:job job}

                      (= :cancellation-requested (:state job))
                      (let [updated (assoc job :execution execution)]
                        (swap! state assoc-in [:jobs job-id] updated)
                        {:job updated :cancel? true})

                      :else
                      (let [updated (assoc job
                                           :state :running
                                           :execution execution
                                           :updated-at (Instant/now clock))]
                        (swap! state assoc-in [:jobs job-id] updated)
                        {:job updated}))))]
            (if (:cancel? launch-result)
              (do
                (request-execution-cancellation! launcher execution)
                (let [cancelled
                      (locking state
                        (let [job (get-in @state [:jobs job-id])
                              updated (cancelled-job job (Instant/now clock))]
                          (swap! state assoc-in [:jobs job-id] updated)
                          updated))]
                  {:started? true :job (job-resource cancelled)}))
              {:started? true :job (job-resource (:job launch-result))}))
          (catch Throwable cause
            (locking state
              (let [job (get-in @state [:jobs job-id])]
                (when (= (:lease-token admission) (get-in job [:lease :token]))
                  (swap! state assoc-in [:jobs job-id]
                         (-> job
                             (assoc :state :failed
                                    :failure "launch_failed"
                                    :updated-at (Instant/now clock))
                             (dissoc :lease))))))
            (throw (errors/raise! "Renderer launch failed"
                                  {:type ::launch-failed}
                                  cause)))))))
  (cancel-job! [_ job-id]
    (let [now (Instant/now clock)
          result
          (locking state
            (let [job (get-in @state [:jobs job-id])]
              (when-not job
                (throw (errors/raise! "Job does not exist" {:type ::job-not-found})))
              (case (:state job)
                :cancelled {:job job}
                :queued (let [updated (cancelled-job job now)]
                          (swap! state assoc-in [:jobs job-id] updated)
                          {:job updated})
                :launching (let [updated (assoc job
                                                :state :cancellation-requested
                                                :updated-at now)]
                             (swap! state assoc-in [:jobs job-id] updated)
                             {:job updated})
                :running (let [updated (assoc job
                                              :state :cancellation-requested
                                              :updated-at now)]
                           (swap! state assoc-in [:jobs job-id] updated)
                           {:job updated :execution (:execution job)})
                :cancellation-requested {:job job}
                (throw (errors/raise! "Terminal job cannot be cancelled"
                                      {:type ::invalid-transition
                                       :state (:state job)})))))]
      (if-let [execution (:execution result)]
        (do
          (request-execution-cancellation! launcher execution)
          (let [cancelled
                (locking state
                  (let [job (get-in @state [:jobs job-id])
                        updated (cancelled-job job (Instant/now clock))]
                    (swap! state assoc-in [:jobs job-id] updated)
                    updated))]
            (job-resource cancelled)))
        (job-resource (:job result)))))
  (retry-job! [_ job-id]
    (let [job (locking state (get-in @state [:jobs job-id]))
          _ (when-not job
              (throw (errors/raise! "Job does not exist" {:type ::job-not-found})))
          now (Instant/now clock)
          month (billing-month clock)
          retried
          (with-member-action
            member-directory (member-identity (:request job))
            (fn []
              (locking state
                (let [job (get-in @state [:jobs job-id])]
                  (when-not (retry-eligible? job)
                    (throw (errors/raise! "Job is not eligible for retry"
                                          {:type ::invalid-transition
                                           :state (:state job)})))
                  (let [reserved (get-in @state [:admission :monthly month] 0)
                        reservation (if (= :preview (:operation-kind job))
                                      preview-reservation-minor-units
                                      render-reservation-minor-units)
                        _ (when (>= (count (filter #(active-lease? now %)
                                                   (vals (:jobs @state))))
                                    max-active-leases)
                            (throw (errors/raise! "All render leases are held"
                                                  {:type ::capacity-exhausted})))
                        _ (when (> (+ reserved reservation)
                                   monthly-budget-minor-units)
                            (throw (errors/raise! "Monthly compute budget is exhausted"
                                                  {:type ::monthly-budget-exhausted})))
                        updated (-> job
                                    (assoc :state :queued
                                           :attempt (inc (:attempt job))
                                           :reservation-kind
                                           (if (= :preview (:operation-kind job))
                                             :preview
                                             :render)
                                           :reservation-minor-units reservation
                                           :updated-at now)
                                    (dissoc :lease :execution :failure
                                            :failure-diagnostics :output))]
                    (swap! state
                           (fn [current]
                             (-> current
                                 (assoc-in [:jobs job-id] updated)
                                 (assoc-in [:admission :monthly month]
                                           (+ reserved reservation)))))
                    updated)))))]
      (swap! enqueued conj {:job-id job-id :attempt (:attempt retried)})
      (job-resource retried)))
  (run-job! [_ job-id]
    (let [started (System/nanoTime)
          job (locking state
                (let [current (get-in @state [:jobs job-id])]
                  (when-not current
                    (throw (errors/raise! "Job does not exist" {:type ::job-not-found})))
                  (when-not (= :running (:state current))
                    (throw (errors/raise! "Only a running job can render"
                                          {:type ::invalid-transition
                                           :state (:state current)})))
                  current))]
      (try
        (let [result (perform-render! worker job-id (:request job))
              output-bytes (:output-bytes result)
              completed
              (locking state
                (let [current (get-in @state [:jobs job-id])
                      updated
                      (cond
                        (contains? #{:cancelled :cancellation-requested}
                                   (:state current))
                        (cancelled-job current (Instant/now clock))

                        (> (long (or output-bytes 0)) max-output-bytes)
                        (let [diagnostics
                              {:failure-code "output_too_large"
                               :stage "completion_persistence"
                               :retryable false
                               :elapsed-ms
                               (quot (- (System/nanoTime) started) 1000000)}]
                          (-> current
                              (assoc :state :failed
                                     :failure "output_too_large"
                                     :failure-diagnostics diagnostics
                                     :updated-at (Instant/now clock))
                              (dissoc :lease :execution :output)))

                        :else
                        (-> current
                            (assoc :state :succeeded
                                   :output (dissoc result :output-bytes)
                                   :updated-at (Instant/now clock))
                            (dissoc :lease :execution :failure
                                    :failure-diagnostics)))]
                  (swap! state assoc-in [:jobs job-id] updated)
                  updated))]
          (job-resource completed))
        (catch Throwable cause
          (let [elapsed-ms (quot (- (System/nanoTime) started) 1000000)
                diagnostics (failure-diagnostics cause elapsed-ms)]
            (locking state
              (let [current (get-in @state [:jobs job-id])
                    updated
                    (if (contains? #{:cancelled :cancellation-requested}
                                   (:state current))
                      (cancelled-job current (Instant/now clock))
                      (-> current
                          (assoc :state :failed
                                 :failure (:failure-code diagnostics)
                                 :failure-diagnostics diagnostics
                                 :updated-at (Instant/now clock))
                          (dissoc :lease :execution :output)))]
                (swap! state assoc-in [:jobs job-id] updated))))
          (throw (errors/raise! "Render worker failed"
                                {:type ::worker-failed :job-id job-id}
                                cause))))))
  JobAttemptRunner
  (run-job-attempt! [this job-id attempt]
    (let [current (get-in @state [:jobs job-id])]
      (when-not current
        (throw (errors/raise! "Job does not exist" {:type ::job-not-found})))
      (require-render-attempt! (:attempt current) attempt)
      (try
        (run-job! this job-id)
        (catch Throwable cause
          (errors/raise! "Validated renderer attempt failed"
                         {:type ::render-attempt-failed
                          :attempt attempt}
                         cause)))))
  admin/JobAdministration
  (cancel-member-jobs! [this {:keys [subject] :as cleanup-identity}]
    (let [job-ids
          (locking state
            (->> (:jobs @state)
                 (keep (fn [[job-id job]]
                         (when (and (= subject
                                       (get-in job [:request :requesterSubject]))
                                    (admin/cleanup-generation?
                                     cleanup-identity
                                     (get-in job
                                             [:request
                                              :requesterMembershipVersion]))
                                    (contains? #{:queued :launching :running
                                                 :cancellation-requested}
                                               (:state job)))
                           job-id)))
                 vec))]
      (doseq [job-id job-ids]
        (cancel-job! this job-id))
      (count job-ids))))

(defrecord InMemoryLauncher [launched cancelled]
  JobLauncher
  (launch-job! [_ job-id]
    (let [execution (str "executions/" (UUID/randomUUID))]
      (swap! launched conj {:job-id job-id :execution execution})
      execution))
  (cancel-execution! [_ execution]
    (swap! cancelled conj execution)))

(defn in-memory-system
  ([] (in-memory-system {}))
  ([{:keys [clock launcher worker daily-limit monthly-budget-minor-units
            preview-reservation-minor-units render-reservation-minor-units
            member-directory]
     :or {clock (Clock/systemUTC)
          daily-limit max-daily-submissions
          monthly-budget-minor-units
          default-monthly-budget-minor-units
          preview-reservation-minor-units
          default-preview-reservation-minor-units
          render-reservation-minor-units
          default-render-reservation-minor-units}}]
   (validate-admission-limits! daily-limit monthly-budget-minor-units
                               preview-reservation-minor-units
                               render-reservation-minor-units)
   (let [state (atom {:jobs {} :idempotency {}})
         enqueued (atom [])
         launched (atom [])
         cancelled (atom [])
         launcher (or launcher (->InMemoryLauncher launched cancelled))
         worker (or worker
                    (reify RenderWorker
                      (perform-render! [_ _ _]
                        (throw (errors/raise! "No render worker configured" {})))))]
     {:service (->InMemoryJobService state enqueued launcher worker clock
                                     daily-limit monthly-budget-minor-units
                                     preview-reservation-minor-units
                                     render-reservation-minor-units
                                     member-directory)
      :state state
      :enqueued enqueued
      :launched launched
      :cancelled cancelled})))
