(ns agg.jobs.lifecycle
  (:require [agg.admin.core :as admin]
            [agg.contracts.render :as contract])
  (:import (java.security MessageDigest)
           (java.time Clock Instant LocalDate YearMonth ZoneOffset)
           (java.util HexFormat UUID)))

(defprotocol JobService
  (submit-job! [service idempotency-key request])
  (get-job [service job-id])
  (dispatch-job! [service job-id])
  (cancel-job! [service job-id])
  (retry-job! [service job-id])
  (run-job! [service job-id]))

(defprotocol JobAccess
  (owns-job? [service job-id subject]))

(defprotocol JobReconciler
  (reconcile-jobs! [service]))

(defprotocol JobLauncher
  (launch-job! [launcher job-id])
  (cancel-execution! [launcher execution]))

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
(def default-monthly-budget-cents 3000)
(def default-render-reservation-cents 25)
(def ^:private billing-zone (ZoneOffset/ofHours -8))
(def lease-seconds (* 65 60))
(def max-output-bytes (* 18 1024 1024 1024))

(defn validate-admission-limits!
  [daily-limit monthly-budget-cents render-reservation-cents]
  (when-not (every? #(and (integer? %) (pos? %))
                    [daily-limit monthly-budget-cents
                     render-reservation-cents])
    (throw (ex-info "Admission limits must be positive integers"
                    {:type ::invalid-admission-configuration})))
  true)

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

(defn job-resource [{:keys [id state attempt created-at updated-at failure output]}]
  (cond-> {:id id
           :state (name state)
           :attempt attempt
           :statusUrl (str "/v1/jobs/" id)
           :cancelUrl (str "/v1/jobs/" id "/cancel")
           :retryUrl (str "/v1/jobs/" id "/retry")
           :createdAt (str created-at)
           :updatedAt (str updated-at)}
    failure (assoc :failureCode failure)
    output (assoc :output output)))

(defn- active-lease? [now job]
  (when-let [expires-at (get-in job [:lease :expires-at])]
    (.isAfter ^Instant expires-at now)))

(defn- stale-lease? [now job]
  (when-let [expires-at (get-in job [:lease :expires-at])]
    (not (.isAfter ^Instant expires-at now))))

(defn- cancelled-job [job now]
  (-> job
      (assoc :state :cancelled :updated-at now)
      (dissoc :lease :execution :failure)))

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
          (throw (ex-info "Member is no longer allowlisted"
                          {:type ::member-not-allowlisted}
                          error))
          (throw error))))))

(defrecord InMemoryJobService [state enqueued launcher worker ^Clock clock
                               daily-limit monthly-budget-cents
                               render-reservation-cents member-directory]
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
      (throw (ex-info "A bounded Idempotency-Key header is required"
                      {:type ::invalid-idempotency-key})))
    (contract/prepare request)
    (let [request-digest (request-digest request)
          submit
          (fn []
            (locking state
              (if-let [job-id (get-in @state [:idempotency idempotency-key
                                              :job-id])]
                (let [{stored-digest :digest}
                      (get-in @state [:idempotency idempotency-key])]
                  (when-not (= stored-digest request-digest)
                    (throw (ex-info
                            "Idempotency key already belongs to another request"
                            {:type ::idempotency-conflict})))
                  {:created? false :job (get-in @state [:jobs job-id])})
                (let [job-id (str (UUID/randomUUID))
                      now (Instant/now clock)
                      day (utc-day clock)
                      month (billing-month clock)
                      submitted (get-in @state [:admission :daily day] 0)
                      reserved (get-in @state [:admission :monthly month] 0)
                      _ (when (>= (count (filter #(active-lease? now %)
                                                 (vals (:jobs @state))))
                                  max-active-leases)
                          (throw (ex-info "All render leases are held"
                                          {:type ::capacity-exhausted})))
                      _ (when (>= submitted daily-limit)
                          (throw (ex-info "Daily submission limit is exhausted"
                                          {:type ::daily-submission-limit-exhausted})))
                      _ (when (> (+ reserved render-reservation-cents)
                                 monthly-budget-cents)
                          (throw (ex-info "Monthly compute budget is exhausted"
                                          {:type ::monthly-budget-exhausted})))
                      job {:id job-id
                           :state :queued
                           :attempt 1
                           :request request
                           :created-at now
                           :updated-at now}]
                  (swap! state (fn [current]
                                 (-> current
                                     (assoc-in [:jobs job-id] job)
                                     (assoc-in [:idempotency idempotency-key]
                                               {:job-id job-id
                                                :digest request-digest})
                                     (assoc-in [:admission :daily day]
                                               (inc submitted))
                                     (assoc-in [:admission :monthly month]
                                               (+ reserved
                                                  render-reservation-cents)))))
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
                (throw (ex-info "Job does not exist" {:type ::job-not-found})))
              (if (not= :queued (:state job))
                {:started? false :job job}
                (do
                  (when (>= (count (filter #(active-lease? now %)
                                           (vals (:jobs @state))))
                            max-active-leases)
                    (throw (ex-info "All render leases are held"
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
                (cancel-execution! launcher execution)
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
            (throw (ex-info "Renderer launch failed"
                            {:type ::launch-failed}
                            cause)))))))
  (cancel-job! [_ job-id]
    (let [now (Instant/now clock)
          result
          (locking state
            (let [job (get-in @state [:jobs job-id])]
              (when-not job
                (throw (ex-info "Job does not exist" {:type ::job-not-found})))
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
                (throw (ex-info "Terminal job cannot be cancelled"
                                {:type ::invalid-transition
                                 :state (:state job)})))))]
      (if-let [execution (:execution result)]
        (do
          (cancel-execution! launcher execution)
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
              (throw (ex-info "Job does not exist" {:type ::job-not-found})))
          now (Instant/now clock)
          month (billing-month clock)
          retried
          (with-member-action
            member-directory (member-identity (:request job))
            (fn []
              (locking state
                (let [job (get-in @state [:jobs job-id])]
                  (when-not (contains? #{:cancelled :failed} (:state job))
                    (throw (ex-info "Only failed or cancelled jobs can be retried"
                                    {:type ::invalid-transition
                                     :state (:state job)})))
                  (let [reserved (get-in @state [:admission :monthly month] 0)
                        _ (when (>= (count (filter #(active-lease? now %)
                                                   (vals (:jobs @state))))
                                    max-active-leases)
                            (throw (ex-info "All render leases are held"
                                            {:type ::capacity-exhausted})))
                        _ (when (> (+ reserved render-reservation-cents)
                                   monthly-budget-cents)
                            (throw (ex-info "Monthly compute budget is exhausted"
                                            {:type ::monthly-budget-exhausted})))
                        updated (-> job
                                    (assoc :state :queued
                                           :attempt (inc (:attempt job))
                                           :updated-at now)
                                    (dissoc :lease :execution :failure :output))]
                    (swap! state
                           (fn [current]
                             (-> current
                                 (assoc-in [:jobs job-id] updated)
                                 (assoc-in [:admission :monthly month]
                                           (+ reserved
                                              render-reservation-cents)))))
                    updated)))))]
      (swap! enqueued conj {:job-id job-id :attempt (:attempt retried)})
      (job-resource retried)))
  (run-job! [_ job-id]
    (let [job (locking state
                (let [current (get-in @state [:jobs job-id])]
                  (when-not current
                    (throw (ex-info "Job does not exist" {:type ::job-not-found})))
                  (when-not (= :running (:state current))
                    (throw (ex-info "Only a running job can render"
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
                        (-> current
                            (assoc :state :failed
                                   :failure "output_too_large"
                                   :updated-at (Instant/now clock))
                            (dissoc :lease :execution :output))

                        :else
                        (-> current
                            (assoc :state :succeeded
                                   :output (dissoc result :output-bytes)
                                   :updated-at (Instant/now clock))
                            (dissoc :lease :execution :failure)))]
                  (swap! state assoc-in [:jobs job-id] updated)
                  updated))]
          (job-resource completed))
        (catch Throwable cause
          (locking state
            (let [current (get-in @state [:jobs job-id])
                  updated
                  (if (contains? #{:cancelled :cancellation-requested}
                                 (:state current))
                    (cancelled-job current (Instant/now clock))
                    (-> current
                        (assoc :state :failed
                               :failure "worker_failed"
                               :updated-at (Instant/now clock))
                        (dissoc :lease :execution :output)))]
              (swap! state assoc-in [:jobs job-id] updated)))
          (throw (ex-info "Render worker failed"
                          {:type ::worker-failed :job-id job-id}
                          cause))))))
  admin/JobAdministration
  (cancel-member-jobs! [this subject]
    (let [job-ids
          (locking state
            (->> (:jobs @state)
                 (keep (fn [[job-id job]]
                         (when (and (= subject
                                       (get-in job [:request :requesterSubject]))
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
  ([{:keys [clock launcher worker daily-limit monthly-budget-cents
            render-reservation-cents member-directory]
     :or {clock (Clock/systemUTC)
          daily-limit max-daily-submissions
          monthly-budget-cents default-monthly-budget-cents
          render-reservation-cents default-render-reservation-cents}}]
   (validate-admission-limits! daily-limit monthly-budget-cents
                               render-reservation-cents)
   (let [state (atom {:jobs {} :idempotency {}})
         enqueued (atom [])
         launched (atom [])
         cancelled (atom [])
         launcher (or launcher (->InMemoryLauncher launched cancelled))
         worker (or worker
                    (reify RenderWorker
                      (perform-render! [_ _ _]
                        (throw (ex-info "No render worker configured" {})))))]
     {:service (->InMemoryJobService state enqueued launcher worker clock
                                     daily-limit monthly-budget-cents
                                     render-reservation-cents member-directory)
      :state state
      :enqueued enqueued
      :launched launched
      :cancelled cancelled})))
