(ns agg.render.media
  (:require [agg.errors :as errors]
            [agg.render.gallery :as gallery]
            [agg.render.plan :as plan]
            [agg.render.process :as process]
            [agg.render.source :as source]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream RandomAccessFile)
           (java.nio.file Files OpenOption Path)
           (java.util.concurrent TimeUnit)
           (javax.imageio ImageIO)))

(defprotocol VideoEncoder
  (encode! [encoder render-spec audio-path output-path write-frames!])
  (verify! [encoder render-spec output-path]))

(defprotocol CompositeEncoder
  (encode-composite! [encoder render-spec heartbeat-path output-path
                      source-stream! write-overlay!])
  (verify-composite! [encoder render-spec output-path]))

(defprotocol CompositeGalleryRenderer
  (render-composite-gallery! [renderer render-spec source-stream! overlays
                              consume-frame!]
    "Batch-decodes selected source frames and emits composited Final PNGs."))

(def prores-4444-contract plan/prores-4444-contract)
(def aac-lc-contract plan/aac-lc-contract)
(def h264-mp4-contract plan/h264-mp4-contract)
(def prores-422-contract plan/prores-422-contract)

(def durable-composite-timeout-ms (* 45 60 1000))
(def durable-composite-smoke-bound-ms 30000)

(def timing-metadata plan/timing-metadata)

(defn- timing-metadata-arguments [render-spec]
  (plan/timing-metadata-arguments render-spec))

(defn- process-builder [command]
  (process/process-builder command))

(defn- capture-output [input]
  (process/capture-output input))

(defn- run-captured!
  ([command]
   (process/run-captured-as! command ::media-tool-failed))
  ([command timeout-ms]
   (process/run-captured-as! command timeout-ms ::media-tool-failed
                             ::media-tool-timeout)))

(defn- encode-with-ffmpeg! [ffmpeg render-spec audio-path output-path write-frames!]
  (let [{:keys [width height fps]} render-spec
        command (into [ffmpeg
                       "-hide_banner"
                       "-nostdin"
                       "-loglevel" "error"
                       "-f" "rawvideo"
                       "-pixel_format" "rgba"
                       "-video_size" (str width "x" height)
                       "-framerate" (str fps)
                       "-i" "pipe:0"
                       "-i" (str audio-path)
                       "-map" "0:v:0"
                       "-map" "1:a:0"
                       "-c:v" (:encoder prores-4444-contract)
                       "-profile:v" (str (:profile prores-4444-contract))
                       "-pix_fmt" (:encoder-input-pixel-format prores-4444-contract)
                       "-alpha_bits" (str (:alpha-bits prores-4444-contract))
                       "-vendor" "apl0"
                       "-c:a" (:encoder aac-lc-contract)
                       "-profile:a" (:profile aac-lc-contract)
                       "-ar" (str (:sample-rate aac-lc-contract))
                       "-ac" (str (:channels aac-lc-contract))
                       "-b:a" (:target-bitrate aac-lc-contract)
                       "-shortest"
                       "-movflags" "+use_metadata_tags"]
                      (concat (timing-metadata-arguments render-spec)
                              ["-y" (str output-path)]))
        process (.start (process-builder command))
        captured (capture-output (.getInputStream process))]
    (try
      (with-open [video-input (.getOutputStream process)]
        (write-frames! video-input))
      (let [exit-status (.waitFor process)]
        @captured
        (when-not (zero? exit-status)
          (throw (errors/raise! "FFmpeg encoding failed"
                                {:type ::encoding-failed
                                 :exit-status exit-status})))
        {:exit-status exit-status})
      (catch Throwable error
        (.destroyForcibly process)
        @captured
        (throw error)))))

(defn inspect-selected-source!
  "Inspects bounded stream metadata through an identity-free loopback URL."
  [ffprobe render-spec]
  (source/inspect-selected! ffprobe render-spec))

(defn inspect-browser-playback!
  "Returns normalized codec evidence for direct browser playback decisions."
  [ffprobe url]
  (source/inspect-playback! ffprobe url))

(defn composite-command
  "Returns the bounded FFmpeg command shape used by the compositing path."
  [ffmpeg render-spec heartbeat-path overlay-pipe output-path]
  (plan/composite-command ffmpeg render-spec heartbeat-path overlay-pipe
                          output-path))

(defn composite-gallery-command
  "Returns one bounded source-decode command for all selected output frames."
  [ffmpeg render-spec frame-indexes overlay-path]
  (plan/composite-gallery-command ffmpeg render-spec frame-indexes overlay-path))

(defn- write-overlay-rgba! [path overlays width height]
  (with-open [output (Files/newOutputStream
                      path (make-array OpenOption 0))]
    (doseq [{:keys [overlay]} overlays]
      (let [image (ImageIO/read (ByteArrayInputStream. overlay))]
        (when-not (and image (= width (.getWidth image)) (= height (.getHeight image)))
          (throw (errors/raise! "Preview overlay dimensions are invalid"
                                {:type ::invalid-gallery-overlay})))
        (let [row (int-array width)
              rgba (byte-array (* width 4))]
          (dotimes [y height]
            (.getRGB image 0 y width 1 row 0 width)
            (dotimes [x width]
              (let [argb (aget row x)
                    offset (* x 4)]
                (aset-byte rgba offset (unchecked-byte (bit-shift-right argb 16)))
                (aset-byte rgba (inc offset) (unchecked-byte (bit-shift-right argb 8)))
                (aset-byte rgba (+ offset 2) (unchecked-byte argb))
                (aset-byte rgba (+ offset 3)
                           (unchecked-byte (unsigned-bit-shift-right argb 24)))))
            (.write output rgba 0 (alength rgba))))))))

(defn- concatenated-pngs [bytes]
  (try
    (gallery/decode-png-stream bytes)
    (catch clojure.lang.ExceptionInfo error
      (if (= ::gallery/invalid-output (:type (ex-data error)))
        (throw (errors/raise! (.getMessage error)
                              {:type ::invalid-gallery-output}
                              error))
        (throw error)))))

(defn- consume-gallery-png! [render-spec frame-index final-png consume-frame!]
  (try
    (gallery/consume-png! render-spec frame-index final-png consume-frame!)
    (catch clojure.lang.ExceptionInfo error
      (if (= ::gallery/invalid-output (:type (ex-data error)))
        (throw (errors/raise! (.getMessage error)
                              {:type ::invalid-gallery-output}
                              error))
        (throw error)))))

(defn- render-composite-gallery-with-ffmpeg!
  [ffmpeg render-spec source-stream! overlays consume-frame!]
  (let [{:keys [width height]} render-spec
        seekable? (some? (plan/seekable-source-url render-spec))
        frame-indexes (mapv :frameIndex overlays)
        overlay-path (Files/createTempFile
                      "agg-preview-overlays-" ".rgba"
                      (make-array java.nio.file.attribute.FileAttribute 0))
        maximum-output-bytes (* (count overlays)
                                (+ (* width height 4) (* 1024 1024)))]
    (try
      (write-overlay-rgba! overlay-path overlays width height)
      (let [process (.start
                     (doto (ProcessBuilder. ^java.util.List
                            (composite-gallery-command
                             ffmpeg render-spec frame-indexes
                             overlay-path))
                       (.redirectErrorStream false)))
            stdout (future
                     (with-open [input (.getInputStream process)]
                       (.readNBytes input (inc maximum-output-bytes))))
            stderr (future
                     (with-open [input (.getErrorStream process)]
                       (.readAllBytes input)))
            source-error (atom nil)
            source-pipe-write-error (atom nil)]
        (try
          (if seekable?
            (.close (.getOutputStream process))
            (try
              (with-open [source-output (.getOutputStream process)]
                (source-stream!
                 (process/monitored-pipe-output
                  source-output source-pipe-write-error)))
              (catch Throwable error
                (reset! source-error error))))
          (let [timeout-ms (long (or (:timeout-ms render-spec)
                                     durable-composite-timeout-ms))
                completed? (.waitFor process timeout-ms TimeUnit/MILLISECONDS)
                _ (when-not completed?
                    (throw (process/timeout-error timeout-ms)))
                exit-status (.exitValue process)
                output @stdout
                expected-pipe-closure?
                (and @source-error
                     (process/caused-by?
                      @source-error @source-pipe-write-error))]
            @stderr
            (when (and @source-error (not expected-pipe-closure?))
              (throw (errors/raise! "Selected-source streaming failed"
                                    {:type ::source-stream-failed
                                     :reason "source_stream_failed"
                                     :retryable true}
                                    @source-error)))
            (when-not (zero? exit-status)
              (throw (errors/raise! "Selected-source gallery composition failed"
                                    {:type ::composite-gallery-failed
                                     :exit-status exit-status
                                     :reason "preview_decode_failed"})))
            (when (> (alength ^bytes output) maximum-output-bytes)
              (throw (errors/raise! "Selected-source gallery output is too large"
                                    {:type ::gallery-output-too-large
                                     :limit maximum-output-bytes})))
            (let [pngs (concatenated-pngs output)
                  requested (count frame-indexes)
                  generated (count pngs)]
              (when (> generated requested)
                (throw (errors/raise! "Source gallery emitted too many images"
                                      {:type ::invalid-gallery-output
                                       :limit requested})))
              (doseq [[frame-index png] (map vector frame-indexes pngs)]
                (consume-gallery-png! render-spec frame-index png consume-frame!))
              (cond-> {:requested-frame-count requested
                       :generated-frame-count generated
                       :omitted-frame-count (- requested generated)
                       :source-decodes 1}
                (< generated requested)
                (assoc :reason "source_duration_too_short"))))
          (catch Throwable error
            (.destroyForcibly process)
            (future-cancel stdout)
            (future-cancel stderr)
            (throw error))))
      (finally
        (Files/deleteIfExists overlay-path)))))

(defn- encode-composite-with-ffmpeg!
  [ffmpeg render-spec heartbeat-path output-path source-stream! write-overlay!]
  (process/encode-composite!
   ffmpeg render-spec heartbeat-path output-path source-stream! write-overlay!
   durable-composite-timeout-ms))

(defn- read-unsigned-int [^RandomAccessFile file]
  (bit-and 0xffffffff (long (.readInt file))))

(defn- top-level-atoms [^Path path]
  (with-open [file (RandomAccessFile. (.toFile path) "r")]
    (let [length (.length file)]
      (loop [offset 0
             atoms []]
        (if (> (+ offset 8) length)
          atoms
          (do
            (.seek file offset)
            (let [size32 (read-unsigned-int file)
                  atom-type (String. (byte-array (repeatedly 4 #(.readByte file)))
                                     "US-ASCII")
                  size (cond
                         (= size32 1) (.readLong file)
                         (zero? size32) (- length offset)
                         :else size32)]
              (when (< size 8)
                (throw (errors/raise! "Invalid MOV atom size" {:type ::invalid-container})))
              (recur (+ offset size) (conj atoms atom-type)))))))))

(defn- parse-rate [rate]
  (let [[numerator denominator] (map parse-long (str/split rate #"/"))]
    (/ (double numerator) (double denominator))))

(defn- approximately= [expected actual tolerance]
  (<= (Math/abs (- (double expected) (double actual))) tolerance))

(defn- verified-timing-metadata? [render-spec format]
  (let [expected (timing-metadata render-spec)
        actual (into {}
                     (map (fn [[key value]]
                            [(if (keyword? key) (name key) (str key)) value]))
                     (:tags format))]
    (or (nil? expected)
        (= expected (select-keys actual (keys expected))))))

(defn- verified-media [render-spec probe atoms]
  (let [streams (:streams probe)
        video (first (filter #(= "video" (:codec_type %)) streams))
        audio (first (filter #(= "audio" (:codec_type %)) streams))
        format (:format probe)
        duration (parse-double (:duration format))
        bitrate (some-> (:bit_rate audio) parse-long)
        expected-duration (:duration-seconds render-spec)]
    (when-not (and (= "prores" (:codec_name video))
                   (= "4444" (:profile video))
                   (= "ap4h" (:codec_tag_string video))
                   (= (:width render-spec) (:width video))
                   (= (:height render-spec) (:height video))
                   (approximately= (:fps render-spec) (parse-rate (:r_frame_rate video)) 0.0001)
                   (= (:decoded-pixel-format prores-4444-contract)
                      (:pix_fmt video))
                   (= "aac" (:codec_name audio))
                   (= "LC" (:profile audio))
                   (= 2 (:channels audio))
                   (= "48000" (:sample_rate audio))
                   (or (nil? bitrate) (pos? bitrate))
                   (approximately= expected-duration duration (/ 1.0 (:fps render-spec)))
                   (str/includes? (:format_name format) "mov")
                   (verified-timing-metadata? render-spec format)
                   (some #{"moov"} atoms)
                   (some #{"mdat"} atoms)
                   (not-any? #{"moof"} atoms))
      (throw (errors/raise! "Encoded media does not satisfy the renderer contract"
                            {:type ::invalid-media-contract})))
    {:video {:codec "prores"
             :profile "4444"
             :encoder-input-pixel-format
             (:encoder-input-pixel-format prores-4444-contract)
             :pixel-format (:pix_fmt video)
             :alpha-bits (:alpha-bits prores-4444-contract)
             :alpha true
             :width (:width video)
             :height (:height video)
             :fps (parse-rate (:r_frame_rate video))}
     :audio {:codec "aac"
             :profile "LC"
             :channels (:channels audio)
             :sample-rate (parse-long (:sample_rate audio))
             :target-bitrate (:target-bitrate-bps aac-lc-contract)
             :observed-bitrate bitrate}
     :container {:format "mov"
                 :duration-seconds duration
                 :seekable true
                 :fragmented false}
     :timing (timing-metadata render-spec)
     :ffprobe probe}))

(defn- verified-composite-media [render-spec probe]
  (let [streams (:streams probe)
        video (first (filter #(= "video" (:codec_type %)) streams))
        audio (first (filter #(= "audio" (:codec_type %)) streams))
        format (:format probe)
        duration (or (some-> (:duration video) parse-double)
                     (some-> (:duration format) parse-double))
        expected-duration (:duration-seconds render-spec)
        output-format (:output-format render-spec)
        expected-video (if (= "prores-422-mov" output-format)
                         prores-422-contract
                         h264-mp4-contract)
        expected-container (:format expected-video)]
    (when (and duration (< duration (- (double expected-duration)
                                       (/ 1.0 (:fps render-spec)))))
      (throw (errors/raise! "Source video is shorter than the requested section"
                            {:type ::short-source})))
    (when-not (and (= (:codec expected-video) (:codec_name video))
                   (= (:width render-spec) (:width video))
                   (= (:height render-spec) (:height video))
                   (approximately= (:fps render-spec)
                                   (parse-rate (:r_frame_rate video))
                                   0.0001)
                   (or (= "h264" (:codec_name video))
                       (and (= "prores" (:codec_name video))
                            (= "yuv422p10le" (:pix_fmt video))
                            (contains? #{"apcn" "apch" "apcs"}
                                       (:codec_tag_string video))))
                   (= "aac" (:codec_name audio))
                   (= 2 (:channels audio))
                   (= "48000" (:sample_rate audio))
                   (approximately= expected-duration duration
                                   (/ 1.0 (:fps render-spec)))
                   (str/includes? (:format_name format) expected-container)
                   (verified-timing-metadata? render-spec format))
      (throw (errors/raise! "Encoded composited media violates its contract"
                            {:type ::invalid-composited-media-contract})))
    {:video {:codec (:codec_name video)
             :profile (:profile video)
             :pixel-format (:pix_fmt video)
             :width (:width video)
             :height (:height video)
             :fps (parse-rate (:r_frame_rate video))}
     :audio {:codec (:codec_name audio)
             :profile (:profile audio)
             :channels (:channels audio)
             :sample-rate (parse-long (:sample_rate audio))
             :target-bitrate (:target-bitrate-bps aac-lc-contract)
             :observed-bitrate (some-> (:bit_rate audio) parse-long)}
     :container {:format expected-container
                 :duration-seconds duration
                 :seekable true
                 :fragmented false}
     :timing (timing-metadata render-spec)
     :ffprobe probe}))

(defrecord FfmpegVideoEncoder [ffmpeg ffprobe]
  VideoEncoder
  (encode! [_ render-spec audio-path output-path write-frames!]
    (encode-with-ffmpeg! ffmpeg render-spec audio-path output-path write-frames!))
  (verify! [_ render-spec output-path]
    (let [probe-output (run-captured!
                        [ffprobe
                         "-v" "error"
                         "-show_entries"
                         (str "format=format_name,duration,size,probe_score:"
                              "format_tags:"
                              "stream=index,codec_type,codec_name,profile,codec_tag_string,"
                              "width,height,pix_fmt,r_frame_rate,duration,sample_rate,channels,channel_layout,bit_rate")
                         "-of" "json"
                         (str output-path)])
          probe (json/read-str probe-output :key-fn keyword)]
      (verified-media render-spec probe (top-level-atoms output-path))))
  CompositeEncoder
  (encode-composite! [_ render-spec heartbeat-path output-path source-stream!
                      write-overlay!]
    (encode-composite-with-ffmpeg! ffmpeg render-spec heartbeat-path output-path
                                   source-stream! write-overlay!))
  (verify-composite! [_ render-spec output-path]
    (let [probe-output (run-captured!
                        [ffprobe
                         "-v" "error"
                         "-show_entries"
                         (str "format=format_name,duration,size,probe_score:"
                              "format_tags:"
                              "stream=index,codec_type,codec_name,profile,codec_tag_string,"
                              "width,height,pix_fmt,r_frame_rate,duration,sample_rate,channels,channel_layout,bit_rate")
                         "-of" "json"
                         (str output-path)])
          probe (json/read-str probe-output :key-fn keyword)]
      (verified-composite-media render-spec probe)))
  CompositeGalleryRenderer
  (render-composite-gallery! [_ render-spec source-stream! overlays consume-frame!]
    (render-composite-gallery-with-ffmpeg! ffmpeg render-spec source-stream!
                                           overlays consume-frame!)))

(defn ffmpeg-video-encoder
  ([] (ffmpeg-video-encoder "ffmpeg" "ffprobe"))
  ([ffmpeg ffprobe]
   (->FfmpegVideoEncoder ffmpeg ffprobe)))

(defn ffmpeg-media
  "Compatibility constructor; prefer ffmpeg-video-encoder."
  ([] (ffmpeg-video-encoder))
  ([ffmpeg ffprobe] (ffmpeg-video-encoder ffmpeg ffprobe)))
