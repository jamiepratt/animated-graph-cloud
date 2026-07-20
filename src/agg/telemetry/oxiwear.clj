(ns agg.telemetry.oxiwear
  (:require [agg.errors :as errors]
            [clojure.string :as str])
  (:import (java.io BufferedReader StringReader)
           (java.time Instant)
           (java.util.regex Pattern)))

(def ^:private max-samples 900000)

(def heart-rate-expected-schema
  {:timestamp-columns ["reading_time"]
   :value-columns ["pulse_rate"]})

(def spo2-expected-schema
  {:timestamp-columns ["reading_time"]
   :value-columns ["spo2"]})

(defn- cell [value]
  (let [trimmed (-> value str/trim (str/replace-first "\ufeff" ""))]
    (if (and (>= (count trimmed) 2)
             (= \" (first trimmed))
             (= \" (last trimmed)))
      (subs trimmed 1 (dec (count trimmed)))
      trimmed)))

(defn- column-index [columns name]
  (some (fn [[index column]]
          (when (= name (str/lower-case (cell column))) index))
        (map-indexed vector columns)))

(defn- parse-value-csv
  [csv value-column output-key minimum maximum field out-of-range-type]
  (try
    (with-open [reader (BufferedReader. (StringReader. csv))]
      (let [header (.readLine reader)
            delimiter (if (and header (str/includes? header ";")) ";" ",")
            separator (re-pattern (Pattern/quote delimiter))
            columns (when header (str/split header separator -1))
            timestamp-index (column-index columns "reading_time")
            value-index (column-index columns value-column)]
        (when-not (and timestamp-index value-index)
          (throw (errors/raise! "Unsupported OxiWear CSV columns"
                                {:type ::unsupported-columns
                                 :field field})))
        (loop [samples (transient [])
               sample-count 0
               line-number 2]
          (if-let [row (.readLine reader)]
            (if (str/blank? row)
              (recur samples sample-count (inc line-number))
              (let [sample
                    (try
                      (when-not (< sample-count max-samples)
                        (throw
                         (errors/raise! "OxiWear CSV has too many samples"
                                        {:type ::too-many-samples
                                         :field field
                                         :limit max-samples})))
                      (let [values (mapv cell (str/split row separator -1))
                            timestamp-text (get values timestamp-index)
                            value-text (get values value-index)
                            timestamp (Instant/parse timestamp-text)
                            value (parse-double value-text)]
                        (when-not (and (not (str/blank? timestamp-text))
                                       (not (str/blank? value-text))
                                       (Double/isFinite value))
                          (throw (IllegalArgumentException.)))
                        (when-not (<= minimum value maximum)
                          (throw
                           (errors/raise! "OxiWear value is out of range"
                                          {:type out-of-range-type
                                           :field field
                                           :line line-number})))
                        {:timestamp timestamp
                         output-key value})
                      (catch clojure.lang.ExceptionInfo error
                        (throw error))
                      (catch Throwable cause
                        (throw
                         (errors/raise! "Malformed OxiWear CSV row"
                                        {:type ::malformed-row
                                         :field field
                                         :line line-number}
                                        cause))))]
                (recur (conj! samples sample)
                       (inc sample-count)
                       (inc line-number))))
            (persistent! samples)))))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Throwable cause
      (throw (errors/raise! "Malformed OxiWear CSV row"
                            {:type ::malformed-row
                             :field field}
                            cause)))))

(defn parse-heart-rate-csv
  "Parses OxiWear heart-rate CSV into offset-normalized instant samples."
  [csv]
  (parse-value-csv csv "pulse_rate" :heart-rate 20.0 260.0
                   "telemetry" ::heart-rate-out-of-range))

(defn parse-spo2-csv
  "Parses OxiWear oxygen-saturation CSV into offset-normalized samples."
  [csv]
  (parse-value-csv csv "spo2" :spo2 0.0 100.0
                   "spo2.telemetry" ::value-out-of-range))
