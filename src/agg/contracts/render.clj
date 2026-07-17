(ns agg.contracts.render
  (:require [agg.render.spec :as spec]
            [agg.telemetry.polar :as polar]
            [agg.telemetry.timeline :as timeline])
  (:import (java.nio.charset StandardCharsets)
           (java.time Duration Instant)))

(def max-telemetry-bytes (* 10 1024 1024))

(defn- instant [value field]
  (try
    (Instant/parse value)
    (catch Throwable cause
      (throw (ex-info (str field " must be an ISO-8601 instant")
                      {:type ::invalid-timestamp :field field}
                      cause)))))

(defn- require! [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn prepare
  "Validates a Polar render request and returns its shared render contract."
  [{:keys [telemetryFormat telemetry preset telemetrySyncAt cameraSyncAt
           sectionStartAt sectionEndAt]}]
  (require! (= "polar-csv" telemetryFormat)
            "Unsupported telemetry format"
            {:type ::unsupported-format})
  (require! (string? telemetry)
            "Telemetry must be CSV text"
            {:type ::invalid-telemetry})
  (require! (<= (alength (.getBytes ^String telemetry StandardCharsets/UTF_8))
                max-telemetry-bytes)
            "Telemetry exceeds the size limit"
            {:type ::telemetry-too-large :limit max-telemetry-bytes})
  (let [render-preset (spec/preset preset)
        telemetry-sync (instant telemetrySyncAt :telemetrySyncAt)
        camera-sync (instant cameraSyncAt :cameraSyncAt)
        section-start (instant sectionStartAt :sectionStartAt)
        section-end (instant sectionEndAt :sectionEndAt)
        duration-nanos (.toNanos (Duration/between section-start section-end))
        duration-seconds (/ duration-nanos 1000000000)
        telemetry-start (.plusNanos telemetry-sync
                                    (.toNanos (Duration/between camera-sync
                                                                section-start)))
        telemetry-end (.plusNanos telemetry-sync
                                  (.toNanos (Duration/between camera-sync
                                                              section-end)))
        samples (polar/parse-csv telemetry)]
    (require! (and (not (.isAfter camera-sync section-start))
                   (.isBefore section-start section-end))
              "Timestamps must be ordered cameraSyncAt <= sectionStartAt < sectionEndAt"
              {:type ::invalid-timestamp-order})
    (require! (zero? (rem duration-nanos 1000000000))
              "Section duration must be a whole number of seconds"
              {:type ::fractional-duration})
    (require! (<= duration-seconds (:duration-seconds render-preset))
              "Section duration exceeds the preset maximum"
              {:type ::duration-too-long})
    (require! (>= (count samples) 2)
              "Telemetry must contain at least two samples"
              {:type ::insufficient-telemetry})
    (require! (every? (fn [[left right]]
                        (.isBefore ^Instant (:timestamp left)
                                   ^Instant (:timestamp right)))
                      (partition 2 1 samples))
              "Telemetry timestamps must be strictly increasing"
              {:type ::unordered-telemetry})
    (require! (and (not (.isAfter ^Instant (:timestamp (first samples))
                                  telemetry-start))
                   (not (.isBefore ^Instant (:timestamp (last samples))
                                   telemetry-end)))
              "Telemetry does not cover the requested section"
              {:type ::insufficient-coverage})
    (assoc (spec/with-duration render-preset duration-seconds)
           :telemetry (timeline/section samples telemetry-start telemetry-end))))
