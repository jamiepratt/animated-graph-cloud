(ns agg.derivative-contract-test
  (:require [agg.derivative.contract :as derivative]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest production-private-preview-contract-is-environment-isolated
  (let [production (derivative/environment-contract :production)
        proto (derivative/environment-contract :proto)]
    (is (= "production" (:environment production)))
    (is (= "proto" (:environment proto)))
    (is (= #{:jobs :cache :idempotency :active-jobs :objects}
           (set (keys (:namespaces production)))))
    (doseq [boundary [:jobs :cache :idempotency :active-jobs :objects]]
      (testing (name boundary)
        (is (not= (get-in production [:namespaces boundary])
                  (get-in proto [:namespaces boundary])))))
    (is (= production (derivative/environment-contract :production)))))

(deftest approved-private-preview-envelope-is-one-versioned-contract
  (is
   (=
    {:version "production-private-preview-contract-v1"
     :environment "production"
     :profile
     {:version "h264-aac-1080p25-v1"
      :container {:format "mp4" :fast-start? true}
      :video {:codec "h264"
              :encoder "libx264"
              :profile "high"
              :level "4.0"
              :pixel-format "yuv420p"
              :fps 25
              :max-width 1920
              :max-height 1080
              :upscale? false
              :crf 23
              :preset "fast"
              :max-rate-bps 4000000
              :buffer-size-bps 8000000
              :gop-frames 50
              :scene-cut-keyframes? false}
      :audio {:codec "aac"
              :profile "aac_low"
              :sample-rate 48000
              :channels 2
              :bitrate-bps 128000
              :silence-when-missing? true}
      :verify #{:audio :codec :dimensions :duration :fast-start
                :size :stream-layout}}
     :limits
     {:source {:max-duration-seconds 480
               :max-bytes 2147483648}
      :transfer {:max-upstream-bytes 2415919104
                 :max-request-count 320
                 :max-range-bytes 8388608}
      :output {:max-bytes 268435456}
      :compute {:cpu 4
                :memory-bytes 4294967296
                :timeout-seconds 900
                :task-count 1
                :automatic-retries 0}
      :concurrency {:queue-max-concurrent-dispatches 1
                    :queue-max-dispatches-per-second 1
                    :max-project-nonterminal-jobs 10
                    :max-user-nonterminal-jobs 1}
      :ttl {:asset-seconds 86400
            :job-metadata-seconds 86400
            :playback-authority-seconds 3600
            :cache-minimum-remaining-seconds 3600}
      :cost {:attempt-reservation-minor-units 125
             :max-user-attempts-per-utc-day 5
             :max-user-monthly-minor-units 2500
             :max-derivative-monthly-minor-units 10000
             :max-project-monthly-minor-units 40000}}}
    derivative/contract-v1)))

(deftest direct-sources-never-submit-derivative-work
  (is (= {:classification :direct-passthrough
          :submit-derivative? false}
         (derivative/classify-source
          {:analysis-status :available
           :browser-support :supported
           :renderable? true})))
  (is (= {:classification :derivative-required
          :submit-derivative? true}
         (derivative/classify-source
          {:analysis-status :available
           :browser-support :rejected
           :renderable? true}))))

(deftest every-work-limit-accepts-the-boundary-and-rejects-one-over
  (let [at-limit {:source-duration-seconds 480
                  :source-bytes 2147483648
                  :upstream-bytes 2415919104
                  :request-count 320
                  :range-bytes 8388608
                  :output-bytes 268435456}]
    (is (= at-limit (derivative/validate-work! at-limit)))
    (doseq [[field failure-code]
            [[:source-duration-seconds "source_duration_exceeded"]
             [:source-bytes "source_size_exceeded"]
             [:upstream-bytes "upstream_transfer_exceeded"]
             [:request-count "range_request_limit_exceeded"]
             [:range-bytes "range_size_exceeded"]
             [:output-bytes "derivative_size_exceeded"]]]
      (let [error
            (try
              (derivative/validate-work! (update at-limit field inc))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= ::derivative/limit-exceeded (:type (ex-data error))))
        (is (= failure-code (:failure-code (ex-data error))))))))

(deftest preparation-admission-and-diagnostics-are-bounded-and-private
  (is (= {:file-id "picker-selected-drive-file"
          :idempotency-key "ui-preview-211"}
         (derivative/validate-submission!
          {:fileId "picker-selected-drive-file"}
          "ui-preview-211")))
  (doseq [[request key]
          [[{} "key"]
           [{:fileId ""} "key"]
           [{:fileId "file" :name "private.mov"} "key"]
           [{:fileId "file"} ""]
           [{:fileId "file"} (apply str (repeat 129 "x"))]]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (derivative/validate-submission! request key))))
  (let [request-id "00000000-0000-0000-0000-000000000211"
        diagnostics
        (derivative/safe-diagnostics
         {:failureCode "derivative_timeout"
          :requestId request-id
          :retryable true
          :attempt 2
          :fileId "private-file"
          :ownerSubject "private-owner"
          :filename "private.mov"})]
    (is (= {:failureCode "derivative_timeout"
            :requestId request-id
            :retryable true
            :attempt 2}
           diagnostics))
    (is (derivative/valid-request-id? request-id))
    (is (false? (derivative/valid-request-id? "private-owner")))))

(deftest openapi-publishes-the-production-private-preview-contract
  (let [openapi (slurp "docs/openapi.yaml")]
    (doseq [contract
            ["/v1/derivative-preparations:"
             "/v1/derivative-preparations/{preparationId}:"
             "/v1/derivative-preparations/{preparationId}/cancel:"
             "/v1/derivative-preparations/{preparationId}/retry:"
             "DerivativePreparation:"
             "enum: [queued, running, cancellation-requested, succeeded, failed, cancelled, expired, revoked]"
             "profileVersion: {const: h264-aac-1080p25-v1}"
             "fileId: {type: string, minLength: 1, maxLength: 256}"]]
      (is (str/includes? openapi contract) contract))
    (is (str/includes? openapi
                       "Google Drive delivery uses only the `drive.file` scope."))
    (is (not (str/includes? openapi "drive.readonly")))))

(deftest production-private-preview-observability-is-an-operator-contract
  (let [runbook (slurp "docs/production-runbook.md")
        acceptance (slurp "docs/release-acceptance.md")
        openapi (slurp "docs/openapi.yaml")
        context (slurp "CONTEXT.md")
        context-map (slurp "CONTEXT-MAP.md")
        adr (slurp "docs/adr/0022-isolate-production-private-video-previews.md")
        changelog (slurp "CHANGELOG.md")]
    (doseq [event ["derivative_preparation_submitted"
                   "derivative_preparation_dispatched"
                   "derivative_drive_ranges_completed"
                   "derivative_encode_exited"
                   "derivative_verification_succeeded"
                   "derivative_publication_succeeded"
                   "derivative_playback_range_served"
                   "derivative_cancellation_resolved"
                   "derivative_reconciliation_completed"
                   "derivative_preparation_terminal"]]
      (is (str/includes? runbook event) event))
    (doseq [contract
            ["jsonPayload.requestId=\"$REQUEST_ID\""
             "X-Request-Id"
             "original video is unchanged"
             "24 hours"
             "processing allowance"
             "private-preview metrics"
             "admin logs"]]
      (is (str/includes? (str runbook "\n" acceptance) contract) contract))
    (is (str/includes? openapi
                       "X-Request-Id:\n              $ref: \"#/components/headers/RequestId\""))
    (is (str/includes? openapi "RequestId:"))
    (is (str/includes? context "production private-preview lifecycle"))
    (is (str/includes? context-map "Private-preview observability"))
    (is (str/includes? adr "Safe correlated observability"))
    (doseq [copy ["private video preview"
                  "original Drive file is unchanged"
                  "expires after 24 hours"
                  "processing allowance"]]
      (is (str/includes? changelog copy) copy))))
