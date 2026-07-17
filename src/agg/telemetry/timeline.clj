(ns agg.telemetry.timeline
  (:import (java.time Duration Instant)))

(defn seconds-between [^Instant start ^Instant end]
  (/ (.toNanos (Duration/between start end)) 1000000000.0))

(defn value-at
  "Linearly interpolates a numeric field at a covered instant."
  [samples value-key ^Instant timestamp]
  (let [exact (some #(when (= timestamp (:timestamp %)) %) samples)]
    (if exact
      (get exact value-key)
      (let [[left right]
            (some (fn [[left right]]
                    (when (and (.isBefore ^Instant (:timestamp left) timestamp)
                               (.isAfter ^Instant (:timestamp right) timestamp))
                      [left right]))
                  (partition 2 1 samples))]
        (when-not (and left right)
          (throw (ex-info "Timestamp is outside telemetry coverage"
                          {:type ::outside-coverage})))
        (let [span (seconds-between (:timestamp left) (:timestamp right))
              offset (seconds-between (:timestamp left) timestamp)
              ratio (/ offset span)]
          (+ (get left value-key)
             (* ratio (- (get right value-key) (get left value-key)))))))))

(defn heart-rate-at
  "Linearly interpolates heart rate at an instant covered by ordered samples."
  [samples timestamp]
  (value-at samples :heart-rate timestamp))

(defn value-at-seconds
  "Linearly interpolates a numeric field on a normalized section timeline."
  [samples value-key seconds]
  (let [exact (some #(when (= seconds (:seconds %)) %) samples)]
    (if exact
      (get exact value-key)
      (let [[left right]
            (some (fn [[left right]]
                    (when (< (:seconds left) seconds (:seconds right))
                      [left right]))
                  (partition 2 1 samples))]
        (when-not (and left right)
          (throw (ex-info "Second is outside telemetry coverage"
                          {:type ::outside-coverage})))
        (let [span (- (:seconds right) (:seconds left))
              ratio (/ (- seconds (:seconds left)) span)]
          (+ (get left value-key)
             (* ratio (- (get right value-key) (get left value-key)))))))))

(defn heart-rate-at-seconds
  "Linearly interpolates heart rate on a normalized section timeline."
  [samples seconds]
  (value-at-seconds samples :heart-rate seconds))

(defn section-values
  "Returns one section-relative numeric series with boundary interpolation."
  [samples value-key ^Instant start ^Instant end]
  (let [inside (filterv #(and (.isAfter ^Instant (:timestamp %) start)
                              (.isBefore ^Instant (:timestamp %) end))
                        samples)]
    (mapv (fn [{:keys [timestamp] :as sample}]
            {:seconds (seconds-between start timestamp)
             value-key (get sample value-key)})
          (into [{:timestamp start
                  value-key (value-at samples value-key start)}]
                (conj inside
                      {:timestamp end
                       value-key (value-at samples value-key end)})))))

(defn section
  "Returns section-relative heart rate with interpolated boundary samples."
  [samples start end]
  (section-values samples :heart-rate start end))
