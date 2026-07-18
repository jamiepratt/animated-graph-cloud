(ns agg.render.media
  (:require [agg.errors :as errors]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.io RandomAccessFile)
           (java.nio.file Files OpenOption Path StandardOpenOption)))

(defprotocol VideoEncoder
  (encode! [encoder render-spec audio-path output-path write-frames!])
  (verify! [encoder render-spec output-path]))

(defprotocol CompositeEncoder
  (encode-composite! [encoder render-spec heartbeat-path output-path
                      source-stream! write-overlay!])
  (verify-composite! [encoder render-spec output-path]))

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

(defn extract-preview!
  "Extracts one PNG from a completed local composite without retaining frames."
  ([ffmpeg render-spec input-path output]
   (let [builder (doto (ProcessBuilder. ^java.util.List
                                        [ffmpeg "-hide_banner" "-nostdin"
                                         "-loglevel" "error"
                                         "-ss" (str (/ (double (:duration-seconds
                                                                   render-spec))
                                                          2.0))
                                         "-i" (str input-path)
                                         "-frames:v" "1"
                                         "-f" "image2pipe"
                                         "-vcodec" "png" "pipe:1"])
                    (.redirectErrorStream false))
         process (.start builder)
         stdout (future
                  (with-open [input (.getInputStream process)]
                    (.readAllBytes input)))
         stderr (future
                 (with-open [input (.getErrorStream process)]
                   (slurp input)))
         status (.waitFor process)]
     (when-not (zero? status)
       @stderr
       (throw (errors/raise! "Preview extraction failed"
                       {:type ::preview-extraction-failed
                        :exit-status status})))
     (.write ^java.io.OutputStream output ^bytes @stdout)
     (.flush ^java.io.OutputStream output)
     {:width (:width render-spec) :height (:height render-spec)
      :at-seconds (/ (double (:duration-seconds render-spec)) 2.0)})))

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

(defn composite-command
  "Returns the bounded FFmpeg command shape used by the compositing path."
  [ffmpeg render-spec heartbeat-path overlay-pipe output-path]
  (let [{:keys [width height fps duration-seconds output-format fit-mode audio-mode]} render-spec
        fit-filter (if (= "crop" fit-mode)
                     (format "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1"
                             width height width height)
                     (format "scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1"
                             width height width height))
        video-filter (str "[0:v]" fit-filter "[base];"
                          "[1:v]format=rgba[overlay];"
                          "[base][overlay]overlay=0:0:format=auto:eof_action=endall[v]")
        audio-filter (case audio-mode
                       "heartbeat-only"
                       "[2:a]aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,alimiter=limit=0.95[a]"
                       "source-only"
                       "[0:a]aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,alimiter=limit=0.95[a]"
                       (str "[0:a]aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,volume=0.5[src];"
                            "[2:a]aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,volume=0.5[beat];"
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

(defn- write-pipe! [stream-fn output result]
  (try
    (stream-fn output)
    (deliver result nil)
    (catch Throwable error
      (deliver result error))))

(defn- encode-composite-attempt!
  [ffmpeg render-spec heartbeat-path output-path source-stream! write-overlay!]
  (let [directory (Files/createTempDirectory
                   "agg-composite-pipe-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        overlay-pipe (fifo-path! directory)
        process (.start (process-builder
                         (composite-command ffmpeg render-spec heartbeat-path
                                            overlay-pipe output-path)))
        source-result (promise)
        overlay-result (promise)
        source-thread (Thread.
                       #(try
                          (with-open [output (.getOutputStream process)]
                            (write-pipe! source-stream! output source-result))
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
    (let [exit-status (.waitFor process)
          source-error @source-result
          overlay-error @overlay-result]
      (try
        (when (and (not (zero? exit-status))
                   (or source-error overlay-error))
          (throw (errors/raise! "FFmpeg compositing failed"
                          {:type ::compositing-failed
                           :exit-status exit-status})))
        (when-not (zero? exit-status)
          (throw (errors/raise! "FFmpeg compositing failed"
                          {:type ::compositing-failed
                           :exit-status exit-status})))
        {:exit-status exit-status}
        (finally
          (Files/deleteIfExists overlay-pipe)
          (Files/deleteIfExists directory))))))

(defn- encode-composite-with-ffmpeg!
  [ffmpeg render-spec heartbeat-path output-path source-stream! write-overlay!]
  (try
    (encode-composite-attempt! ffmpeg render-spec heartbeat-path output-path
                               source-stream! write-overlay!)
    (catch clojure.lang.ExceptionInfo error
      ;; A source without an audio stream is still composable. Retry the same
      ;; non-seekable source with heartbeat-only audio; no source bytes are
      ;; retained between attempts.
      (if (and (= "source+heartbeat" (:audio-mode render-spec))
               (= ::compositing-failed (:type (ex-data error))))
        (encode-composite-attempt!
         ffmpeg
         (assoc render-spec :audio-mode "heartbeat-only")
         heartbeat-path output-path source-stream! write-overlay!)
        (throw error)))))

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
      (verified-composite-media render-spec probe))))

(defn ffmpeg-video-encoder
  ([] (ffmpeg-video-encoder "ffmpeg" "ffprobe"))
  ([ffmpeg ffprobe]
   (->FfmpegVideoEncoder ffmpeg ffprobe)))

(defn ffmpeg-media
  "Compatibility constructor; prefer ffmpeg-video-encoder."
  ([] (ffmpeg-video-encoder))
  ([ffmpeg ffprobe] (ffmpeg-video-encoder ffmpeg ffprobe)))
