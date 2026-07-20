(ns agg.telemetry.polar
  (:require [agg.errors :as errors]
            [clojure.string :as str])
  (:import (java.io BufferedReader StringReader)
           (java.time Instant)
           (java.util.regex Pattern)))

(def ^:private max-samples 900000)

(def expected-schema
  {:timestamp-columns ["timestamp" "date/time" "datetime"]
   :value-columns ["heart_rate" "heart rate" "heart rate (bpm)"
                   "HR" "HR (bpm)"]})

(def ^:private timestamp-columns
  (set (map str/lower-case (:timestamp-columns expected-schema))))

(def ^:private heart-rate-columns
  (set (map str/lower-case (:value-columns expected-schema))))

(defn- cell [value]
  (let [trimmed (-> value str/trim (str/replace-first "\ufeff" ""))]
    (if (and (>= (count trimmed) 2)
             (= \" (first trimmed))
             (= \" (last trimmed)))
      (subs trimmed 1 (dec (count trimmed)))
      trimmed)))

(defn- column-index [columns accepted]
  (some (fn [[index column]]
          (when (accepted (str/lower-case (cell column))) index))
        (map-indexed vector columns)))

(defn parse-csv
  "Parses Polar heart-rate CSV into timestamped samples. Sensor-gap zeros may
  be ignored strictly outside optional required bounds."
  ([csv]
   (parse-csv csv nil))
  ([csv {:keys [required-start required-end]}]
   (with-open [reader (BufferedReader. (StringReader. csv))]
     (let [header (.readLine reader)
           delimiter (if (and header (str/includes? header ";")) ";" ",")
           separator (re-pattern (Pattern/quote delimiter))
           columns (when header (str/split header separator -1))
           timestamp-index (column-index columns timestamp-columns)
           heart-rate-index (column-index columns heart-rate-columns)]
       (when-not (and timestamp-index heart-rate-index)
         (throw (errors/raise! "Unsupported Polar CSV columns"
                               {:type ::unsupported-columns
                                :field "telemetry"})))
       (loop [samples (transient [])
              sample-count 0
              line-number 2]
         (if-let [row (.readLine reader)]
           (if (str/blank? row)
             (recur samples sample-count (inc line-number))
             (let [sample
                   (try
                     (let [values (mapv cell (str/split row separator -1))
                           timestamp-text (get values timestamp-index)
                           heart-rate-text (get values heart-rate-index)
                           timestamp (Instant/parse timestamp-text)
                           heart-rate (parse-double heart-rate-text)
                           outside-required-window?
                           (and required-start
                                required-end
                                (or (.isBefore timestamp required-start)
                                    (.isAfter timestamp required-end)))]
                       (when-not (< sample-count max-samples)
                         (throw
                          (errors/raise! "Polar CSV has too many samples"
                                         {:type ::too-many-samples
                                          :field "telemetry"
                                          :limit max-samples})))
                       (if (and outside-required-window?
                                (zero? heart-rate))
                         {:timestamp timestamp
                          ::sensor-gap true}
                         (do
                           (when-not (and (not (str/blank? timestamp-text))
                                          (not (str/blank? heart-rate-text))
                                          (Double/isFinite heart-rate))
                             (throw (IllegalArgumentException.)))
                           (when-not (<= 20.0 heart-rate 260.0)
                             (throw
                              (errors/raise! "Polar heart rate is out of range"
                                             {:type ::heart-rate-out-of-range
                                              :field "telemetry"
                                              :line line-number})))
                           {:timestamp timestamp
                            :heart-rate heart-rate})))
                     (catch clojure.lang.ExceptionInfo error
                       (throw error))
                     (catch Throwable cause
                       (throw (errors/raise! "Malformed Polar CSV row"
                                             {:type ::malformed-row
                                              :field "telemetry"
                                              :line line-number}
                                             cause))))]
               (recur (conj! samples sample)
                      (inc sample-count)
                      (inc line-number))))
           (persistent! samples)))))))
