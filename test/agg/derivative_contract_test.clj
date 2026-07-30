(ns agg.derivative-contract-test
  (:require [agg.derivative.contract :as derivative]
            [agg.drive.derivative :as drive-derivative]
            [agg.render.derivative :as render-derivative]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest approved-derivative-profile-and-envelope-have-one-versioned-contract
  (is (= {:version "derivative-preview-contract-v1"
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

(deftest render-domain-owns-the-published-derivative-profile
  (is (= render-derivative/profile-v1
         (:profile derivative/contract-v1))))

(deftest derivative-work-boundaries-accept-the-limit-and-reject-one-over
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
      (let [error (try
                    (derivative/validate-work! (update at-limit field inc))
                    nil
                    (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= ::derivative/limit-exceeded
               (:type (ex-data error))))
        (is (= failure-code (:failure-code (ex-data error))))
        (is (= (get at-limit field) (:limit (ex-data error))))
        (is (= (inc (get at-limit field)) (:reported (ex-data error))))))))

(deftest normalized-playback-evidence-has-four-stable-classifications
  (let [h264 {:container {:format "mp4"}
              :video {:codec "h264" :codecTag "avc1"}
              :audio {:codec "aac"}}
        hevc {:container {:format "mov"}
              :video {:codec "hevc" :codecTag "hvc1"}
              :audio {:codec "aac"}}]
    (is (= {:classification :direct-passthrough}
           (drive-derivative/classify
            {:analysis-status :available
             :browser-support :supported
             :renderable? true
             :evidence h264})))
    (is (= {:classification :derivative-required}
           (drive-derivative/classify
            {:analysis-status :available
             :browser-support :rejected
             :renderable? true
             :evidence hevc})))
    (is (= {:classification :unavailable
            :error-code "playback_evidence_unavailable"
            :retryable true}
           (drive-derivative/classify
            {:analysis-status :unavailable
             :browser-support :unknown})))
    (is (= {:classification :terminal-failure
            :error-code "derivative_source_not_renderable"
            :retryable false}
           (drive-derivative/classify
            {:analysis-status :available
             :browser-support :rejected
             :renderable? false
             :evidence hevc})))
    (is (= {:classification :terminal-failure
            :error-code "playback_analysis_failed"
            :retryable false}
           (drive-derivative/classify
            {:analysis-status :failed
             :browser-support :unknown})))))

(deftest cache-fingerprints-are-opaque-scoped-and-disable-unsafe-reuse
  (let [secret "fixture-hmac-key"
        source {:owner-subject "private-owner"
                :drive-file-id "private-drive-id"
                :drive-version "immutable-version"
                :source-bytes 4096
                :profile-version "h264-aac-1080p25-v1"
                :job-id "job-one"}
        reusable (drive-derivative/cache-fingerprint secret source)
        changed (fn [field value]
                  (:fingerprint
                   (drive-derivative/cache-fingerprint
                    secret (assoc source field value))))
        current-job
        (drive-derivative/cache-fingerprint
         secret (dissoc source :drive-version))]
    (is (= reusable
           (drive-derivative/cache-fingerprint secret source)))
    (is (= "derivative-cache-fingerprint-v1" (:version reusable)))
    (is (= :cross-job (:reuse-scope reusable)))
    (is (re-matches #"[0-9a-f]{64}" (:fingerprint reusable)))
    (doseq [[field replacement]
            [[:owner-subject "other-owner"]
             [:drive-file-id "other-drive-id"]
             [:drive-version "other-version"]
             [:source-bytes 4097]
             [:profile-version "other-profile"]]]
      (is (not= (:fingerprint reusable) (changed field replacement))))
    (is (= :current-job-only (:reuse-scope current-job)))
    (is (= current-job
           (drive-derivative/cache-fingerprint
            secret (dissoc source :drive-version))))
    (is (not= (:fingerprint current-job)
              (:fingerprint
               (drive-derivative/cache-fingerprint
                secret (-> source
                           (dissoc :drive-version)
                           (assoc :job-id "job-two"))))))
    (is (not-any? #(str/includes? (pr-str reusable) %)
                  ["private-owner" "private-drive-id" "immutable-version"
                   "fixture-hmac-key"]))
    (let [error (try
                  (drive-derivative/cache-fingerprint
                   secret (dissoc source :drive-version :job-id))
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= ::drive-derivative/invalid-cache-evidence
             (:type (ex-data error)))))))
