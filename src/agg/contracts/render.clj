(ns agg.contracts.render
  (:require [agg.drive.core :as drive]
            [agg.drive.limits :as drive-limits]
            [agg.errors :as errors]
            [agg.render.spec :as spec]
            [agg.render.watermark :as watermark]
            [agg.telemetry.garmin :as garmin]
            [agg.telemetry.oxiwear :as oxiwear]
            [agg.telemetry.polar :as polar]
            [agg.telemetry.timeline :as timeline]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.time Duration Instant ZoneId)))

(def max-telemetry-bytes (* 10 1024 1024))

(def selected-work-envelope
  (select-keys drive-limits/renderer-range-limits-v1
               [:max-upstream-bytes
                :max-request-count
                :max-range-bytes]))

(def selected-source-profile
  {:version "camera-profile-pending-evidence-v1"
   :status :pending-evidence
   :required-camera-families #{"GoPro" "DJI"}
   :required-codecs #{"h264" "hevc"}
   :required-bit-depths #{8 10}
   :required-selection-points #{:start :middle :late}
   :required-authoritative-measurements
   #{:index-placement :max-upstream-bytes :max-request-count
     :elapsed-ms :peak-memory-bytes :estimated-cost}})

(def max-source-bytes (* 2 1024 1024 1024))

(defn- authoritative-selected-work-estimate
  [{:keys [evidence-version index-placement selected-work]}]
  (let [{:keys [max-upstream-bytes max-request-count]} selected-work]
    (when-not
     (and (= "authoritative-selected-range-v1" evidence-version)
          (contains? #{"front" "end"} index-placement)
          (integer? max-upstream-bytes)
          (pos? max-upstream-bytes)
          (integer? max-request-count)
          (pos? max-request-count))
      (throw
       (errors/raise!
        "Selected source evidence is pending"
        {:type ::selected-work-evidence-pending
         :reason "selected_source_evidence_pending"})))
    {:estimated-upstream-bytes max-upstream-bytes
     :estimated-request-count max-request-count}))

(defn preflight-selected-work
  "Admits authoritative selected-range bounds without using whole-file size."
  [{:keys [duration-seconds]} inspection]
  (let [{:keys [estimated-upstream-bytes estimated-request-count] :as estimate}
        (authoritative-selected-work-estimate inspection)]
    (cond
      (> estimated-upstream-bytes
         (:max-upstream-bytes selected-work-envelope))
      (throw (errors/raise!
              "Selected source transfer exceeds the processing envelope"
              {:type ::selected-work-exceeds-envelope
               :reason "selected_source_transfer"
               :guidance
               (str "This range needs more source transfer or processing time "
                    "than Alpha Compose can safely use. Shorten the range, "
                    "choose another section, or optimize the video as a "
                    "seekable MP4. The complete recording can stay in Drive.")}))

      (> estimated-request-count
         (:max-request-count selected-work-envelope))
      (throw (errors/raise!
              "Selected source request count exceeds the processing envelope"
              {:type ::selected-work-exceeds-envelope
               :reason "selected_source_requests"}))

      :else
      (merge {:status :accepted
              :profile-version (:version selected-source-profile)
              :profile-status (:status selected-source-profile)
              :guaranteed? false
              :selected-duration-seconds duration-seconds}
             (-> estimate
                 (update :estimated-upstream-bytes long)
                 (update :estimated-request-count long))))))

(defn- base64-characters [bytes]
  (* 4 (quot (+ bytes 2) 3)))

(def max-garmin-base64-characters
  (base64-characters max-telemetry-bytes))

(def max-render-request-bytes
  (+ max-garmin-base64-characters
     max-telemetry-bytes
     (base64-characters (:bytes watermark/limits))
     (* 2 1024 1024)))

(def telemetry-documentation-path
  "/openapi.yaml#/components/schemas/RenderRequest")

(def telemetry-failure-codes
  #{"unsupported_telemetry_columns"
    "malformed_telemetry_row"
    "heart_rate_out_of_range"
    "telemetry_value_out_of_range"
    "unordered_telemetry"
    "insufficient_telemetry_coverage"
    "telemetry_too_large"
    "telemetry_sample_limit_exceeded"
    "unsupported_telemetry_format"
    "invalid_telemetry"})

(defn- error-data [error]
  (loop [current error
         data []]
    (if current
      (recur (.getCause ^Throwable current)
             (conj data (ex-data current)))
      data)))

(def ^:private telemetry-failure-types
  [[::polar/unsupported-columns
    {:failure-code "unsupported_telemetry_columns"
     :field "telemetry"
     :expected-schema polar/expected-schema}]
   [::oxiwear/unsupported-columns
    {:failure-code "unsupported_telemetry_columns"}]
   [::polar/heart-rate-out-of-range
    {:failure-code "heart_rate_out_of_range" :field "telemetry"}]
   [::oxiwear/heart-rate-out-of-range
    {:failure-code "heart_rate_out_of_range"}]
   [::garmin/heart-rate-out-of-range
    {:failure-code "heart_rate_out_of_range" :field "telemetry"}]
   [::oxiwear/value-out-of-range
    {:failure-code "telemetry_value_out_of_range"}]
   [::polar/too-many-samples
    {:failure-code "telemetry_sample_limit_exceeded" :field "telemetry"}]
   [::oxiwear/too-many-samples
    {:failure-code "telemetry_sample_limit_exceeded"}]
   [::garmin/too-many-samples
    {:failure-code "telemetry_sample_limit_exceeded" :field "telemetry"}]
   [::polar/malformed-row
    {:failure-code "malformed_telemetry_row" :field "telemetry"}]
   [::oxiwear/malformed-row
    {:failure-code "malformed_telemetry_row"}]
   [::unordered-telemetry
    {:failure-code "unordered_telemetry" :field "telemetry"}]
   [::unordered-spo2
    {:failure-code "unordered_telemetry" :field "spo2.telemetry"}]
   [::insufficient-telemetry
    {:failure-code "insufficient_telemetry_coverage" :field "telemetry"}]
   [::insufficient-coverage
    {:failure-code "insufficient_telemetry_coverage" :field "telemetry"}]
   [::insufficient-spo2
    {:failure-code "insufficient_telemetry_coverage"
     :field "spo2.telemetry"}]
   [::insufficient-spo2-coverage
    {:failure-code "insufficient_telemetry_coverage"
     :field "spo2.telemetry"}]
   [::telemetry-too-large
    {:failure-code "telemetry_too_large" :field "telemetry"}]
   [::spo2-too-large
    {:failure-code "telemetry_too_large" :field "spo2.telemetry"}]
   [::garmin/fit-too-large
    {:failure-code "telemetry_too_large" :field "telemetry"}]
   [::unsupported-format
    {:failure-code "unsupported_telemetry_format"
     :field "telemetryFormat"}]
   [::invalid-telemetry
    {:failure-code "invalid_telemetry" :field "telemetry"}]
   [::invalid-spo2
    {:failure-code "invalid_telemetry" :field "spo2"}]
   [::garmin/malformed-fit
    {:failure-code "unsupported_telemetry_format" :field "telemetry"}]])

(defn telemetry-failure
  "Classifies owned telemetry validation failures into privacy-safe public data."
  [error]
  (let [data (error-data error)
        [failure-type failure diagnostics]
        (some (fn [[failure-type failure]]
                (when-let [diagnostics
                           (some #(when (= failure-type (:type %)) %) data)]
                  [failure-type failure diagnostics]))
              telemetry-failure-types)]
    (when failure
      (let [field (or (:field failure) (:field diagnostics))
            expected-schema
            (or (:expected-schema failure)
                (when (= ::oxiwear/unsupported-columns failure-type)
                  (if (= "spo2.telemetry" field)
                    oxiwear/spo2-expected-schema
                    oxiwear/heart-rate-expected-schema)))]
        (cond-> (assoc failure
                       :field field
                       :documentation-path telemetry-documentation-path)
          (:line diagnostics) (assoc :line (:line diagnostics))
          expected-schema (assoc :expected-schema expected-schema))))))

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

(def ^:private nanos-per-second 1000000000)

(defn- whole-frame-count [duration-nanos fps]
  (let [scaled (* duration-nanos fps)]
    (when (zero? (rem scaled nanos-per-second))
      (quot scaled nanos-per-second))))

(defn- iana-time-zone [value field]
  (require! (and (string? value)
                 (not (str/blank? value))
                 (contains? (ZoneId/getAvailableZoneIds) value))
            (str field " must be a known IANA timezone identifier")
            {:type ::invalid-display-time-zone
             :field field})
  (ZoneId/of value))

(defn- display-time-zone [value]
  (iana-time-zone value "displayTimeZone"))

(defn- synchronization-mode [value]
  (require! (some? value)
            "synchronizationMode is required; choose shared-clock or manual-anchor"
            {:type ::invalid-synchronization-mode
             :field "synchronizationMode"})
  (require! (contains? #{"shared-clock" "manual-anchor"} value)
            "synchronizationMode must be shared-clock or manual-anchor"
            {:type ::invalid-synchronization-mode
             :field "synchronizationMode"})
  value)

(defn- synchronization-anchors [request mode]
  (case mode
    "shared-clock"
    (do
      (doseq [[key field] [[:telemetrySyncAt "telemetrySyncAt"]
                           [:cameraSyncAt "cameraSyncAt"]]]
        (require! (not (contains? request key))
                  (str field
                       " must be omitted when synchronizationMode is shared-clock")
                  {:type ::synchronization-anchor-forbidden
                   :field field}))
      nil)

    "manual-anchor"
    (do
      (doseq [[key field] [[:telemetrySyncAt "telemetrySyncAt"]
                           [:cameraSyncAt "cameraSyncAt"]]]
        (require! (contains? request key)
                  (str field
                       " is required when synchronizationMode is manual-anchor")
                  {:type ::synchronization-anchor-required
                   :field field}))
      {:telemetry-sync (instant (:telemetrySyncAt request)
                                "telemetrySyncAt")
       :camera-sync (instant (:cameraSyncAt request)
                             "cameraSyncAt")})))

(def default-future-trace-opacity-percent 25)

(defn- future-trace-opacity-percent [request]
  (let [value (if (contains? request :futureTraceOpacityPercent)
                (:futureTraceOpacityPercent request)
                default-future-trace-opacity-percent)]
    (require! (and (number? value) (<= 0 value 100))
              "futureTraceOpacityPercent must be a number from 0 through 100"
              {:type ::invalid-future-trace-opacity
               :field "futureTraceOpacityPercent"})
    value))

(defn normalize-request
  "Defaults and validates values that must be persisted with a render request."
  [request]
  (assoc request :futureTraceOpacityPercent
         (future-trace-opacity-percent request)))

(defn- transparent-alpha-bits [request source-video]
  (let [present? (contains? request :transparentAlphaBits)
        value (if present? (:transparentAlphaBits request) 16)]
    (when present?
      (require! (nil? source-video)
                "transparentAlphaBits is only valid for transparent overlays"
                {:type ::transparent-alpha-requires-overlay
                 :field "transparentAlphaBits"})
      (require! (contains? #{8 16} value)
                "transparentAlphaBits must be 8 or 16"
                {:type ::invalid-transparent-alpha-bits
                 :field "transparentAlphaBits"}))
    (when-not source-video value)))

(defn- source-options [source-video output-format fit-mode audio-mode]
  (when source-video
    (require! (and (map? source-video)
                   (string? (:fileId source-video))
                   (not (str/blank? (:fileId source-video))))
              "sourceVideo.fileId is required"
              {:type ::invalid-source-video}))
  (when (or output-format fit-mode audio-mode)
    (require! source-video
              "Video composition requires sourceVideo.fileId"
              {:type ::composition-requires-source}))
  (let [output-format (case (or output-format "h264-mp4")
                        "mp4" "h264-mp4"
                        "h264" "h264-mp4"
                        "prores-422" "prores-422-mov"
                        "h264-mp4" "h264-mp4"
                        "prores-422-mov" "prores-422-mov"
                        nil)
        fit-mode (or fit-mode "letterbox")
        audio-mode (case (or audio-mode "source+heartbeat")
                     "source" "source-only"
                     "heartbeat" "heartbeat-only"
                     "source-and-heartbeat" "source+heartbeat"
                     "source+heartbeat" "source+heartbeat"
                     "source-only" "source-only"
                     "heartbeat-only" "heartbeat-only"
                     nil)]
    (when source-video
      (require! (contains? #{"h264-mp4" "prores-422-mov"} output-format)
                "Unsupported composited output format"
                {:type ::unsupported-output-format})
      (require! (contains? #{"letterbox" "pillarbox" "crop"} fit-mode)
                "Unsupported source fit mode"
                {:type ::unsupported-fit-mode})
      (require! (contains? #{"source+heartbeat" "source-only" "heartbeat-only"}
                           audio-mode)
                "Unsupported composited audio mode"
                {:type ::unsupported-audio-mode})
      {:output-format output-format
       :fit-mode fit-mode
       :audio-mode audio-mode})))

(defn- source-video-clock [source-video]
  (when source-video
    (require! (and (string? (:recordingStartAt source-video))
                   (not (str/blank? (:recordingStartAt source-video))))
              "sourceVideo.recordingStartAt is required"
              {:type ::invalid-source-video-clock
               :field "sourceVideo.recordingStartAt"})
    (require! (and (string? (:timeZone source-video))
                   (not (str/blank? (:timeZone source-video))))
              "sourceVideo.timeZone is required"
              {:type ::invalid-source-video-clock
               :field "sourceVideo.timeZone"})
    {:recording-start-at
     (instant (:recordingStartAt source-video)
              "sourceVideo.recordingStartAt")
     :time-zone
     (iana-time-zone (:timeZone source-video) "sourceVideo.timeZone")}))

(defn- parse-heart-rate [format telemetry required-start required-end]
  (case format
    "polar-csv" (polar/parse-csv telemetry {:required-start required-start
                                            :required-end required-end})
    "garmin-fit" (garmin/parse-fit-base64 telemetry)
    "oxiwear-hr-csv" (oxiwear/parse-heart-rate-csv telemetry)
    (throw (errors/raise! "Unsupported telemetry format"
                          {:type ::unsupported-format
                           :field "telemetryFormat"}))))

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

(declare attach-source-metadata)

(defn prepare
  "Validates a render request and returns its shared normalized contract."
  [{:keys [telemetryFormat telemetry preset sectionStartAt sectionEndAt
           displayTimeZone spo2 timer watermark sourceVideo
           outputFormat fitMode audioMode format fit audio sourceVideoServerMetadata
           synchronizationMode]
    :as request}]
  (let [synchronization-mode (synchronization-mode synchronizationMode)
        future-trace-opacity-percent
        (future-trace-opacity-percent request)
        source (source-options sourceVideo
                               (or outputFormat format)
                               (or fitMode fit)
                               (or audioMode audio))
        transparent-alpha-bits
        (transparent-alpha-bits request sourceVideo)
        source-clock (source-video-clock sourceVideo)]
    (require! (#{"polar-csv" "garmin-fit" "oxiwear-hr-csv"}
               telemetryFormat)
              "Unsupported telemetry format"
              {:type ::unsupported-format :field "telemetryFormat"})
    (require! (string? telemetry)
              "Telemetry must be CSV text or base64 FIT content"
              {:type ::invalid-telemetry :field "telemetry"})
    (let [telemetry-limit (if (= "garmin-fit" telemetryFormat)
                            max-garmin-base64-characters
                            max-telemetry-bytes)]
      (require! (<= (utf8-size telemetry) telemetry-limit)
                "Telemetry exceeds the size limit"
                {:type ::telemetry-too-large
                 :field "telemetry"
                 :limit telemetry-limit}))
    (when spo2
      (require! (and (map? spo2)
                     (= "oxiwear-spo2-csv" (:format spo2))
                     (string? (:telemetry spo2)))
                "Invalid optional SpO2 input"
                {:type ::invalid-spo2 :field "spo2"})
      (require! (<= (utf8-size (:telemetry spo2)) max-telemetry-bytes)
                "SpO2 telemetry exceeds the size limit"
                {:type ::spo2-too-large
                 :field "spo2.telemetry"
                 :limit max-telemetry-bytes}))
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
          {:keys [telemetry-sync camera-sync]}
          (synchronization-anchors request synchronization-mode)
          section-start (instant sectionStartAt "sectionStartAt")
          section-end (instant sectionEndAt "sectionEndAt")
          display-time-zone (display-time-zone displayTimeZone)
          timer-start (when timer (instant (:startAt timer) :timerStartAt))
          timer-end (when timer (instant (:endAt timer) :timerEndAt))
          duration-nanos (.toNanos (Duration/between section-start section-end))
          duration-frames (whole-frame-count duration-nanos
                                             (:fps render-preset))
          source-trim-nanos
          (when source-clock
            (.toNanos
             (Duration/between (:recording-start-at source-clock)
                               section-start)))
          source-trim-frames
          (when (and source-trim-nanos (not (neg? source-trim-nanos)))
            (whole-frame-count source-trim-nanos (:fps render-preset)))
          telemetry-start
          (if (= "shared-clock" synchronization-mode)
            section-start
            (.plusNanos telemetry-sync
                        (.toNanos (Duration/between camera-sync
                                                    section-start))))
          telemetry-end
          (if (= "shared-clock" synchronization-mode)
            section-end
            (.plusNanos telemetry-sync
                        (.toNanos (Duration/between camera-sync
                                                    section-end))))
          parsed-samples (parse-heart-rate telemetryFormat telemetry
                                           telemetry-start telemetry-end)
          samples (filterv #(not (::polar/sensor-gap %)) parsed-samples)
          spo2-samples (when spo2
                         (oxiwear/parse-spo2-csv (:telemetry spo2)))
          watermark-image (when watermark
                            (watermark/decode-base64
                             (:contentBase64 watermark)))]
      (require! (.isBefore section-start section-end)
                "Timestamps must be ordered sectionStartAt < sectionEndAt"
                {:type ::invalid-timestamp-order})
      (require! duration-frames
                "Section duration must be a whole number of 25 fps frames"
                {:type ::fractional-duration})
      (require! (<= duration-frames
                    (* (:fps render-preset)
                       (:duration-seconds render-preset)))
                "Section duration exceeds the preset maximum"
                {:type ::duration-too-long})
      (when source-clock
        (require! (not (neg? source-trim-nanos))
                  "sectionStartAt cannot precede sourceVideo.recordingStartAt"
                  {:type ::negative-source-trim
                   :field "sectionStartAt"})
        (require! source-trim-frames
                  "sectionStartAt must select an exact 25 fps source frame"
                  {:type ::fractional-source-trim
                   :field "sectionStartAt"}))
      (when timer
        (require! (and (not (.isBefore timer-start section-start))
                       (.isBefore timer-start timer-end)
                       (not (.isAfter timer-end section-end)))
                  "Timer must satisfy sectionStartAt <= startAt < endAt <= sectionEndAt"
                  {:type ::invalid-timer-order}))
      (require! (>= (count samples) 2)
                "Telemetry must contain at least two samples"
                {:type ::insufficient-telemetry :field "telemetry"})
      (require! (ordered? parsed-samples)
                "Telemetry timestamps must be strictly increasing"
                {:type ::unordered-telemetry :field "telemetry"})
      (require! (covers? samples telemetry-start telemetry-end)
                "Telemetry does not cover the requested section"
                {:type ::insufficient-coverage :field "telemetry"})
      (when spo2-samples
        (require! (>= (count spo2-samples) 2)
                  "SpO2 telemetry must contain at least two samples"
                  {:type ::insufficient-spo2 :field "spo2.telemetry"})
        (require! (ordered? spo2-samples)
                  "SpO2 timestamps must be strictly increasing"
                  {:type ::unordered-spo2 :field "spo2.telemetry"})
        (require! (covers? spo2-samples telemetry-start telemetry-end)
                  "SpO2 telemetry does not cover the requested section"
                  {:type ::insufficient-spo2-coverage
                   :field "spo2.telemetry"}))
      (cond-> (assoc (spec/with-frame-duration render-preset duration-frames)
                     :synchronization-mode synchronization-mode
                     :future-trace-opacity-percent
                     future-trace-opacity-percent
                     :transparent-alpha-bits
                     transparent-alpha-bits
                     :section-start-at section-start
                     :display-time-zone display-time-zone
                     :telemetry (timeline/section samples
                                                  telemetry-start
                                                  telemetry-end))
        source
        (merge source)

        sourceVideo
        (assoc :source-video
               (assoc source-clock
                      :file-id (:fileId sourceVideo)
                      :trim-offset-frames source-trim-frames
                      :trim-offset-seconds
                      (/ source-trim-frames (:fps render-preset))))

        sourceVideoServerMetadata
        (attach-source-metadata sourceVideoServerMetadata)

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
        (assoc :watermark watermark-image)))))

(defn- attach-source-metadata*
  [render-spec {:keys [id name mimeType size trashed] :as metadata}
   enforce-size-limit?]
  (let [file-id (get-in render-spec [:source-video :file-id])
        duration-millis
        (try
          (some-> (get-in metadata [:videoMediaMetadata :durationMillis])
                  str
                  parse-long)
          (catch Throwable _ nil))
        selected-end-millis
        (when (number? (:duration-seconds render-spec))
          (* 1000
             (+ (get-in render-spec [:source-video :trim-offset-seconds] 0)
                (:duration-seconds render-spec))))]
    (require! (and file-id
                   (= file-id id)
                   (string? name)
                   (string? mimeType)
                   (drive/supported-source-video-mime-type? mimeType)
                   (integer? size)
                   (not (neg? size))
                   (not trashed))
              "Drive source metadata is invalid"
              {:type ::invalid-source-metadata})
    (when enforce-size-limit?
      (require! (<= size max-source-bytes)
                "Drive source exceeds the size limit"
                {:type ::source-too-large
                 :limit max-source-bytes
                 :size size}))
    (when (and duration-millis selected-end-millis
               (not (neg? duration-millis)))
      (require! (<= selected-end-millis duration-millis)
                "sectionEndAt exceeds the known source video duration"
                {:type ::source-range-too-long
                 :field "sectionEndAt"
                 :limit duration-millis}))
    (update render-spec :source-video
            merge
            {:metadata
             (select-keys metadata
                          [:id :name :mimeType :size :trashed
                           :videoMediaMetadata])})))

(defn attach-source-metadata
  "Attaches metadata fetched from Drive for render admission.
   Request-supplied metadata is ignored."
  [render-spec metadata]
  (attach-source-metadata* render-spec metadata true))

(defn attach-source-selection-metadata
  "Attaches metadata fetched from Drive for source selection, playback, and
   recording-clock inspection without whole-file admission."
  [render-spec metadata]
  (attach-source-metadata* render-spec metadata false))
