(ns agg.errors)

(def ^:private max-diagnostic-number 1000000000000)
(def ^:private max-diagnostic-string-length 128)
(def ^:private safe-stages
  #{"source_metadata" "frame_compose"
    "request_load" "request_prepare" "source_content" "overlay_render"
    "composition_encode" "artifact_upload" "drive_delivery"
    "completion_persistence"})
(def ^:private safe-context-keys
  #{:status :offset :size :reported :sent-through :line :limit :limits
    :retryable :job-id :field :component :exit-status :failure-code
    :state :components :reason :stage :attempt :elapsed-ms :timeout-ms})

(defn- finite-number? [value]
  (and (number? value)
       (<= (Math/abs (double value)) max-diagnostic-number)
       (or (not (double? value))
           (Double/isFinite (double value)))))

(defn- safe-string? [value]
  (and (string? value)
       (<= (count value) max-diagnostic-string-length)
       (re-matches #"[A-Za-z0-9_.:/-]+" value)))

(defn- safe-limits? [value]
  (and (map? value)
       (<= (count value) 16)
       (every? (fn [[key value]]
                 (and (keyword? key) (finite-number? value)))
               value)))

(defn- safe-context-value? [key value]
  (case key
    :limits (safe-limits? value)
    (:job-id :field :component :failure-code) (safe-string? value)
    :stage (contains? safe-stages value)
    :reason (safe-string? value)
    :state (keyword? value)
    :components (and (vector? value)
                     (<= (count value) 16)
                     (every? safe-string? value))
    :retryable (boolean? value)
    (finite-number? value)))

(defn- namespaced-keyword? [value]
  (and (keyword? value) (some? (namespace value))))

(defn- require-type! [value]
  (when-not (namespaced-keyword? value)
    (throw (IllegalArgumentException.
            "Application errors require a namespaced keyword :type")))
  value)

(defn- diagnostic-data [data source]
  (let [data (or data {})
        type (require-type! (:type data))]
    (merge {:type type
            :source (select-keys source [:file :line :column])}
           (into {}
                 (keep (fn [[key value]]
                         (when (and (contains? safe-context-keys key)
                                    (safe-context-value? key value))
                           [key value])))
                 data))))

(defn raise*! [message data cause source]
  (throw (ex-info message (diagnostic-data data source) cause)))

(defmacro raise!
  "Raises an application-owned ExceptionInfo with safe, source-aware data.

  The optional cause is attached unchanged. This macro deliberately emits no
  telemetry; API, job, and worker boundaries own logging decisions."
  ([message data]
   (let [{:keys [file line column]} (meta &form)
         file (or file *file*)]
     `(raise*! ~message ~data nil
               {:file ~file :line ~line :column ~column})))
  ([message data cause]
   (let [{:keys [file line column]} (meta &form)
         file (or file *file*)]
     `(raise*! ~message ~data ~cause
               {:file ~file :line ~line :column ~column}))))
