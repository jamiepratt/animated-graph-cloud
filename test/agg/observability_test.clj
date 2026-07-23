(ns agg.observability-test
  (:require [agg.api.main :as api]
            [agg.errors :as errors]
            [agg.http-test-support :as test-http]
            [agg.jobs.lifecycle :as jobs]
            [agg.observability :as observability]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [taoensso.telemere :as tel]
            [taoensso.tufte :as tufte]))

(defn- available-port []
  (test-http/available-port))

(defn- start-api!
  ([port] (start-api! port {}))
  ([port dependencies]
   (api/start! port dependencies)))

(defn- valid-render-request []
  {:telemetryFormat "polar-csv"
   :telemetry (slurp (io/resource "fixtures/polar/valid.csv"))
   :preset "1080p25"
   :synchronizationMode "manual-anchor"
   :telemetrySyncAt "2026-07-17T10:00:00Z"
   :cameraSyncAt "2026-07-17T09:00:00Z"
   :sectionStartAt "2026-07-17T09:00:00Z"
   :sectionEndAt "2026-07-17T09:00:02Z"
   :displayTimeZone "Europe/Warsaw"})

(deftest telemere-event-keeps-event-id-and-safe-fields-only
  (let [signal (tel/with-signal
                 (observability/emit-event!
                  "api"
                  "admission_rejected"
                  {:severity "WARNING"
                   :reason "capacity_exhausted"
                   :queueAgeMs 12
                   :requestBody "telemetry"
                   :token "secret"
                   :credential "secret"
                   :filename "private.mov"
                   :signedUrl "https://storage.example/signed"}))]
    (is (= :api/admission_rejected (:id signal)))
    (is (= {:severity "WARNING"
            :component "api"
            :event "admission_rejected"
            :reason "capacity_exhausted"
            :queueAgeMs 12}
           (:data signal)))
    (is (not-any? #(contains? (:data signal) %)
                  [:requestBody :token :credential :filename :signedUrl]))))

(deftest preview-timeout-event-keeps-only-bounded-diagnostics
  (let [fields (observability/safe-event-fields
                {:severity "ERROR"
                 :component "api"
                 :event "request_failed"
                 :requestId "00000000-0000-0000-0000-000000000000"
                 :category "preview_timeout"
                 :stage "frame_compose"
                 :status 504
                 :elapsedMs 45001
                 :timeoutMs 45000
                 :retryable true
                 :fileId "private-file"
                 :filename "private.mov"
                 :requestBody "private-telemetry"})]
    (is (= {:severity "ERROR"
            :component "api"
            :event "request_failed"
            :requestId "00000000-0000-0000-0000-000000000000"
            :category "preview_timeout"
            :stage "frame_compose"
            :status 504
            :elapsedMs 45001
            :timeoutMs 45000
            :retryable true}
           fields))
    (is (empty? (observability/safe-event-fields {:stage "private.mov"})))))

(deftest early-access-delivery-event-keeps-only-bounded-operations-data
  (is (= {:severity "ERROR"
          :component "api"
          :event "early_access_notification_failed"
          :category "early_access_delivery"
          :upstreamStatus 503
          :retryable true
          :sourceFile "agg/early_access/resend.clj"
          :sourceLine 91
          :sourceColumn 18}
         (observability/safe-event-fields
          {:severity "ERROR"
           :component "api"
           :event "early_access_notification_failed"
           :category "early_access_delivery"
           :upstreamStatus 503
           :retryable true
           :sourceFile "agg/early_access/resend.clj"
           :sourceLine 91
           :sourceColumn 18
           :email "verified@example.com"
           :instagram "@runner"
           :message "private message"
           :proof "private-proof"
           :apiKey "private-key"
           :providerBody "private response"}))))

(deftest partial-preview-event-keeps-bounded-source-duration-counts
  (let [base {:severity "WARNING"
              :component "renderer"
              :event "preview_partial_gallery"
              :requestId "00000000-0000-0000-0000-000000000084"
              :reason "source_duration_too_short"
              :retryable false}]
    (is (= (assoc base
                  :requestedMomentCount 4
                  :generatedMomentCount 3
                  :omittedMomentCount 1
                  :requestedDurationSeconds 20)
           (observability/safe-event-fields
            (assoc base
                   :requestedMomentCount 4
                   :generatedMomentCount 3
                   :omittedMomentCount 1
                   :requestedDurationSeconds 20))))
    (is (= base
           (observability/safe-event-fields
            (assoc base
                   :requestedMomentCount 33
                   :generatedMomentCount -1
                   :omittedMomentCount 33
                   :requestedDurationSeconds 481))))))

(deftest cloud-render-event-keeps-the-bounded-durable-diagnosis
  (is (= {:severity "ERROR"
          :component "renderer"
          :event "cloud_render_failed"
          :failureCode "composition_encode_failed"
          :stage "composition_encode"
          :status 422
          :retryable false
          :attempt 2
          :elapsedMs 9123}
         (observability/safe-event-fields
          {:severity "ERROR"
           :component "renderer"
           :event "cloud_render_failed"
           :failureCode "composition_encode_failed"
           :stage "composition_encode"
           :status 422
           :retryable false
           :attempt 2
           :elapsedMs 9123
           :filename "private.mov"
           :token "secret"}))))

(deftest cloud-render-progress-keeps-only-bounded-numeric-evidence
  (is (= {:severity "INFO"
          :component "renderer"
          :event "cloud_render_progress"
          :message "Cloud renderer job is active"
          :stage "composition_encode"
          :elapsedMs 1200
          :progressPercent 50}
         (observability/safe-event-fields
          {:severity "INFO"
           :component "renderer"
           :event "cloud_render_progress"
           :message "Cloud renderer job is active"
           :stage "composition_encode"
           :elapsedMs 1200
           :progressPercent 50
           :fileId "private-file"
           :telemetry "private"}))))

(deftest tufte-emits-structured-profile-signal
  (let [signals (atom [])]
    (tufte/with-handler
      ::capture
      (fn
        ([signal] (swap! signals conj signal))
        ([] nil))
      {:async nil}
      (tufte/profile {:id ::render}
                     (tufte/p ::audio-generation (+ 1 1))))
    (let [signal (some #(when (= ::render (:id %)) %) @signals)]
      (is signal)
      (is (:pstats signal)))))

(deftest api-emits-one-bounded-signal-for-owned-failures
  (let [events (atom [])
        contention-service
        (reify jobs/JobService
          (submit-job! [_ _ _]
            (errors/raise! "contention"
                           {:type ::jobs/transaction-contention
                            :retryable true}))
          (get-job [_ _] nil)
          (dispatch-job! [_ _] nil)
          (cancel-job! [_ _] nil)
          (retry-job! [_ _] nil)
          (run-job! [_ _] nil))
        port (available-port)
        server (start-api! port {:job-service contention-service
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj
                                          (assoc fields :event event)))})]
    (try
      (let [response (test-http/send-string!
                      :post
                      (str "http://127.0.0.1:" port "/v1/jobs")
                      (json/write-str (valid-render-request))
                      {"Content-Type" "application/json"
                       "Idempotency-Key" "test-key"})]
        (is (= 503 (.statusCode response)))
        (is (= ["job_failed"] (mapv :event @events))))
      (finally
        (.close ^java.lang.AutoCloseable server)))
    (let [validation-events (atom [])
          validation-port (available-port)
          validation-server
          (start-api! validation-port
                      {:job-service contention-service
                       :event-sink
                       (fn [event fields]
                         (swap! validation-events conj
                                (assoc fields :event event)))})]
      (try
        (let [response (test-http/send-string!
                        :post
                        (str "http://127.0.0.1:" validation-port "/v1/jobs")
                        (json/write-str
                         (assoc (valid-render-request)
                                :telemetryFormat "unknown"))
                        {"Content-Type" "application/json"
                         "Idempotency-Key" "validation-key"})
              request-id (some-> response .headers
                                 (.firstValue "x-request-id") (.orElse nil))]
          (is (= 400 (.statusCode response)))
          (is (= [{:event "request_failed"
                   :severity "WARNING"
                   :requestId request-id
                   :category "request_contract"
                   :status 400
                   :retryable false
                   :failureCode "unsupported_telemetry_format"
                   :field "telemetryFormat"}]
                 @validation-events)))
        (finally
          (.close ^java.lang.AutoCloseable validation-server))))))
