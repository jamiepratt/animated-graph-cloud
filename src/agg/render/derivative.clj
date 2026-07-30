(ns agg.render.derivative
  (:require [agg.errors :as errors]
            [agg.render.process :as process]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.io RandomAccessFile)
           (java.lang ProcessBuilder$Redirect)
           (java.net URI)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.util.concurrent TimeUnit)))

(def profile-v1
  {:version "h264-aac-1080p25-v1"
   :container {:format "mp4" :fast-start? true}
   :video {:codec "h264"
           :encoder "libx264"
           :profile "high"
           :level "4.0"
           :pixel-format "yuv420p"
           :fps 25
           :max-width 1920
           :max-height 1080
           :upscale? false
           :crf 23
           :preset "fast"
           :max-rate-bps 4000000
           :buffer-size-bps 8000000
           :gop-frames 50
           :scene-cut-keyframes? false}
   :audio {:codec "aac"
           :profile "aac_low"
           :sample-rate 48000
           :channels 2
           :bitrate-bps 128000
           :silence-when-missing? true}
   :verify #{:audio :codec :dimensions :duration :fast-start
             :size :stream-layout}})

(def ^:private video-filter
  (str "scale="
       "w='if(gte(iw,ih),min(iw,1920),min(iw,1080))':"
       "h='if(gte(iw,ih),min(ih,1080),min(ih,1920))':"
       "force_original_aspect_ratio=decrease:"
       "force_divisible_by=2,"
       "setsar=1,"
       "fps=25"))

(def ^:private audio-filter
  "aresample=48000,aformat=sample_rates=48000:channel_layouts=stereo")

(def ^:private max-duration-seconds 480)
(def ^:private max-output-bytes (* 256 1024 1024))
(def ^:private max-wall-time-ms (* 900 1000))
(def ^:private process-stop-grace-ms 1000)

(defn- ffmpeg-seconds [seconds]
  (String/format java.util.Locale/ROOT "%.3f"
                 (to-array [(double seconds)])))

(defn- loopback-source-url? [source-url]
  (try
    (let [uri (URI. source-url)]
      (and (= "http" (.getScheme uri))
           (#{"127.0.0.1" "::1"} (.getHost uri))
           (pos? (.getPort uri))
           (.startsWith (.getPath uri) "/source/")))
    (catch Throwable _
      false)))

(defn encode-command
  "Builds the fixed derivative command for an identity-free loopback source."
  [{:keys [ffmpeg source-url source-duration-seconds source-has-audio?
           output-path]}]
  (when-not (and (string? ffmpeg)
                 (loopback-source-url? source-url)
                 (number? source-duration-seconds)
                 (pos? source-duration-seconds)
                 (or (string? output-path) (instance? Path output-path))
                 (boolean? source-has-audio?))
    (throw
     (errors/raise! "Derivative encode request is invalid"
                    {:type ::invalid-encode-request
                     :failure-code "invalid_derivative_measurement"})))
  (let [duration (ffmpeg-seconds source-duration-seconds)
        source-input [ffmpeg "-hide_banner" "-nostdin" "-loglevel" "error"
                      "-i" source-url]
        audio-input (when-not source-has-audio?
                      ["-f" "lavfi" "-t" duration
                       "-i" "anullsrc=r=48000:cl=stereo"])
        mapping (if source-has-audio?
                  ["-map" "0:v:0" "-map" "0:a:0"]
                  ["-map" "0:v:0" "-map" "1:a:0"])]
    (into []
          (concat
           source-input
           audio-input
           mapping
           ["-t" duration
            "-vf" video-filter
            "-r" "25"
            "-fps_mode" "cfr"
            "-c:v" "libx264"
            "-profile:v" "high"
            "-level:v" "4.0"
            "-pix_fmt" "yuv420p"
            "-preset" "fast"
            "-crf" "23"
            "-maxrate" "4000000"
            "-bufsize" "8000000"
            "-g" "50"
            "-keyint_min" "50"
            "-sc_threshold" "0"
            "-c:a" "aac"
            "-profile:a" "aac_low"
            "-ar" "48000"
            "-ac" "2"
            "-b:a" "128000"
            "-af" audio-filter
            "-movflags" "+faststart"
            "-f" "mp4"
            "-y" (str output-path)]))))

(defn predicted-maximum-output-bytes
  "Returns the fixed audio and video bitrate ceiling for a duration."
  [duration-seconds]
  (when-not (and (number? duration-seconds)
                 (not (neg? duration-seconds))
                 (<= duration-seconds max-duration-seconds))
    (throw
     (errors/raise! "Derivative duration exceeds its approved limit"
                    {:type ::duration-exceeded
                     :failure-code "source_duration_exceeded"
                     :limit max-duration-seconds
                     :reported duration-seconds})))
  (long
   (Math/ceil
    (/ (* (double duration-seconds)
          (+ (get-in profile-v1 [:video :max-rate-bps])
             (get-in profile-v1 [:audio :bitrate-bps])))
       8.0))))

(defn validate-runtime!
  "Accepts exact output and wall-time limits and rejects one over."
  [{:keys [elapsed-ms output-bytes] :as runtime}]
  (when-not (and (integer? elapsed-ms)
                 (not (neg? elapsed-ms))
                 (integer? output-bytes)
                 (not (neg? output-bytes)))
    (throw
     (errors/raise! "Derivative runtime measurement is invalid"
                    {:type ::invalid-runtime
                     :failure-code "invalid_derivative_measurement"})))
  (when (> elapsed-ms max-wall-time-ms)
    (throw
     (errors/raise! "Derivative encoding exceeded its deadline"
                    {:type ::timeout
                     :failure-code "derivative_timeout"
                     :timeout-ms max-wall-time-ms})))
  (when (> output-bytes max-output-bytes)
    (throw
     (errors/raise! "Derivative output exceeds its approved limit"
                    {:type ::output-exceeded
                     :failure-code "derivative_size_exceeded"
                     :limit max-output-bytes})))
  runtime)

(defn- stop-process! [^Process child]
  (when (.isAlive child)
    (.destroy child)
    (when-not (.waitFor child process-stop-grace-ms TimeUnit/MILLISECONDS)
      (.destroyForcibly child)
      (.waitFor child process-stop-grace-ms TimeUnit/MILLISECONDS))))

(defn- output-size [^Path output-path]
  (if (Files/exists output-path (make-array java.nio.file.LinkOption 0))
    (Files/size output-path)
    0))

(defn- run-encode!
  [command ^Path output-path timeout-ms cancelled?]
  (let [child
        (-> (process/process-builder command)
            (.redirectOutput ProcessBuilder$Redirect/DISCARD)
            (.start))
        started (System/nanoTime)]
    (try
      (loop []
        (let [elapsed-ms
              (quot (- (System/nanoTime) started) 1000000)
              current-output-bytes (output-size output-path)]
          (validate-runtime!
           {:elapsed-ms elapsed-ms
            :output-bytes current-output-bytes})
          (cond
            (cancelled?)
            (throw
             (errors/raise! "Derivative encoding was cancelled"
                            {:type ::cancelled
                             :reason "cancelled"}))

            (> elapsed-ms timeout-ms)
            (throw
             (errors/raise! "Derivative encoding exceeded its deadline"
                            {:type ::timeout
                             :failure-code "derivative_timeout"
                             :timeout-ms timeout-ms}))

            (.waitFor child 20 TimeUnit/MILLISECONDS)
            (when-not (zero? (.exitValue child))
              (throw
               (errors/raise! "Derivative media encoding failed"
                              {:type ::encode-failed
                               :failure-code "derivative_encode_failed"
                               :exit-status (.exitValue child)})))

            :else
            (recur))))
      (finally
        (stop-process! child)))))

(defn- parse-rate [rate]
  (when-let [[_ numerator denominator]
             (and (string? rate) (re-matches #"(\d+)/(\d+)" rate))]
    (let [denominator (parse-long denominator)]
      (when (pos? denominator)
        (/ (double (parse-long numerator)) denominator)))))

(defn- duration [probe]
  (some-> (get-in probe [:format :duration]) parse-double))

(defn- atom-order [^Path output-path]
  (with-open [file (RandomAccessFile. (.toFile output-path) "r")]
    (let [length (.length file)]
      (loop [offset 0
             atoms []]
        (if (> (+ offset 8) length)
          atoms
          (do
            (.seek file offset)
            (let [short-size (Integer/toUnsignedLong (.readInt file))
                  type-bytes (byte-array 4)
                  _ (.readFully file type-bytes)
                  type (String. type-bytes StandardCharsets/US_ASCII)
                  header-size (if (= 1 short-size) 16 8)
                  atom-size (cond
                              (= 0 short-size) (- length offset)
                              (= 1 short-size) (.readLong file)
                              :else short-size)]
              (if (or (< atom-size header-size)
                      (> (+ offset atom-size) length))
                (conj atoms "invalid")
                (recur (+ offset atom-size) (conj atoms type))))))))))

(defn- fitting-dimensions?
  [source-width source-height width height]
  (let [landscape? (>= source-width source-height)
        max-width (if landscape? 1920 1080)
        max-height (if landscape? 1080 1920)
        source-ratio (/ (double source-width) source-height)
        output-ratio (/ (double width) height)]
    (and (pos-int? width)
         (pos-int? height)
         (even? width)
         (even? height)
         (<= width source-width)
         (<= width max-width)
         (<= height source-height)
         (<= height max-height)
         (< (Math/abs (- source-ratio output-ratio)) 0.02))))

(defn- verification-failure! []
  (throw
   (errors/raise! "Derivative artifact verification failed"
                  {:type ::verification-failed
                   :failure-code "derivative_verification_failed"})))

(defn inspect-source!
  "Returns only the media shape needed by the encoder from its local proxy."
  [{:keys [ffprobe source-url timeout-ms]
    :or {ffprobe "ffprobe"
         timeout-ms max-wall-time-ms}}]
  (when-not (and (string? ffprobe)
                 (loopback-source-url? source-url)
                 (integer? timeout-ms)
                 (pos? timeout-ms)
                 (<= timeout-ms max-wall-time-ms))
    (throw
     (errors/raise! "Derivative source inspection request is invalid"
                    {:type ::invalid-inspection-request
                     :failure-code "invalid_derivative_measurement"})))
  (let [probe
        (try
          (json/read-str
           (process/run-captured-as!
            [ffprobe "-v" "quiet"
             "-show_entries" "stream=codec_type,width,height"
             "-of" "json" source-url]
            timeout-ms ::inspection-failed ::inspection-timeout)
           :key-fn keyword)
          (catch Throwable error
            (if (= ::inspection-timeout (:type (ex-data error)))
              (throw
               (errors/raise! "Derivative source inspection exceeded its deadline"
                              {:type ::timeout
                               :failure-code "derivative_timeout"
                               :timeout-ms timeout-ms}
                              error))
              (throw
               (errors/raise! "Derivative source media is invalid"
                              {:type ::invalid-source
                               :failure-code "derivative_source_not_renderable"}
                              error)))))
        videos (filterv #(= "video" (:codec_type %)) (:streams probe))
        audios (filterv #(= "audio" (:codec_type %)) (:streams probe))
        video (first videos)]
    (when-not (and video
                   (pos-int? (:width video))
                   (pos-int? (:height video)))
      (throw
       (errors/raise! "Derivative source media is invalid"
                      {:type ::invalid-source
                       :failure-code "derivative_source_not_renderable"})))
    {:width (:width video)
     :height (:height video)
     :audio? (boolean (seq audios))}))

(defn verify!
  "Verifies the complete fixed derivative contract and returns safe metadata."
  [{:keys [ffprobe output-path source-duration-seconds source-width
           source-height timeout-ms]
    :or {timeout-ms max-wall-time-ms}}]
  (let [output-path ^Path output-path
        output-bytes (output-size output-path)]
    (when-not (and (integer? timeout-ms)
                   (pos? timeout-ms)
                   (<= timeout-ms max-wall-time-ms)
                   (pos? output-bytes)
                   (<= output-bytes max-output-bytes))
      (verification-failure!))
    (let [probe
          (try
            (json/read-str
             (process/run-captured-as!
              [ffprobe "-v" "error"
               "-show_entries"
               (str "format=format_name,duration,size:"
                    "stream=index,codec_type,codec_name,profile,level,"
                    "pix_fmt,width,height,r_frame_rate,avg_frame_rate,"
                    "sample_rate,channels,channel_layout,duration")
               "-of" "json" (str output-path)]
              timeout-ms
              ::verification-tool-failed
              ::verification-tool-timeout)
             :key-fn keyword)
            (catch Throwable error
              (if (= ::verification-tool-timeout (:type (ex-data error)))
                (throw
                 (errors/raise! "Derivative encoding exceeded its deadline"
                                {:type ::timeout
                                 :failure-code "derivative_timeout"
                                 :timeout-ms timeout-ms}
                                error))
                (throw
                 (errors/raise! "Derivative artifact verification failed"
                                {:type ::verification-failed
                                 :failure-code "derivative_verification_failed"}
                                error)))))
          streams (:streams probe)
          videos (filterv #(= "video" (:codec_type %)) streams)
          audios (filterv #(= "audio" (:codec_type %)) streams)
          video (first videos)
          audio (first audios)
          actual-duration (duration probe)
          video-duration (some-> (:duration video) parse-double)
          audio-duration (some-> (:duration audio) parse-double)
          atoms (atom-order output-path)
          moov-index (.indexOf atoms "moov")
          mdat-index (.indexOf atoms "mdat")
          valid?
          (and (str/includes? (or (get-in probe [:format :format_name]) "")
                              "mp4")
               (= 2 (count streams))
               (= 1 (count videos))
               (= 1 (count audios))
               (= "h264" (:codec_name video))
               (= "High" (:profile video))
               (= 40 (:level video))
               (= "yuv420p" (:pix_fmt video))
               (= 25.0 (parse-rate (:r_frame_rate video)))
               (= 25.0 (parse-rate (:avg_frame_rate video)))
               (fitting-dimensions?
                source-width source-height (:width video) (:height video))
               (= "aac" (:codec_name audio))
               (= "LC" (:profile audio))
               (= "48000" (:sample_rate audio))
               (= 2 (:channels audio))
               (= "stereo" (:channel_layout audio))
               (number? actual-duration)
               (number? video-duration)
               (number? audio-duration)
               (<= (Math/abs
                    (- (double source-duration-seconds) actual-duration))
                   0.08)
               (<= (Math/abs
                    (- (double source-duration-seconds) video-duration))
                   0.08)
               (<= (Math/abs
                    (- (double source-duration-seconds) audio-duration))
                   0.08)
               (<= actual-duration max-duration-seconds)
               (<= 0 moov-index)
               (< moov-index mdat-index))]
      (when-not valid?
        (verification-failure!))
      {:output-path output-path
       :content-type "video/mp4"
       :output-bytes output-bytes
       :duration-seconds actual-duration
       :video {:codec "h264"
               :profile "High"
               :level 40
               :pixel-format "yuv420p"
               :width (:width video)
               :height (:height video)
               :fps "25/1"}
       :audio {:codec "aac"
               :profile "LC"
               :sample-rate 48000
               :channels 2}
       :fast-start? true})))

(defn encode!
  "Encodes and verifies one bounded derivative from an opaque loopback proxy."
  [{:keys [output-path source-duration-seconds source-width source-height
           timeout-ms cancelled? stage!]
    :or {timeout-ms max-wall-time-ms
         cancelled? (constantly false)
         stage! (fn [_])}
    :as request}]
  (let [output-path (if (instance? Path output-path)
                      output-path
                      (Path/of ^String output-path (make-array String 0)))
        started (System/nanoTime)]
    (when-not (and (pos-int? source-width)
                   (pos-int? source-height)
                   (number? source-duration-seconds)
                   (pos? source-duration-seconds)
                   (<= source-duration-seconds max-duration-seconds)
                   (integer? timeout-ms)
                   (pos? timeout-ms)
                   (<= timeout-ms max-wall-time-ms)
                   (ifn? cancelled?)
                   (ifn? stage!))
      (throw
       (errors/raise! "Derivative encode request is invalid"
                      {:type ::invalid-encode-request
                       :failure-code "invalid_derivative_measurement"})))
    (predicted-maximum-output-bytes source-duration-seconds)
    (Files/deleteIfExists output-path)
    (try
      (stage! :ffmpeg-started)
      (run-encode!
       (encode-command (assoc request :output-path output-path))
       output-path timeout-ms cancelled?)
      (stage! :ffmpeg-completed)
      (when (cancelled?)
        (throw
         (errors/raise! "Derivative encoding was cancelled"
                        {:type ::cancelled
                         :reason "cancelled"})))
      (let [elapsed-ms
            (quot (- (System/nanoTime) started) 1000000)
            _ (when (>= elapsed-ms timeout-ms)
                (throw
                 (errors/raise! "Derivative encoding exceeded its deadline"
                                {:type ::timeout
                                 :failure-code "derivative_timeout"
                                 :timeout-ms timeout-ms})))
            result
            (do
              (stage! :verification-started)
              (let [verified
                    (verify!
                     (assoc request
                            :output-path output-path
                            :timeout-ms (- timeout-ms elapsed-ms)))]
                (stage! :verification-completed)
                verified))
            total-elapsed-ms
            (quot (- (System/nanoTime) started) 1000000)]
        (validate-runtime!
         {:elapsed-ms total-elapsed-ms
          :output-bytes (output-size output-path)})
        (when (> total-elapsed-ms timeout-ms)
          (throw
           (errors/raise! "Derivative encoding exceeded its deadline"
                          {:type ::timeout
                           :failure-code "derivative_timeout"
                           :timeout-ms timeout-ms})))
        result)
      (catch Throwable error
        (Files/deleteIfExists output-path)
        (throw error)))))
