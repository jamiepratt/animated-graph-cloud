(ns agg.telemetry.garmin
  (:import (com.garmin.fit MesgBroadcaster RecordMesgListener)
           (java.io ByteArrayInputStream)
           (java.util Base64)))

(def ^:private max-fit-bytes (* 10 1024 1024))
(def ^:private max-samples 900000)
(def ^:private max-base64-characters
  (* 4 (quot (+ max-fit-bytes 2) 3)))

(defn parse-fit-base64
  "Decodes Garmin FIT record messages into timestamped heart-rate samples."
  [encoded]
  (try
    (when-not (and (string? encoded)
                   (<= (count encoded) max-base64-characters))
      (throw (ex-info "Garmin FIT input exceeds the size limit"
                      {:type ::fit-too-large
                       :limit max-fit-bytes})))
    (let [bytes (.decode (Base64/getDecoder) ^String encoded)
          samples (transient [])
          sample-count (volatile! 0)
          broadcaster (MesgBroadcaster.)]
      (when (> (alength bytes) max-fit-bytes)
        (throw (ex-info "Garmin FIT input exceeds the size limit"
                        {:type ::fit-too-large
                         :limit max-fit-bytes})))
      (.addListener
       broadcaster
       (reify RecordMesgListener
         (onMesg [_ record]
           (when (and (.getTimestamp record) (.getHeartRate record))
             (when-not (< @sample-count max-samples)
               (throw (ex-info "Garmin FIT has too many samples"
                               {:type ::too-many-samples
                                :limit max-samples})))
             (vswap! sample-count inc)
             (conj! samples
                    {:timestamp (.getInstant (.getTimestamp record))
                     :heart-rate (double (.getHeartRate record))})))))
      (with-open [input (ByteArrayInputStream. bytes)]
        (.run broadcaster input))
      (persistent! samples))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Throwable cause
      (throw (ex-info "Malformed Garmin FIT input"
                      {:type ::malformed-fit}
                      cause)))))
