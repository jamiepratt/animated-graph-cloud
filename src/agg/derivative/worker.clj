(ns agg.derivative.worker
  (:refer-clojure :exclude [run!])
  (:require [agg.derivative.contract :as contract]
            [agg.derivative.lifecycle :as lifecycle]
            [agg.drive.range-proxy :as range-proxy]
            [agg.errors :as errors]
            [agg.observability :as observability]
            [agg.render.derivative :as render-derivative]
            [clojure.string :as str])
  (:import (java.io Closeable)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)))

(def profile render-derivative/profile-v1)

(declare run!)

(def ^:private cloud-timeout-ms
  (* 1000
     (get-in contract/contract-v1 [:limits :compute :timeout-seconds])))
(def ^:private cleanup-margin-ms 60000)
(def ^:private worker-compute-timeout-ms
  (- cloud-timeout-ms cleanup-margin-ms))

(def ^:private runtime-limit-contract
  [["AGG_DERIVATIVE_MAX_SOURCE_DURATION_SECONDS"
    :source-duration-seconds [:limits :source :max-duration-seconds]]
   ["AGG_DERIVATIVE_MAX_SOURCE_BYTES"
    :source-bytes [:limits :source :max-bytes]]
   ["AGG_DERIVATIVE_MAX_UPSTREAM_BYTES"
    :upstream-bytes [:limits :transfer :max-upstream-bytes]]
   ["AGG_DERIVATIVE_MAX_REQUEST_COUNT"
    :request-count [:limits :transfer :max-request-count]]
   ["AGG_DERIVATIVE_MAX_RANGE_BYTES"
    :range-bytes [:limits :transfer :max-range-bytes]]
   ["AGG_DERIVATIVE_MAX_OUTPUT_BYTES"
    :output-bytes [:limits :output :max-bytes]]])

(def ^:private safe-result-keys
  [:output-path :content-type :output-bytes :duration-seconds
   :video :audio :fast-start?])

(defn runtime-limits-from-environment
  "Validates #192 runtime limits against #191's semantic contract."
  [environment]
  (into {}
        (map
         (fn [[environment-name field contract-path]]
           (let [configured (some-> (get environment environment-name)
                                    parse-long)
                 expected (get-in contract/contract-v1 contract-path)]
             (when-not (= expected configured)
               (throw
                (errors/raise! "Derivative runtime contract is invalid"
                               {:type ::invalid-runtime-contract
                                :field (name field)
                                :limit expected})))
             [field expected])))
        runtime-limit-contract))

(defn parse-options [args]
  (let [options (if (= 4 (count args))
                  (apply hash-map args)
                  {})
        job-id (get options "--job-id")
        attempt (some-> (get options "--attempt") parse-long)
        valid-id?
        (try
          (and (string? job-id) (UUID/fromString job-id))
          (catch IllegalArgumentException _
            false))]
    (when-not (and (= #{"--job-id" "--attempt"} (set (keys options)))
                   valid-id?
                   (pos-int? attempt))
      (throw
       (errors/raise! "Derivative worker options are invalid"
                      {:type ::invalid-options})))
    {:job-id job-id :attempt attempt}))

(defn- present-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- valid-optional-string? [value]
  (or (nil? value) (present-string? value)))

(defn- require-attempt-record!
  [{:keys [job-id attempt profile asset source owner] :as record}
   expected-job-id expected-attempt]
  (when-not
   (and (= expected-job-id job-id)
        (= expected-attempt attempt)
        (= "production" (:environment record))
        (= render-derivative/profile-v1 profile)
        (= job-id (:id asset))
        (present-string? (:object-key asset))
        (present-string? (:file-id source))
        (valid-optional-string? (:drive-version source))
        (pos-int? (:bytes source))
        (number? (:duration-seconds source))
        (pos? (:duration-seconds source))
        (present-string? (:subject owner))
        (valid-optional-string? (:membership-version owner)))
    (throw
     (errors/raise! "Derivative preparation attempt is invalid"
                    {:type ::invalid-attempt
                     :failure-code "invalid_derivative_attempt"})))
  record)

(defn- require-source-access!
  [{:keys [gateway access-token file-id] :as access} expected-file-id]
  (when-not (and gateway
                 (present-string? access-token)
                 (= expected-file-id file-id))
    (throw
     (errors/raise! "Derivative source access is invalid"
                    {:type ::invalid-source-access
                     :failure-code "invalid_derivative_attempt"})))
  access)

(def ^:private cancellation-poll-nanos (* 1000 1000 1000))

(defn- bounded-cancellation-check [check!]
  (let [last-check (atom nil)
        last-result (atom false)]
    (fn []
      (let [now (System/nanoTime)]
        (when (or (nil? @last-check)
                  (>= (- now @last-check) cancellation-poll-nanos))
          (reset! last-result (boolean (check!)))
          (reset! last-check now))
        @last-result))))

(defn- retryable-failure? [type]
  (contains? #{::render-derivative/encode-failed
               ::render-derivative/timeout}
             type))

(defn- report-failure!
  [{:keys [service fail-preparation-attempt!
           acknowledge-preparation-cancellation!]}
   job-id attempt error]
  (if (contains? #{::render-derivative/cancelled
                   ::publication-cancelled}
                 (:type (ex-data error)))
    (when acknowledge-preparation-cancellation!
      (acknowledge-preparation-cancellation! service job-id attempt))
    (when fail-preparation-attempt!
      (let [data (ex-data error)
            failure-code (:failure-code data)]
        (fail-preparation-attempt!
         service job-id attempt
         {:failure-code
          (if (contains? contract/public-error-codes-v1 failure-code)
            failure-code
            "derivative_failed")
          :retryable (retryable-failure? (:type data))})))))

(defn- duration-bucket [duration-seconds]
  (cond
    (< duration-seconds 30) "under_30_seconds"
    (< duration-seconds 120) "30_to_119_seconds"
    (< duration-seconds 300) "120_to_299_seconds"
    :else "300_to_480_seconds"))

(defn- elapsed-millis [started-nanos]
  (quot (- (System/nanoTime) started-nanos) 1000000))

(defn- emit-worker-event! [event fields]
  (observability/emit-context-event! "derivative" event fields))

(defn- emit-worker-stage! [stage]
  (when-let [[event operation status]
             (get
              {:streaming-started
               ["derivative_streaming_started"
                "derivative_encode" "started"]
               :inspection-started
               ["derivative_inspection_started"
                "derivative_encode" "started"]
               :inspection-completed
               ["derivative_inspection_succeeded"
                "derivative_encode" "succeeded"]
               :ffmpeg-started
               ["derivative_ffmpeg_started"
                "derivative_encode" "started"]
               :ffmpeg-completed
               ["derivative_ffmpeg_exited"
                "derivative_encode" "succeeded"]
               :verification-started
               ["derivative_verification_started"
                "derivative_verification" "started"]
               :verification-completed
               ["derivative_verification_succeeded"
                "derivative_verification" "succeeded"]
               :streaming-stopped
               ["derivative_streaming_stopped"
                "derivative_encode" "succeeded"]}
              stage)]
    (emit-worker-event!
     event
     {:severity "INFO"
      :operation operation
      :status status})))

(defn run-cloud-attempt!
  "Loads, verifies, immutably publishes, and completes one exact private attempt."
  [{:keys [job-id attempt]}
   {:keys [service output-path load-preparation-attempt source-access!
           preparation-cancellation-requested? publish-derivative!
           delete-derivative! complete-preparation-attempt!]
    :as dependencies}]
  (let [record
        (require-attempt-record!
         (load-preparation-attempt service job-id attempt)
         job-id attempt)
        source (:source record)
        asset (:asset record)
        access
        (require-source-access!
         (source-access! service job-id attempt)
         (:file-id source))
        output-path
        (or output-path
            (Files/createTempFile
             "agg-derivative-output-" ".mp4"
             (make-array FileAttribute 0)))
        cancellation-requested?
        #(preparation-cancellation-requested? service job-id attempt)
        cancelled?
        (bounded-cancellation-check
         cancellation-requested?)
        published (atom nil)
        phase (atom :encode)
        attempt-started-nanos (System/nanoTime)
        correlation
        (merge
         {:requestId (get-in record [:observability :request-id])
          :attempt attempt
          :profileVersion (:version profile)
          :reservedMinorUnits
          (long
           (or (get-in record [:observability :reservation-minor-units]) 0))}
         (select-keys (get record :observability) [:trace :revision]))]
    (observability/with-event-context
      {:fields correlation
       :event-sink (:event-sink dependencies)}
      (Files/deleteIfExists ^Path output-path)
      (try
        (let [encode-started-nanos (System/nanoTime)
              _ (emit-worker-event!
                 "derivative_encode_started"
                 {:severity "INFO"
                  :operation "derivative_encode"
                  :status "started"
                  :sourceBytes (:bytes source)
                  :durationBucket
                  (duration-bucket (:duration-seconds source))})
              verified
              (run!
               {:classification :derivative-required
                :source-duration-seconds (:duration-seconds source)
                :source-bytes (:bytes source)
                :output-path output-path}
               (assoc dependencies
                      :proxy-config access
                      :cancelled? cancelled?
                      :stage! emit-worker-stage!))
              _ (emit-worker-event!
                 "derivative_encode_exited"
                 {:severity "INFO"
                  :operation "derivative_encode"
                  :status "succeeded"
                  :elapsedMs (elapsed-millis encode-started-nanos)
                  :sourceBytes (:bytes source)
                  :upstreamBytes (get-in verified [:transfer :upstream-bytes])
                  :outputBytes (:output-bytes verified)})
              _ (reset! phase :publication)
              publication
              {:job-id job-id
               :attempt attempt
               :asset-id (:id asset)
               :object-key (:object-key asset)
               :output-path (:output-path verified)
               :content-type (:content-type verified)
               :size (:output-bytes verified)
               :profile-version (:version profile)}
              _ (emit-worker-event!
                 "derivative_publication_started"
                 {:severity "INFO"
                  :operation "derivative_publication"
                  :status "started"
                  :outputBytes (:output-bytes verified)})
              stored (publish-derivative! service publication)
              _ (emit-worker-event!
                 "derivative_publication_succeeded"
                 {:severity "INFO"
                  :operation "derivative_publication"
                  :status "succeeded"
                  :outputBytes (:size stored)})
              _ (reset! phase :completion)
              completion
              {:asset-id (:id asset)
               :object-key (:object-key asset)
               :generation (:generation stored)
               :size (:size stored)
               :content-type (:content-type stored)
               :profile-version (:profile-version stored)
               :measurements {:output-bytes (:output-bytes verified)}}]
          (reset! published
                  {:object-key (:object-key asset)
                   :generation (:generation stored)})
          (when (cancellation-requested?)
            (throw
             (errors/raise! "Derivative publication was cancelled"
                            {:type ::publication-cancelled
                             :reason "cancelled"})))
          (let [completed
                (complete-preparation-attempt!
                 service job-id attempt completion)]
            (reset! published nil)
            (emit-worker-event!
             "derivative_preparation_terminal"
             {:severity "INFO"
              :operation "derivative_preparation"
              :status "succeeded"
              :reason "completed"
              :elapsedMs (elapsed-millis attempt-started-nanos)
              :outputBytes (:output-bytes verified)})
            completed))
        (catch Throwable error
          (let [data (ex-data error)
                cancelled?
                (contains? #{::render-derivative/cancelled
                             ::publication-cancelled}
                           (:type data))
                retryable (retryable-failure? (:type data))
                failure-code
                (if (contains? contract/public-error-codes-v1
                               (:failure-code data))
                  (:failure-code data)
                  (cond
                    cancelled? "cancelled"
                    (= :publication @phase) "publication_failed"
                    :else "derivative_failed"))
                failure-fields
                (cond->
                 (merge
                  {:severity (if cancelled? "WARNING" "ERROR")
                   :status (if cancelled? "cancelled" "failed")
                   :reason failure-code
                   :errorType (some-> (:type data) str)
                   :retryable retryable
                   :elapsedMs (elapsed-millis attempt-started-nanos)}
                  (observability/exception-fields error))
                  (= ::render-derivative/verification-failed (:type data))
                  (assoc :verificationFailures
                         (:verification-failures data)))]
            (case @phase
              :encode
              (emit-worker-event!
               "derivative_encode_exited"
               (assoc failure-fields :operation "derivative_encode"))

              :publication
              (emit-worker-event!
               "derivative_publication_failed"
               (assoc failure-fields :operation "derivative_publication"))

              nil)
            (when (and @published delete-derivative!)
              (try
                (delete-derivative! service @published)
                (catch Throwable _
                  nil)))
            (Files/deleteIfExists ^Path output-path)
            (let [terminal
                  (try
                    (report-failure! dependencies job-id attempt error)
                    (catch Throwable _
                      nil))
                  terminal-fields
                  (cond-> failure-fields
                    cancelled?
                    (assoc :status (or (:state terminal) "cancelled")
                           :reason (or (:state terminal) "cancelled"))
                    (and cancelled? (contains? terminal :cancellationLagMs))
                    (assoc :cancellationLagMs
                           (:cancellationLagMs terminal)))]
              (when (or (not cancelled?)
                        (lifecycle/terminal-transition? terminal))
                (emit-worker-event!
                 "derivative_preparation_terminal"
                 (assoc terminal-fields
                        :operation "derivative_preparation")))))
          (throw error))
        (finally
          (Files/deleteIfExists ^Path output-path)
          (emit-worker-event!
           "derivative_cleanup_completed"
           {:severity "INFO"
            :operation "derivative_cleanup"
            :status "succeeded"}))))))

(defn- required-resolve [symbol]
  (or (requiring-resolve symbol)
      (throw
       (errors/raise! "Derivative worker integration is unavailable"
                      {:type ::missing-integration}))))

(defn- cloud-dependencies []
  (let [worker-service
        (required-resolve 'agg.derivative.gcp/worker-service)]
    {:service (worker-service)
     :load-preparation-attempt
     (required-resolve
      'agg.derivative.lifecycle/load-preparation-attempt)
     :source-access!
     (required-resolve 'agg.derivative.gcp/source-access!)
     :preparation-cancellation-requested?
     (required-resolve
      'agg.derivative.lifecycle/preparation-cancellation-requested?)
     :fail-preparation-attempt!
     (required-resolve
      'agg.derivative.lifecycle/fail-preparation-attempt!)
     :acknowledge-preparation-cancellation!
     (required-resolve
      'agg.derivative.lifecycle/acknowledge-preparation-cancellation!)
     :publish-derivative!
     (required-resolve 'agg.derivative.gcp/publish-derivative!)
     :delete-derivative!
     (required-resolve 'agg.derivative.gcp/delete-derivative!)
     :complete-preparation-attempt!
     (required-resolve
      'agg.derivative.lifecycle/complete-preparation-attempt!)}))

(defn -main [& args]
  (try
    (runtime-limits-from-environment (System/getenv))
    (run-cloud-attempt! (parse-options args) (cloud-dependencies))
    nil
    (catch Throwable _
      (System/exit 1))))

(defn- require-derivative! [classification]
  (when-not (= :derivative-required classification)
    (throw
     (errors/raise! "Source is not eligible for derivative encoding"
                    {:type ::source-not-renderable
                     :failure-code "derivative_source_not_renderable"}))))

(defn- transfer-result [stats]
  (select-keys stats
               [:upstream-bytes :request-count :retry-count :cache-hit-count]))

(defn- validate-source! [{:keys [source-duration-seconds source-bytes]}]
  (contract/validate-work!
   {:source-duration-seconds source-duration-seconds
    :source-bytes source-bytes}))

(defn- validate-result! [result stats]
  (contract/validate-work!
   {:upstream-bytes (:upstream-bytes stats)
    :request-count (:request-count stats)
    :range-bytes
    (get-in contract/contract-v1 [:limits :transfer :max-range-bytes])
    :output-bytes (:output-bytes result)}))

(defn- delete-output! [output-path]
  (when (instance? Path output-path)
    (Files/deleteIfExists ^Path output-path)))

(defn- remaining-time-ms [deadline-nanos timeout-ms]
  (let [remaining
        (quot (- deadline-nanos (System/nanoTime)) 1000000)]
    (when-not (pos? remaining)
      (throw
       (errors/raise! "Derivative worker exceeded its compute deadline"
                      {:type ::render-derivative/timeout
                       :failure-code "derivative_timeout"
                       :timeout-ms timeout-ms})))
    remaining))

(defn- proxy-failure!
  [stats cause]
  (let [reason (:failure-reason stats)
        request-limit
        (get-in contract/contract-v1
                [:limits :transfer :max-request-count])]
    (case reason
      "work_budget_exhausted"
      (if (>= (long (or (:request-count stats) 0)) request-limit)
        (throw
         (errors/raise! "Derivative source request limit was exhausted"
                        {:type ::transfer-exceeded
                         :failure-code "range_request_limit_exceeded"}
                        cause))
        (throw
         (errors/raise! "Derivative source transfer limit was exhausted"
                        {:type ::transfer-exceeded
                         :failure-code "upstream_transfer_exceeded"}
                        cause)))

      "lifetime_exhausted"
      (throw
       (errors/raise! "Derivative source transfer exceeded its deadline"
                      {:type ::transfer-timeout
                       :failure-code "derivative_timeout"}
                      cause))

      ("invalid_upstream_response" "upstream_timeout"
                                   "concurrency_exhausted")
      (throw
       (errors/raise! "Derivative source transfer failed"
                      {:type ::transfer-failed
                       :failure-code "derivative_transfer_failed"}
                      cause))

      (throw cause))))

(defn run!
  "Runs one derivative encode while confining source identity to its proxy."
  [{:keys [classification source-duration-seconds output-path]
    :as request}
   {:keys [proxy-config start-source-proxy! inspect-source! encode! cancelled?
           ffmpeg ffprobe timeout-ms stage!]
    :or {start-source-proxy! range-proxy/start!
         inspect-source! render-derivative/inspect-source!
         encode! render-derivative/encode!
         cancelled? (constantly false)
         ffmpeg "ffmpeg"
         ffprobe "ffprobe"
         timeout-ms worker-compute-timeout-ms
         stage! (fn [_])}}]
  (if (= :direct-passthrough classification)
    {:classification :direct-passthrough}
    (do
      (require-derivative! classification)
      (validate-source! request)
      (when-not (and (integer? timeout-ms)
                     (pos? timeout-ms)
                     (<= timeout-ms worker-compute-timeout-ms))
        (throw
         (errors/raise! "Derivative worker deadline is invalid"
                        {:type ::invalid-runtime-contract
                         :field "timeout-ms"
                         :limit worker-compute-timeout-ms})))
      (let [deadline-nanos
            (+ (System/nanoTime) (* timeout-ms 1000000))
            proxy
            ^Closeable
            (start-source-proxy!
             (assoc proxy-config :size (:source-bytes request)
                    :stream-open-ended? true
                    :limits
                    (assoc range-proxy/derivative-limits-v1
                           :lifetime-ms timeout-ms)))]
        (stage! :streaming-started)
        (try
          (stage! :inspection-started)
          (let [{:keys [width height audio?]}
                (inspect-source!
                 {:ffprobe ffprobe
                  :source-url (:url proxy)
                  :timeout-ms
                  (remaining-time-ms deadline-nanos timeout-ms)})
                _ (stage! :inspection-completed)
                result
                (encode!
                 {:ffmpeg ffmpeg
                  :ffprobe ffprobe
                  :source-url (:url proxy)
                  :source-duration-seconds source-duration-seconds
                  :source-width width
                  :source-height height
                  :source-has-audio? audio?
                  :output-path output-path
                  :timeout-ms
                  (remaining-time-ms deadline-nanos timeout-ms)
                  :cancelled? cancelled?
                  :stage! stage!})
                stats ((:stats proxy))]
            (validate-result! result stats)
            (assoc (select-keys result safe-result-keys)
                   :classification :derivative-ready
                   :transfer (transfer-result stats)))
          (catch Throwable error
            (delete-output! output-path)
            (proxy-failure! ((:stats proxy)) error))
          (finally
            (.close proxy)
            (stage! :streaming-stopped)))))))
