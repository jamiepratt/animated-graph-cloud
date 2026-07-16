(ns agg.render.media
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.io RandomAccessFile)
           (java.nio.file Path)))

(defprotocol Media
  (encode! [media render-spec audio-path output-path write-frames!])
  (verify! [media render-spec output-path]))

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
      (throw (ex-info "Media tool failed"
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
          (throw (ex-info "FFmpeg encoding failed"
                          {:type ::encoding-failed
                           :exit-status exit-status})))
        {:exit-status exit-status})
      (catch Throwable error
        (.destroyForcibly process)
        @captured
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
                (throw (ex-info "Invalid MOV atom size" {:type ::invalid-container})))
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
      (throw (ex-info "Encoded media does not satisfy the renderer contract"
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

(defrecord FfmpegMedia [ffmpeg ffprobe]
  Media
  (encode! [_ render-spec audio-path output-path write-frames!]
    (encode-with-ffmpeg! ffmpeg render-spec audio-path output-path write-frames!))
  (verify! [_ render-spec output-path]
    (let [probe-output (run-captured!
                        [ffprobe
                         "-v" "error"
                         "-show_entries"
                         (str "format=format_name,duration,size,probe_score:"
                              "stream=index,codec_type,codec_name,profile,codec_tag_string,"
                              "width,height,pix_fmt,r_frame_rate,sample_rate,channels,channel_layout,bit_rate")
                         "-of" "json"
                         (str output-path)])
          probe (json/read-str probe-output :key-fn keyword)]
      (verified-media render-spec probe (top-level-atoms output-path)))))

(defn ffmpeg-media
  ([] (ffmpeg-media "ffmpeg" "ffprobe"))
  ([ffmpeg ffprobe]
   (->FfmpegMedia ffmpeg ffprobe)))
