(ns agg.render.plan
  (:require [agg.errors :as errors]
            [clojure.string :as str])
  (:import (java.time Instant)))

(def prores-4444-contract
  {:encoder "prores_ks"
   :profile 4
   :encoder-input-pixel-format "yuva444p10le"
   :decoded-pixel-format "yuva444p12le"})

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

(defn timing-metadata
  "Returns the versioned UTC timeline represented by a rendered artifact."
  [{:keys [section-start-at duration-seconds display-time-zone]}]
  (when (instance? Instant section-start-at)
    {"com.alphacompose.timing.version" "1"
     "com.alphacompose.timing.start_utc" (str section-start-at)
     "com.alphacompose.timing.end_utc"
     (str (.plusNanos ^Instant section-start-at
                      (long (* duration-seconds 1000000000))))
     "com.alphacompose.timing.time_zone" (str display-time-zone)}))

(defn timing-metadata-arguments [render-spec]
  (when-let [metadata (timing-metadata render-spec)]
    (mapcat (fn [[key value]] ["-metadata" (str key "=" value)]) metadata)))

(defn fit-filter [{:keys [width height fit-mode]}]
  (if (= "crop" fit-mode)
    (format "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1"
            width height width height)
    (format "scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1"
            width height width height)))

(defn ffmpeg-seconds [seconds]
  (Double/toString (double (or seconds 0))))

(def ^:private loopback-source-pattern
  #"http://127\.0\.0\.1:\d+/source/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn seekable-source-url [render-spec]
  (when-let [url (get-in render-spec [:source-video :input-url])]
    (when-not (and (string? url)
                   (re-matches loopback-source-pattern url))
      (throw (errors/raise! "Renderer source URL is invalid"
                            {:type :agg.render.media/invalid-source-input})))
    url))

(defn source-input-args [render-spec]
  (if-let [url (seekable-source-url render-spec)]
    ["-protocol_whitelist" "http,tcp"
     "-ss" (ffmpeg-seconds
            (get-in render-spec [:source-video :trim-offset-seconds]))
     "-i" url]
    ["-i" "pipe:0"]))

(defn composite-command
  "Returns the bounded FFmpeg command shape used by the compositing path."
  [ffmpeg render-spec heartbeat-path overlay-pipe output-path]
  (let [{:keys [width height fps duration-seconds output-format audio-mode]} render-spec
        source-trim-seconds
        (ffmpeg-seconds
         (get-in render-spec [:source-video :trim-offset-seconds]))
        trim-seconds (if (seekable-source-url render-spec)
                       (ffmpeg-seconds 0)
                       source-trim-seconds)
        video-filter (str "[0:v]trim=start=" trim-seconds
                          ",setpts=PTS-STARTPTS,"
                          (fit-filter render-spec) "[base];"
                          "[1:v]format=rgba[overlay];"
                          "[base][overlay]overlay=0:0:format=auto:eof_action=endall[v]")
        audio-filter (case audio-mode
                       "heartbeat-only"
                       "[2:a]aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,alimiter=limit=0.95[a]"
                       "source-only"
                       (str "[0:a]atrim=start=" trim-seconds
                            ",asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,alimiter=limit=0.95[a]")
                       (str "[0:a]atrim=start=" trim-seconds
                            ",asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo,aresample=48000,volume=0.5[src];"
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
                      ["-f" "mov" "-movflags" "+use_metadata_tags"]
                      ["-f" "mp4" "-movflags" "+faststart+use_metadata_tags"])]
    (into [ffmpeg "-hide_banner" "-nostdin" "-loglevel" "error"]
          (concat
           (source-input-args render-spec)
           ["-f" "rawvideo"
            "-pixel_format" "rgba"
            "-video_size" (str width "x" height)
            "-framerate" (str fps)
            "-i" (str overlay-pipe)
            "-i" (str heartbeat-path)
            "-filter_complex" (str video-filter ";" audio-filter)
            "-map" "[v]"
            "-map" "[a]"
            "-t" (ffmpeg-seconds duration-seconds)
            "-r" (str fps)
            "-ar" "48000"
            "-ac" "2"
            "-c:a" "aac"
            "-b:a" (:target-bitrate aac-lc-contract)]
           video-args format-args (timing-metadata-arguments render-spec)
           ["-y" (str output-path)]))))

(defn composite-gallery-command
  "Returns one bounded source-decode command for all selected output frames."
  [ffmpeg render-spec frame-indexes overlay-path]
  (let [{:keys [width height fps]} render-spec
        seekable? (some? (seekable-source-url render-spec))
        trim-frames
        (get-in render-spec [:source-video :trim-offset-frames] 0)
        source-frame-indexes (if seekable?
                               frame-indexes
                               (mapv #(+ trim-frames %) frame-indexes))
        selection (str/join "+" (map #(format "eq(n\\,%d)" %)
                                     source-frame-indexes))
        video-filter
        (str "[0:v]" (fit-filter render-spec) ",fps=" fps
             ",select='" selection "',setpts=N/(" fps "*TB)[base];"
             "[1:v]format=rgba[overlay];"
             "[base][overlay]overlay=0:0:format=auto:eof_action=endall[v]")]
    (into [ffmpeg "-hide_banner" "-nostdin" "-loglevel" "error"]
          (concat
           (source-input-args render-spec)
           ["-f" "rawvideo"
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
            "pipe:1"]))))
