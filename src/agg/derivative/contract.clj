(ns agg.derivative.contract
  (:require [agg.errors :as errors]
            [clojure.string :as str])
  (:import (java.util UUID)))

(def ^:private supported-environments #{:production :proto})

(def ^:private profile-v1
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
             :size :stream-layout}})

(def contract-v1
  {:version "production-private-preview-contract-v1"
   :environment "production"
   :profile profile-v1
   :limits
   {:source {:max-duration-seconds 480
             :max-bytes (* 2 1024 1024 1024)}
    :transfer {:max-upstream-bytes (* 9 256 1024 1024)
               :max-request-count 320
               :max-range-bytes (* 8 1024 1024)}
    :output {:max-bytes (* 256 1024 1024)}
    :compute {:cpu 4
              :memory-bytes (* 4 1024 1024 1024)
              :timeout-seconds 900
              :task-count 1
              :automatic-retries 0}
    :concurrency {:queue-max-concurrent-dispatches 1
                  :queue-max-dispatches-per-second 1
                  :max-project-nonterminal-jobs 10
                  :max-user-nonterminal-jobs 1}
    :ttl {:asset-seconds (* 24 60 60)
          :job-metadata-seconds (* 24 60 60)
          :playback-authority-seconds (* 60 60)
          :cache-minimum-remaining-seconds (* 60 60)}
    :cost {:attempt-reservation-minor-units 125
           :max-user-attempts-per-utc-day 5
           :max-user-monthly-minor-units 2500
           :max-derivative-monthly-minor-units 10000
           :max-project-monthly-minor-units 40000}}})

(def public-error-codes-v1
  #{"derivative_failed"
    "derivative_encode_failed"
    "derivative_verification_failed"
    "derivative_transfer_failed"
    "derivative_timeout"
    "derivative_cancel_failed"
    "derivative_source_not_renderable"
    "playback_analysis_failed"
    "playback_evidence_unavailable"
    "source_duration_exceeded"
    "source_size_exceeded"
    "upstream_transfer_exceeded"
    "range_request_limit_exceeded"
    "range_size_exceeded"
    "derivative_size_exceeded"
    "invalid_derivative_measurement"
    "invalid_derivative_transition"
    "derivative_project_backlog_exhausted"
    "derivative_user_job_active"
    "derivative_daily_attempt_limit_exhausted"
    "derivative_user_budget_exhausted"
    "derivative_pool_budget_exhausted"
    "membership_revoked"})

(def ^:private work-limits
  [[:source-duration-seconds
    (get-in contract-v1 [:limits :source :max-duration-seconds])
    "source_duration_exceeded"]
   [:source-bytes
    (get-in contract-v1 [:limits :source :max-bytes])
    "source_size_exceeded"]
   [:upstream-bytes
    (get-in contract-v1 [:limits :transfer :max-upstream-bytes])
    "upstream_transfer_exceeded"]
   [:request-count
    (get-in contract-v1 [:limits :transfer :max-request-count])
    "range_request_limit_exceeded"]
   [:range-bytes
    (get-in contract-v1 [:limits :transfer :max-range-bytes])
    "range_size_exceeded"]
   [:output-bytes
    (get-in contract-v1 [:limits :output :max-bytes])
    "derivative_size_exceeded"]])

(defn validate-work!
  "Returns bounded derivative measurements or raises a stable failure."
  [measurements]
  (doseq [[field limit failure-code] work-limits
          :let [reported (get measurements field)]
          :when (some? reported)]
    (when-not (and (number? reported) (not (neg? reported)))
      (throw
       (errors/raise! "Private-preview measurement is invalid"
                      {:type ::invalid-measurement
                       :failure-code "invalid_derivative_measurement"})))
    (when (> reported limit)
      (throw
       (errors/raise! "Private-preview work exceeds its approved limit"
                      {:type ::limit-exceeded
                       :failure-code failure-code
                       :limit limit
                       :reported reported}))))
  measurements)

(defn classify-source
  "Chooses direct private passthrough or explicit preparation."
  [{:keys [analysis-status browser-support renderable?]}]
  (case analysis-status
    :failed
    {:classification :terminal-failure
     :submit-derivative? false
     :error-code "playback_analysis_failed"
     :retryable false}

    :unavailable
    {:classification :unavailable
     :submit-derivative? false
     :error-code "playback_evidence_unavailable"
     :retryable true}

    :available
    (cond
      (= :supported browser-support)
      {:classification :direct-passthrough
       :submit-derivative? false}

      (and (= :rejected browser-support) renderable?)
      {:classification :derivative-required
       :submit-derivative? true}

      (= :rejected browser-support)
      {:classification :terminal-failure
       :submit-derivative? false
       :error-code "derivative_source_not_renderable"
       :retryable false}

      :else
      {:classification :unavailable
       :submit-derivative? false
       :error-code "playback_evidence_unavailable"
       :retryable true})

    {:classification :unavailable
     :submit-derivative? false
     :error-code "playback_evidence_unavailable"
     :retryable true}))

(defn valid-request-id? [value]
  (try
    (and (string? value) (UUID/fromString value))
    (catch IllegalArgumentException _
      false)))

(defn validate-submission!
  "Validates the transport-only preparation request and idempotency key."
  [request idempotency-key]
  (let [file-id (:fileId request)]
    (when-not (and (= #{:fileId} (set (keys request)))
                   (string? file-id)
                   (not (str/blank? file-id))
                   (<= (count file-id) 256))
      (throw
       (errors/raise! "Private-preview source is invalid"
                      {:type ::invalid-source-request})))
    (when-not (and (string? idempotency-key)
                   (not (str/blank? idempotency-key))
                   (<= (count idempotency-key) 128))
      (throw
       (errors/raise! "A bounded Idempotency-Key header is required"
                      {:type ::invalid-idempotency-key})))
    {:file-id file-id
     :idempotency-key idempotency-key}))

(defn safe-diagnostics
  "Returns only allowlisted, bounded support fields."
  [{:keys [failureCode requestId retryable attempt]}]
  (cond-> {}
    (contains? public-error-codes-v1 failureCode)
    (assoc :failureCode failureCode)

    (valid-request-id? requestId)
    (assoc :requestId requestId)

    (boolean? retryable)
    (assoc :retryable retryable)

    (and (integer? attempt) (<= 1 attempt 1000))
    (assoc :attempt attempt)))

(defn environment-contract
  "Returns every environment-scoped persistence and object boundary."
  [environment]
  (when-not (contains? supported-environments environment)
    (throw
     (errors/raise! "Private-preview environment is invalid"
                    {:type ::invalid-environment})))
  (let [prefix (name environment)]
    {:environment prefix
     :namespaces
     {:jobs (str prefix "-derivative-preparation-jobs-v1")
      :cache (str prefix "-derivative-preview-cache-v1")
      :idempotency (str prefix "-derivative-preparation-idempotency-v1")
      :active-jobs (str prefix "-derivative-active-jobs-v1")
      :objects (str prefix "/derivative-previews/v1")}}))
