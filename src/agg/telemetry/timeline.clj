(ns agg.telemetry.timeline
  (:import (java.time Duration Instant)))

(defn seconds-between [^Instant start ^Instant end]
  (/ (.toNanos (Duration/between start end)) 1000000000.0))

(defn heart-rate-at
  "Linearly interpolates heart rate at an instant covered by ordered samples."
  [samples ^Instant timestamp]
  (let [exact (some #(when (= timestamp (:timestamp %)) %) samples)]
    (if exact
      (:heart-rate exact)
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
          (+ (:heart-rate left)
             (* ratio (- (:heart-rate right) (:heart-rate left)))))))))

(defn heart-rate-at-seconds
  "Linearly interpolates a normalized section timeline."
  [samples seconds]
  (let [exact (some #(when (= seconds (:seconds %)) %) samples)]
    (if exact
      (:heart-rate exact)
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
          (+ (:heart-rate left)
             (* ratio (- (:heart-rate right) (:heart-rate left)))))))))

(defn section
  "Returns section-relative telemetry with interpolated boundary samples."
  [samples ^Instant start ^Instant end]
  (let [inside (filterv #(and (.isAfter ^Instant (:timestamp %) start)
                              (.isBefore ^Instant (:timestamp %) end))
                        samples)]
    (mapv (fn [{:keys [timestamp heart-rate]}]
            {:seconds (seconds-between start timestamp)
             :heart-rate heart-rate})
          (into [{:timestamp start :heart-rate (heart-rate-at samples start)}]
                (conj inside
                      {:timestamp end :heart-rate (heart-rate-at samples end)})))))
