(ns agg.drive.limits)

(def ^:private source-safety-version "selected-range-safety-v1")

(def ^:private hard-safety-ceilings
  {:renderer
   {:max-upstream-bytes (* 16 1024 1024 1024)
    :max-request-count 4096
    :max-range-bytes (* 8 1024 1024)
    :max-concurrent-requests 2
    :request-timeout-ms 10000
    :max-retries 3
    :max-cache-bytes (* 8 1024 1024)
    :lifetime-ms (* 45 60 1000)}
   :preflight
   {:max-upstream-bytes (* 512 1024 1024)
    :max-request-count 64
    :max-range-bytes (* 8 1024 1024)
    :max-concurrent-requests 1
    :request-timeout-ms 3000
    :max-retries 1
    :max-cache-bytes (* 8 1024 1024)
    :lifetime-ms 30000}})

(def ^:private environment-contract
  {:renderer
   {:max-upstream-bytes "AGG_RENDERER_SOURCE_MAX_UPSTREAM_BYTES"
    :max-request-count "AGG_RENDERER_SOURCE_MAX_REQUESTS"
    :max-range-bytes "AGG_RENDERER_SOURCE_MAX_RANGE_BYTES"
    :max-concurrent-requests "AGG_RENDERER_SOURCE_MAX_CONCURRENCY"
    :request-timeout-ms "AGG_RENDERER_SOURCE_REQUEST_TIMEOUT_MS"
    :max-retries "AGG_RENDERER_SOURCE_MAX_RETRIES"
    :max-cache-bytes "AGG_RENDERER_SOURCE_MAX_CACHE_BYTES"
    :lifetime-ms "AGG_RENDERER_SOURCE_LIFETIME_MS"}
   :preflight
   {:max-upstream-bytes "AGG_PREFLIGHT_SOURCE_MAX_UPSTREAM_BYTES"
    :max-request-count "AGG_PREFLIGHT_SOURCE_MAX_REQUESTS"
    :max-range-bytes "AGG_PREFLIGHT_SOURCE_MAX_RANGE_BYTES"
    :max-concurrent-requests "AGG_PREFLIGHT_SOURCE_MAX_CONCURRENCY"
    :request-timeout-ms "AGG_PREFLIGHT_SOURCE_REQUEST_TIMEOUT_MS"
    :max-retries "AGG_PREFLIGHT_SOURCE_MAX_RETRIES"
    :max-cache-bytes "AGG_PREFLIGHT_SOURCE_MAX_CACHE_BYTES"
    :lifetime-ms "AGG_PREFLIGHT_SOURCE_LIFETIME_MS"}})

(defn- environment-ceiling [environment name]
  (let [value (some-> (get environment name) parse-long)]
    (when-not (and (integer? value) (not (neg? value)))
      (throw
       (IllegalArgumentException.
        (str "Missing or invalid selected-range safety ceiling " name))))
    value))

(defn limits-from-environment
  "Returns hard safety ceilings from the reviewed versioned runtime contract."
  [environment]
  (if-let [version (get environment "AGG_SOURCE_SAFETY_LIMITS_VERSION")]
    (do
      (when-not (= source-safety-version version)
        (throw
         (IllegalArgumentException.
          "Unsupported selected-range safety limits version")))
      (into {}
            (map
             (fn [[scope fields]]
               [scope
                (into {}
                      (map
                       (fn [[field name]]
                         [field (environment-ceiling environment name)]))
                      fields)]))
            environment-contract))
    hard-safety-ceilings))

(def ^:private configured-limits
  (limits-from-environment (System/getenv)))

(def renderer-range-limits-v1 (:renderer configured-limits))
(def preflight-range-limits-v1 (:preflight configured-limits))
