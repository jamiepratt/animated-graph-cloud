(ns agg.observability
  (:require [clojure.data.json :as json]
            [taoensso.telemere :as tel]
            [taoensso.tufte :as tufte]
            [taoensso.tufte.telemere :as tufte-telemere]))

(def ^:private safe-event-keys
  #{:severity :component :event :message :reason :queueAgeMs :repairedJobs
    :releasedLeases :targetMemberId :tokensRevoked :credentialsDeleted
    :jobsCancelled :cleanupErrors :components :failureCode :errorType :port
    :requestId :category :status :phase :view :listState :tokenStatus
    :accountStatus :mimeFilter :indexStatus :stage :elapsedMs :timeoutMs
    :retryable})

(def ^:private safe-value-keys
  #{:severity :component :event :message :reason :failureCode :errorType
    :requestId :category :phase :view :listState :tokenStatus :accountStatus
    :mimeFilter :indexStatus :stage})

(def ^:private safe-stages
  #{"source_metadata" "source_content" "frame_compose"})

(defn- safe-string? [value]
  (and (string? value)
       (<= (count value) 128)
       (re-matches #"[A-Za-z0-9_.:/-]+" value)))

(defn- safe-message? [value]
  (and (string? value)
       (<= (count value) 256)
       (not (re-find #"(?i)telemetry|token|credential|signed.?url|https?://"
                     value))))

(defn- safe-event-value? [key value]
  (cond
    (= :message key) (safe-message? value)
    (= :stage key) (contains? safe-stages value)
    (contains? safe-value-keys key) (safe-string? value)
    (contains? #{:cleanupErrors :components} key)
    (and (vector? value) (<= (count value) 16) (every? safe-string? value))
    (number? value) (<= (Math/abs (double value)) 1000000000000)
    (boolean? value) true
    :else false))

(defn safe-event-fields [fields]
  (into {}
        (keep (fn [[key value]]
                (when (and (contains? safe-event-keys key)
                           (safe-event-value? key value))
                  [key value])))
        fields))

(defn- severity [level]
  (case level
    :trace "DEBUG"
    :debug "DEBUG"
    :warn "WARNING"
    :error "ERROR"
    :fatal "ERROR"
    "INFO"))

(defn- telemere-level [value]
  (case value
    "DEBUG" :debug
    "WARNING" :warn
    "ERROR" :error
    "INFO" :info
    :info))

(defn- signal-fields [{:keys [data level msg_] :as signal}]
  (let [fields (if (contains? signal :tufte/pstats)
                 {:severity (severity level)
                  :component "renderer"
                  :event "render_timings"}
                 (safe-event-fields data))
        message (when msg_ (force msg_))]
    (cond-> (merge {:severity (severity level)} fields)
      (and message
           (safe-message? message)
           (not (contains? fields :message)))
      (assoc :message message))))

(defonce ^:private persistence-sink (atom nil))

(defn configure-persistence!
  "Configures an optional best-effort sink for the safe console event record."
  [sink]
  (reset! persistence-sink sink)
  true)

(defn- persist-event! [fields raw]
  (when-let [sink @persistence-sink]
    (try
      (sink {:fields fields :raw raw})
      (catch Throwable _))))

(defn- console-handler
  ([] nil)
  ([signal]
   (when (and (not= :trace (:kind signal))
              (not (contains? signal :tufte/pstats)))
     (let [fields (signal-fields signal)
           raw (json/write-str fields)]
       (println raw)
       (persist-event! fields raw)))))

(defn configure! []
  (tel/remove-handler! :default/console)
  (tel/add-handler! ::console console-handler {:async nil})
  (tufte/add-handler! ::telemere (tufte-telemere/handler:telemere)
                      {:async nil})
  true)

(configure!)

(defn emit-event!
  "Emits one bounded structured event through Telemere."
  ([component event fields]
   (let [fields (safe-event-fields
                 (merge {:component component
                         :event event}
                        fields))]
     (tel/log! {:level (telemere-level (:severity fields))
                :id (keyword (str component "/" event))
                :data fields})))
  ([event-record]
   (emit-event! (:component event-record)
                (:event event-record)
                event-record)))

(defn emit-error!
  "Emits one boundary error event without attaching exception data or causes."
  [component event error fields]
  (emit-event! component event
               (merge {:severity "ERROR"
                       :errorType (some-> error ex-data :type str)}
                      fields)))

(defmacro trace!
  "Adds a low-cardinality Telemere trace around a boundary operation."
  [id & body]
  `(try
     (tel/trace! {:id ~id} ~@body)
     (catch Throwable error#
       (if-let [original# (get-in (ex-data error#)
                                  [:taoensso.telemere/signal :error])]
         (throw original#)
         (throw error#)))))
