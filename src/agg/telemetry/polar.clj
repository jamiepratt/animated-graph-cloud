(ns agg.telemetry.polar
  (:require [clojure.string :as str])
  (:import (java.time Instant)
           (java.util.regex Pattern)))

(def ^:private timestamp-columns
  #{"timestamp" "date/time" "datetime"})

(def ^:private heart-rate-columns
  #{"heart_rate" "heart rate" "heart rate (bpm)" "hr" "hr (bpm)"})

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
  "Parses Polar heart-rate CSV into timestamped samples."
  [csv]
  (let [[header & rows] (str/split-lines csv)
        delimiter (if (str/includes? header ";") ";" ",")
        separator (re-pattern (Pattern/quote delimiter))
        columns (str/split header separator -1)
        timestamp-index (column-index columns timestamp-columns)
        heart-rate-index (column-index columns heart-rate-columns)]
    (when-not (and timestamp-index heart-rate-index)
      (throw (ex-info "Unsupported Polar CSV columns"
                      {:type ::unsupported-columns})))
    (mapv (fn [row]
            (try
              (let [values (mapv cell (str/split row separator -1))
                    timestamp (get values timestamp-index)
                    heart-rate (get values heart-rate-index)
                    parsed-heart-rate (parse-double heart-rate)]
                (when-not (and (not (str/blank? timestamp))
                               (not (str/blank? heart-rate))
                               parsed-heart-rate
                               (Double/isFinite parsed-heart-rate)
                               (<= 20.0 parsed-heart-rate 260.0))
                  (throw (IllegalArgumentException.)))
                {:timestamp (Instant/parse timestamp)
                 :heart-rate parsed-heart-rate})
              (catch Throwable cause
                (throw (ex-info "Malformed Polar CSV row"
                                {:type ::malformed-row}
                                cause)))))
          (remove str/blank? rows))))
