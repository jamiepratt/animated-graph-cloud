(ns agg.telemetry.oxiwear
  (:require [clojure.string :as str])
  (:import (java.io BufferedReader StringReader)
           (java.time Instant)
           (java.util.regex Pattern)))

(def ^:private max-samples 900000)

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

(defn- parse-value-csv [csv value-column output-key minimum maximum]
  (try
    (with-open [reader (BufferedReader. (StringReader. csv))]
      (let [header (.readLine reader)
            delimiter (if (and header (str/includes? header ";")) ";" ",")
            separator (re-pattern (Pattern/quote delimiter))
            columns (when header (str/split header separator -1))
            timestamp-index (column-index columns "reading_time")
            value-index (column-index columns value-column)]
        (when-not (and timestamp-index value-index)
          (throw (ex-info "Unsupported OxiWear CSV columns"
                          {:type ::unsupported-columns})))
        (loop [samples (transient [])
               sample-count 0
               line-number 2]
          (if-let [row (.readLine reader)]
            (if (str/blank? row)
              (recur samples sample-count (inc line-number))
              (let [values (mapv cell (str/split row separator -1))
                    timestamp (get values timestamp-index)
                    value (some-> (get values value-index) parse-double)]
                (when-not (and timestamp value
                               (Double/isFinite value)
                               (<= minimum value maximum)
                               (< sample-count max-samples))
                  (throw (ex-info "Malformed OxiWear CSV row"
                                  {:type ::malformed-row
                                   :line line-number})))
                (recur (conj! samples
                              {:timestamp (Instant/parse timestamp)
                               output-key value})
                       (inc sample-count)
                       (inc line-number))))
            (persistent! samples)))))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Throwable cause
      (throw (ex-info "Malformed OxiWear CSV row"
                      {:type ::malformed-row}
                      cause)))))

(defn parse-heart-rate-csv
  "Parses OxiWear heart-rate CSV into offset-normalized instant samples."
  [csv]
  (parse-value-csv csv "pulse_rate" :heart-rate 20.0 260.0))

(defn parse-spo2-csv
  "Parses OxiWear oxygen-saturation CSV into offset-normalized samples."
  [csv]
  (parse-value-csv csv "spo2" :spo2 0.0 100.0))
