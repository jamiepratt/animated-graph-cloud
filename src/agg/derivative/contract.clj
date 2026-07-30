(ns agg.derivative.contract
  (:require [agg.errors :as errors]
            [agg.render.derivative :as render-derivative]))

(def contract-v1
  {:version "derivative-preview-contract-v1"
   :profile render-derivative/profile-v1
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
  "Returns bounded derivative measurements or raises a stable public failure."
  [measurements]
  (doseq [[field limit failure-code] work-limits
          :let [reported (get measurements field)]
          :when (some? reported)]
    (when-not (and (number? reported) (not (neg? reported)))
      (throw
       (errors/raise! "Derivative measurement is invalid"
                      {:type ::invalid-measurement
                       :failure-code "invalid_derivative_measurement"})))
    (when (> reported limit)
      (throw
       (errors/raise! "Derivative work exceeds its approved limit"
                      {:type ::limit-exceeded
                       :failure-code failure-code
                       :limit limit
                       :reported reported}))))
  measurements)
