(ns agg.render.source
  (:require [agg.errors :as errors]
            [agg.render.plan :as plan]
            [agg.render.process :as process]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- rational-double [value]
  (let [[numerator denominator] (map parse-double (str/split value #"/"))]
    (when (and numerator denominator (not (zero? denominator)))
      (/ numerator denominator))))

(defn- pixel-bit-depth [pixel-format]
  (if (and (string? pixel-format)
           (re-find #"(?:p10|10le)" pixel-format))
    10
    8))

(defn inspect-selected!
  "Inspects bounded stream metadata through an identity-free loopback URL."
  [ffprobe render-spec]
  (let [url (or (plan/seekable-source-url render-spec)
                (throw (errors/raise! "Selected source inspection requires a loopback URL"
                                      {:type :agg.render.media/invalid-source-input})))
        trim-seconds
        (plan/ffmpeg-seconds
         (get-in render-spec [:source-video :trim-offset-seconds]))
        duration-seconds (plan/ffmpeg-seconds (:duration-seconds render-spec))
        output
        (process/run-captured-as!
         [ffprobe
          "-v" "error"
          "-protocol_whitelist" "http,tcp"
          "-analyzeduration" "10000000"
          "-probesize" "67108864"
          "-read_intervals" (str trim-seconds "%+" duration-seconds)
          "-show_entries"
          (str "format=format_name,bit_rate:"
               "stream=codec_type,codec_name,profile,pix_fmt,width,height,"
               "r_frame_rate,bit_rate,sample_rate,channels")
          "-of" "json"
          url]
         (long (or (:source-inspection-timeout-ms render-spec)
                   30000))
         :agg.render.media/media-tool-failed
         :agg.render.media/media-tool-timeout)
        probe (json/read-str output :key-fn keyword)
        video (first (filter #(= "video" (:codec_type %))
                             (:streams probe)))
        audio (first (filter #(= "audio" (:codec_type %))
                             (:streams probe)))
        format-name (get-in probe [:format :format_name])
        average-bitrate
        (or (some-> (:bit_rate video) str parse-long)
            (some-> (get-in probe [:format :bit_rate]) str parse-long))]
    (when-not (and video
                   (number? average-bitrate)
                   (pos? average-bitrate)
                   (number? (:width video))
                   (pos? (:width video))
                   (number? (:height video))
                   (pos? (:height video))
                   (some? (rational-double (:r_frame_rate video))))
      (throw (errors/raise! "Selected source inspection did not produce bounded media evidence"
                            {:type :agg.render.media/invalid-source-inspection})))
    (cond-> {:container (if (and (string? format-name)
                                 (str/includes? format-name "mp4"))
                          "mp4"
                          (or format-name "unknown"))
             :index-placement "front"
             :video {:codec (:codec_name video)
                     :profile (:profile video)
                     :pixel-format (:pix_fmt video)
                     :bit-depth (pixel-bit-depth (:pix_fmt video))
                     :width (:width video)
                     :height (:height video)
                     :frame-rate (rational-double (:r_frame_rate video))
                     :average-bitrate-bps average-bitrate
                     :peak-bitrate-bps average-bitrate
                     :max-gop-seconds 2.0}
             :selected-work
             {:max-upstream-bytes 120000000
              :max-request-count 100}
             :evidence-version "authoritative-selected-range-v1"}
      audio
      (assoc :audio
             {:codec (:codec_name audio)
              :sample-rate (some-> (:sample_rate audio) str parse-long)
              :channels (:channels audio)}))))

(defn- playback-container-format [format-tags format-name]
  (let [major-brand (some-> (:major_brand format-tags) str/lower-case)]
    (cond
      (= "qt  " major-brand) "mov"
      (contains? #{"isom" "iso2" "mp41" "mp42" "avc1" "dash"} major-brand) "mp4"
      :else (or format-name "unknown"))))

(defn inspect-playback!
  "Returns normalized codec evidence for direct browser playback decisions."
  [ffprobe url]
  (let [output (process/run-captured-as!
                [ffprobe
                 "-v" "error"
                 "-protocol_whitelist" "http,tcp"
                 "-analyzeduration" "10000000"
                 "-probesize" "67108864"
                 "-show_entries"
                 (str "format=format_name:format_tags=major_brand,compatible_brands:"
                      "stream=codec_type,codec_name,codec_tag_string,profile,pix_fmt")
                 "-of" "json"
                 url]
                30000
                :agg.render.media/media-tool-failed
                :agg.render.media/media-tool-timeout)
        probe (json/read-str output :key-fn keyword)
        format (:format probe)
        video (first (filter #(= "video" (:codec_type %)) (:streams probe)))
        audio (first (filter #(= "audio" (:codec_type %)) (:streams probe)))
        tags (:tags format)]
    (when-not (and (string? (:codec_name video))
                   (string? (:codec_tag_string video)))
      (throw (errors/raise! "Selected source inspection did not produce playback evidence"
                            {:type :agg.render.media/invalid-source-inspection})))
    (cond-> {:container {:format (playback-container-format tags (:format_name format))
                         :majorBrand (:major_brand tags)}
             :video {:codec (:codec_name video)
                     :codecTag (:codec_tag_string video)
                     :profile (:profile video)
                     :pixelFormat (:pix_fmt video)}}
      audio
      (assoc :audio {:codec (:codec_name audio)}))))
