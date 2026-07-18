(ns agg.contracts.render
  (:require [agg.errors :as errors]
            [agg.render.spec :as spec]
            [agg.render.watermark :as watermark]
            [agg.telemetry.garmin :as garmin]
            [agg.telemetry.oxiwear :as oxiwear]
            [agg.telemetry.polar :as polar]
            [agg.telemetry.timeline :as timeline])
  (:import (java.nio.charset StandardCharsets)
           (java.time Duration Instant)))

(def max-telemetry-bytes (* 10 1024 1024))

(defn- base64-characters [bytes]
  (* 4 (quot (+ bytes 2) 3)))

(def max-garmin-base64-characters
  (base64-characters max-telemetry-bytes))

(def max-render-request-bytes
  (+ max-garmin-base64-characters
     max-telemetry-bytes
     (base64-characters (:bytes watermark/limits))
     (* 2 1024 1024)))

(defn- instant [value field]
  (try
    (Instant/parse value)
    (catch Throwable cause
      (throw (errors/raise! (str field " must be an ISO-8601 instant")
                      {:type ::invalid-timestamp :field field}
                      cause)))))

(defn- require! [condition message data]
  (when-not condition
    (throw (errors/raise! message data))))

(defn- parse-heart-rate [format telemetry]
  (case format
    "polar-csv" (polar/parse-csv telemetry)
    "garmin-fit" (garmin/parse-fit-base64 telemetry)
    "oxiwear-hr-csv" (oxiwear/parse-heart-rate-csv telemetry)
    (throw (errors/raise! "Unsupported telemetry format"
                    {:type ::unsupported-format}))))

(defn- utf8-size [value]
  (alength (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- ordered? [samples]
  (every? (fn [[left right]]
            (.isBefore ^Instant (:timestamp left)
                       ^Instant (:timestamp right)))
          (partition 2 1 samples)))

(defn- covers? [samples start end]
  (and (not (.isAfter ^Instant (:timestamp (first samples)) start))
       (not (.isBefore ^Instant (:timestamp (last samples)) end))))

(defn prepare
  "Validates a render request and returns its shared normalized contract."
  [{:keys [telemetryFormat telemetry preset telemetrySyncAt cameraSyncAt
           sectionStartAt sectionEndAt spo2 timer watermark]}]
  (require! (#{"polar-csv" "garmin-fit" "oxiwear-hr-csv"}
             telemetryFormat)
            "Unsupported telemetry format"
            {:type ::unsupported-format})
  (require! (string? telemetry)
            "Telemetry must be CSV text or base64 FIT content"
            {:type ::invalid-telemetry})
  (let [telemetry-limit (if (= "garmin-fit" telemetryFormat)
                          max-garmin-base64-characters
                          max-telemetry-bytes)]
    (require! (<= (utf8-size telemetry) telemetry-limit)
              "Telemetry exceeds the size limit"
              {:type ::telemetry-too-large :limit telemetry-limit}))
  (when spo2
    (require! (and (map? spo2)
                   (= "oxiwear-spo2-csv" (:format spo2))
                   (string? (:telemetry spo2)))
              "Invalid optional SpO2 input"
              {:type ::invalid-spo2})
    (require! (<= (utf8-size (:telemetry spo2)) max-telemetry-bytes)
              "SpO2 telemetry exceeds the size limit"
              {:type ::spo2-too-large :limit max-telemetry-bytes}))
  (when timer
    (require! (and (map? timer)
                   (string? (:startAt timer))
                   (string? (:endAt timer)))
              "Invalid timer interval"
              {:type ::invalid-timer}))
  (when watermark
    (require! (and (map? watermark)
                   (string? (:contentBase64 watermark)))
              "Invalid watermark input"
              {:type ::invalid-watermark}))
  (let [render-preset (spec/preset preset)
        telemetry-sync (instant telemetrySyncAt :telemetrySyncAt)
        camera-sync (instant cameraSyncAt :cameraSyncAt)
        section-start (instant sectionStartAt :sectionStartAt)
        section-end (instant sectionEndAt :sectionEndAt)
        timer-start (when timer (instant (:startAt timer) :timerStartAt))
        timer-end (when timer (instant (:endAt timer) :timerEndAt))
        duration-nanos (.toNanos (Duration/between section-start section-end))
        duration-seconds (/ duration-nanos 1000000000)
        telemetry-start (.plusNanos telemetry-sync
                                    (.toNanos (Duration/between camera-sync
                                                                section-start)))
        telemetry-end (.plusNanos telemetry-sync
                                  (.toNanos (Duration/between camera-sync
                                                              section-end)))
        samples (parse-heart-rate telemetryFormat telemetry)
        spo2-samples (when spo2
                       (oxiwear/parse-spo2-csv (:telemetry spo2)))
        watermark-image (when watermark
                          (watermark/decode-base64
                           (:contentBase64 watermark)))]
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
    (when timer
      (require! (and (not (.isBefore timer-start section-start))
                     (.isBefore timer-start timer-end)
                     (not (.isAfter timer-end section-end)))
                "Timer must satisfy sectionStartAt <= startAt < endAt <= sectionEndAt"
                {:type ::invalid-timer-order}))
    (require! (>= (count samples) 2)
              "Telemetry must contain at least two samples"
              {:type ::insufficient-telemetry})
    (require! (ordered? samples)
              "Telemetry timestamps must be strictly increasing"
              {:type ::unordered-telemetry})
    (require! (covers? samples telemetry-start telemetry-end)
              "Telemetry does not cover the requested section"
              {:type ::insufficient-coverage})
    (when spo2-samples
      (require! (>= (count spo2-samples) 2)
                "SpO2 telemetry must contain at least two samples"
                {:type ::insufficient-spo2})
      (require! (ordered? spo2-samples)
                "SpO2 timestamps must be strictly increasing"
                {:type ::unordered-spo2})
      (require! (covers? spo2-samples telemetry-start telemetry-end)
                "SpO2 telemetry does not cover the requested section"
                {:type ::insufficient-spo2-coverage}))
    (cond-> (assoc (spec/with-duration render-preset duration-seconds)
                   :telemetry (timeline/section samples
                                                telemetry-start
                                                telemetry-end))
      spo2-samples
      (assoc :spo2 (timeline/section-values spo2-samples
                                            :spo2
                                            telemetry-start
                                            telemetry-end))

      timer
      (assoc :timer {:start-seconds (timeline/seconds-between section-start
                                                              timer-start)
                     :end-seconds (timeline/seconds-between section-start
                                                            timer-end)})

      watermark-image
      (assoc :watermark watermark-image))))
