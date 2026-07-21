(ns agg.render.media
  (:require [agg.errors :as errors]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream IOException OutputStream RandomAccessFile)
           (java.nio.file Files OpenOption Path)
           (java.util Arrays)
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

(def prores-4444-contract
  {:encoder "prores_ks"
   :profile 4
   :encoder-input-pixel-format "yuva444p10le"
   :decoded-pixel-format "yuva444p12le"
   :alpha-bits 16})

(def aac-lc-contract
  {:encoder "aac"
   :profile "aac_low"
   :sample-rate 48000
   :channels 2
   :target-bitrate "192k"
   :target-bitrate-bps 192000})

(def h264-mp4-contract
  {:format "mp4"
   :codec "h264"
   :encoder "libx264"
   :pixel-format "yuv420p"})

(def prores-422-contract
  {:format "mov"
   :codec "prores"
   :profile "422"
   :encoder "prores_ks"
   :pixel-format "yuv422p10le"})

(def durable-composite-timeout-ms (* 45 60 1000))
(def durable-composite-smoke-bound-ms 30000)

(def ^:private process-stop-grace-ms 1000)
(def ^:private timed-out (Object.))

(defn- process-builder [command]
  (doto (ProcessBuilder. ^java.util.List command)
    (.redirectErrorStream true)))

(defn- capture-output [input]
  (let [captured (promise)
        thread (Thread.
                (fn []
                  (deliver captured
                           (try
                             (slurp input)
                             (catch Throwable _
                               ""))))
                "agg-media-output")]
    (.setDaemon thread true)
    (.start thread)
    captured))

(defn- run-captured! [command]
  (let [process (.start (process-builder command))
        captured (capture-output (.getInputStream process))
        exit-status (.waitFor process)
        output @captured]
    (when-not (zero? exit-status)
      (throw (errors/raise! "Media tool failed"
                            {:type ::media-tool-failed
                             :exit-status exit-status})))
    output))

(defn- encode-with-ffmpeg! [ffmpeg render-spec audio-path output-path write-frames!]
  (let [{:keys [width height fps]} render-spec
        command [ffmpeg
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
                 "-y"
                 (str output-path)]
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

(defn- fifo-path! [directory]
  (let [path (.resolve ^Path directory "overlay.rgba")
        process (.start (ProcessBuilder. ^java.util.List ["mkfifo" (str path)]))]
    (when-not (zero? (.waitFor process))
      (throw (errors/raise! "Could not create the overlay pipe"
                            {:type ::pipe-creation-failed})))
    path))

(defn- fit-filter [{:keys [width height fit-mode]}]
  (if (= "crop" fit-mode)
    (format "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1"
            width height width height)
    (format "scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1"
            width height width height)))

(defn composite-command
  "Returns the bounded FFmpeg command shape used by the compositing path."
  [ffmpeg render-spec heartbeat-path overlay-pipe output-path]
  (let [{:keys [width height fps duration-seconds output-format audio-mode]} render-spec
        video-filter (str "[0:v]" (fit-filter render-spec) "[base];"
                          "[1:v]format=rgba[overlay];"
                          "[base][overlay]overlay=0:0:format=auto:eof_action=endall[v]")
        audio-filter (case audio-mode
                       "heartbeat-only"
                       "[2:a]aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,alimiter=limit=0.95[a]"
                       "source-only"
                       "[0:a]aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,alimiter=limit=0.95[a]"
                       (str "[0:a]aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,volume=0.5[src];"
                            "[2:a]aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,volume=1.0[beat];"
                            "[src][beat]amix=inputs=2:duration=longest:dropout_transition=0,alimiter=limit=0.95[a]"))
        video-args (case output-format
                     "prores-422-mov"
                     ["-c:v" (:encoder prores-422-contract)
                      "-profile:v" "3"
                      "-pix_fmt" (:pixel-format prores-422-contract)]
                     ["-c:v" (:encoder h264-mp4-contract)
                      "-pix_fmt" (:pixel-format h264-mp4-contract)
                      "-preset" "fast"])
        format-args (if (= "prores-422-mov" output-format)
                      ["-f" "mov"]
                      ["-f" "mp4" "-movflags" "+faststart"])]
    (into [ffmpeg "-hide_banner" "-nostdin" "-loglevel" "error"
           "-i" "pipe:0"
           "-f" "rawvideo"
           "-pixel_format" "rgba"
           "-video_size" (str width "x" height)
           "-framerate" (str fps)
           "-i" (str overlay-pipe)
           "-i" (str heartbeat-path)
           "-filter_complex" (str video-filter ";" audio-filter)
           "-map" "[v]"
           "-map" "[a]"
           "-t" (str duration-seconds)
           "-r" (str fps)
           "-ar" "48000"
           "-ac" "2"
           "-c:a" "aac"
           "-b:a" (:target-bitrate aac-lc-contract)]
          (concat video-args format-args ["-y" (str output-path)]))))

(defn composite-gallery-command
  "Returns one bounded source-decode command for all selected output frames."
  [ffmpeg render-spec frame-indexes overlay-path]
  (let [{:keys [width height fps]} render-spec
        selection (str/join "+" (map #(format "eq(n\\,%d)" %)
                                     frame-indexes))
        video-filter
        (str "[0:v]" (fit-filter render-spec) ",fps=" fps
             ",select='" selection "',setpts=N/(" fps "*TB)[base];"
             "[1:v]format=rgba[overlay];"
             "[base][overlay]overlay=0:0:format=auto:eof_action=endall[v]")]
    [ffmpeg "-hide_banner" "-nostdin" "-loglevel" "error"
     "-i" "pipe:0"
     "-f" "rawvideo"
     "-pixel_format" "rgba"
     "-video_size" (str width "x" height)
     "-framerate" (str fps)
     "-i" (str overlay-path)
     "-filter_complex" video-filter
     "-map" "[v]"
     "-frames:v" (str (count frame-indexes))
     "-an"
     "-fps_mode" "passthrough"
     "-f" "image2pipe"
     "-vcodec" "png"
     "pipe:1"]))

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

(def ^:private png-signature
  (byte-array [(unchecked-byte 137) 80 78 71 13 10 26 10]))

(defn- bytes-match? [bytes offset expected]
  (and (<= (+ offset (alength ^bytes expected)) (alength ^bytes bytes))
       (every? (fn [index]
                 (= (aget ^bytes bytes (+ offset index))
                    (aget ^bytes expected index)))
               (range (alength ^bytes expected)))))

(defn- unsigned-int-at [bytes offset]
  (+ (bit-shift-left (bit-and 0xff (aget ^bytes bytes offset)) 24)
     (bit-shift-left (bit-and 0xff (aget ^bytes bytes (inc offset))) 16)
     (bit-shift-left (bit-and 0xff (aget ^bytes bytes (+ offset 2))) 8)
     (bit-and 0xff (aget ^bytes bytes (+ offset 3)))))

(defn- concatenated-pngs [bytes]
  (loop [offset 0
         images []]
    (if (= offset (alength ^bytes bytes))
      images
      (do
        (when-not (bytes-match? bytes offset png-signature)
          (throw (errors/raise! "Source gallery emitted invalid PNG data"
                                {:type ::invalid-gallery-output})))
        (let [end
              (loop [chunk-offset (+ offset 8)]
                (when (> (+ chunk-offset 12) (alength ^bytes bytes))
                  (throw (errors/raise! "Source gallery PNG is truncated"
                                        {:type ::invalid-gallery-output})))
                (let [length (unsigned-int-at bytes chunk-offset)
                      chunk-end (+ chunk-offset 12 length)]
                  (when (> chunk-end (alength ^bytes bytes))
                    (throw (errors/raise! "Source gallery PNG is truncated"
                                          {:type ::invalid-gallery-output})))
                  (if (= [73 69 78 68]
                         (mapv #(bit-and 0xff (aget ^bytes bytes %))
                               (range (+ chunk-offset 4) (+ chunk-offset 8))))
                    chunk-end
                    (recur chunk-end))))]
          (recur end (conj images (Arrays/copyOfRange bytes offset end))))))))

(defn- consume-gallery-png! [render-spec frame-index final-png consume-frame!]
  (let [{:keys [width height]} render-spec
        image (ImageIO/read (ByteArrayInputStream. final-png))]
    (when-not (and image (= width (.getWidth image))
                   (= height (.getHeight image)))
      (throw (errors/raise! "Source gallery frame dimensions are invalid"
                            {:type ::invalid-gallery-output})))
    (consume-frame! frame-index final-png)))

(declare monitored-pipe-output caused-by?)

(defn- render-composite-gallery-with-ffmpeg!
  [ffmpeg render-spec source-stream! overlays consume-frame!]
  (let [{:keys [width height]} render-spec
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
          (try
            (with-open [source-output (.getOutputStream process)]
              (source-stream!
               (monitored-pipe-output source-output source-pipe-write-error)))
            (catch Throwable error
              (reset! source-error error)))
          (let [exit-status (.waitFor process)
                output @stdout
                expected-pipe-closure?
                (and @source-error
                     (caused-by? @source-error @source-pipe-write-error))]
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

(defn- write-pipe! [stream-fn output result]
  (try
    (stream-fn output)
    (deliver result nil)
    (catch Throwable error
      (deliver result error))))

(defn- record-pipe-write-error! [write-error error]
  (compare-and-set! write-error nil error)
  (throw error))

(defn- monitored-pipe-output [^OutputStream output write-error]
  (proxy [OutputStream] []
    (write
      ([value]
       (try
         (if (bytes? value)
           (.write output ^bytes value)
           (.write output (int value)))
         (catch IOException error
           (record-pipe-write-error! write-error error))))
      ([buffer offset length]
       (try
         (.write output ^bytes buffer (int offset) (int length))
         (catch IOException error
           (record-pipe-write-error! write-error error)))))
    (flush []
      (try
        (.flush output)
        (catch IOException error
          (record-pipe-write-error! write-error error))))))

(defn- caused-by? [error cause]
  (loop [current error]
    (cond
      (nil? current) false
      (identical? current cause) true
      :else (recur (.getCause ^Throwable current)))))

(defn- remaining-ms [deadline-nanos]
  (max 0 (long (Math/ceil
                (/ (- deadline-nanos (System/nanoTime)) 1000000.0)))))

(defn- timeout-error [timeout-ms]
  (errors/raise! "FFmpeg compositing exceeded its deadline"
                 {:type ::composite-timeout
                  :failure-code "composition_timeout"
                  :stage "composition_encode"
                  :timeout-ms timeout-ms
                  :retryable true}))

(defn- delivered-error [result]
  (when (realized? result)
    (let [value @result]
      (when (instance? Throwable value) value))))

(defn- await-process! [^Process process overlay-result deadline-nanos timeout-ms]
  (loop []
    (when-let [overlay-error (delivered-error overlay-result)]
      (throw (errors/raise! "FFmpeg compositing failed"
                            {:type ::compositing-failed}
                            overlay-error)))
    (if-not (.isAlive process)
      (.exitValue process)
      (let [remaining (remaining-ms deadline-nanos)]
        (if (pos? remaining)
          (do
            (.waitFor process (min remaining 25) TimeUnit/MILLISECONDS)
            (recur))
          (throw (timeout-error timeout-ms)))))))

(defn- await-producer! [result deadline-nanos timeout-ms]
  (let [remaining (remaining-ms deadline-nanos)
        value (if (pos? remaining)
                (deref result remaining timed-out)
                timed-out)]
    (if (identical? timed-out value)
      (throw (timeout-error timeout-ms))
      value)))

(defn- stop-process! [^Process process]
  (when (.isAlive process)
    (.destroy process)
    (when-not (.waitFor process process-stop-grace-ms TimeUnit/MILLISECONDS)
      (.destroyForcibly process)
      (.waitFor process process-stop-grace-ms TimeUnit/MILLISECONDS))))

(defn- encode-composite-attempt!
  [ffmpeg render-spec heartbeat-path output-path source-stream! write-overlay!
   deadline-nanos timeout-ms]
  (let [directory (Files/createTempDirectory
                   "agg-composite-pipe-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        overlay-pipe (fifo-path! directory)
        process (.start (process-builder
                         (composite-command ffmpeg render-spec heartbeat-path
                                            overlay-pipe output-path)))
        captured (capture-output (.getInputStream process))
        source-result (promise)
        source-pipe-write-error (atom nil)
        overlay-result (promise)
        source-thread (Thread.
                       #(try
                          (with-open [output (.getOutputStream process)]
                            (write-pipe! source-stream!
                                         (monitored-pipe-output
                                          output source-pipe-write-error)
                                         source-result))
                          (catch Throwable error
                            (deliver source-result error)))
                       "agg-drive-source")
        overlay-thread (Thread.
                        #(try
                           (with-open [output (Files/newOutputStream
                                               overlay-pipe
                                               (make-array OpenOption 0))]
                             (write-pipe! write-overlay! output overlay-result))
                           (catch Throwable error
                             (deliver overlay-result error)))
                        "agg-overlay-pipe")]
    (.setDaemon source-thread true)
    (.setDaemon overlay-thread true)
    (.start source-thread)
    (.start overlay-thread)
    (try
      (let [exit-status (await-process! process overlay-result deadline-nanos
                                        timeout-ms)]
        (when-not (zero? exit-status)
          (throw (errors/raise! "FFmpeg compositing failed"
                                {:type ::compositing-failed
                                 :exit-status exit-status})))
        (let [source-error (await-producer! source-result deadline-nanos timeout-ms)
              overlay-error (await-producer! overlay-result deadline-nanos timeout-ms)]
          ;; The duration bound can make FFmpeg close stdin before Drive finishes.
          ;; Suppress only the exact write failure from that successful process.
          (when (and source-error
                     (not (caused-by? source-error
                                      @source-pipe-write-error)))
            (throw (errors/raise! "FFmpeg compositing failed"
                                  {:type ::compositing-failed
                                   :exit-status exit-status}
                                  source-error)))
          (when overlay-error
            (throw (errors/raise! "FFmpeg compositing failed"
                                  {:type ::compositing-failed
                                   :exit-status exit-status}
                                  overlay-error)))
          {:exit-status exit-status}))
      (catch Throwable error
        (stop-process! process)
        (throw error))
      (finally
        (deref captured process-stop-grace-ms "")
        (Files/deleteIfExists overlay-pipe)
        (Files/deleteIfExists directory)))))

(defn- encode-composite-with-ffmpeg!
  [ffmpeg render-spec heartbeat-path output-path source-stream! write-overlay!]
  (let [timeout-ms (long (or (:timeout-ms render-spec)
                             durable-composite-timeout-ms))
        deadline-nanos (+ (System/nanoTime) (* timeout-ms 1000000))]
    (try
      (encode-composite-attempt! ffmpeg render-spec heartbeat-path output-path
                                 source-stream! write-overlay!
                                 deadline-nanos timeout-ms)
      (catch clojure.lang.ExceptionInfo error
        ;; A source without an audio stream is still composable. Retry the same
        ;; non-seekable source with heartbeat-only audio; no source bytes are
        ;; retained between attempts. Both attempts share one deadline.
        (if (and (= "source+heartbeat" (:audio-mode render-spec))
                 (= ::compositing-failed (:type (ex-data error))))
          (encode-composite-attempt!
           ffmpeg
           (assoc render-spec :audio-mode "heartbeat-only")
           heartbeat-path output-path source-stream! write-overlay!
           deadline-nanos timeout-ms)
          (throw error))))))

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
                   (str/includes? (:format_name format) expected-container))
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
