(ns agg.transparent-alpha-benchmark
  (:require [agg.render.media :as media]
            [agg.render.spec :as spec]
            [agg.renderer.main :as renderer]
            [clojure.data.json :as json])
  (:import (java.nio.file Files Path)
           (java.time Instant ZoneId)))

(def ^:private minimum-useful-size-reduction-percent 5.0)
(def ^:private maximum-render-time-ratio 1.25)
(def ^:private render-time-slack-seconds 1.0)

(defn- benchmark-spec [duration-seconds alpha-bits ^Path output]
  (assoc (spec/with-duration (spec/preset "1080p25") duration-seconds)
         :section-start-at Instant/EPOCH
         :display-time-zone (ZoneId/of "UTC")
         :future-trace-opacity-percent 25
         :transparent-alpha-bits alpha-bits
         :telemetry [{:seconds 0.0 :heart-rate 92.0}
                     {:seconds (* duration-seconds 0.2) :heart-rate 148.0}
                     {:seconds (* duration-seconds 0.4) :heart-rate 104.0}
                     {:seconds (* duration-seconds 0.6) :heart-rate 172.0}
                     {:seconds (* duration-seconds 0.8) :heart-rate 118.0}
                     {:seconds duration-seconds :heart-rate 156.0}]
         :spo2 [{:seconds 0.0 :spo2 98.0}
                {:seconds (* duration-seconds 0.5) :spo2 91.0}
                {:seconds duration-seconds :spo2 97.0}]
         :timer {:start-seconds (* duration-seconds 0.25)
                 :end-seconds (* duration-seconds 0.75)}
         :output-path output
         :profile? false))

(defn- render-mode! [directory duration-seconds alpha-bits]
  (let [output (.resolve ^Path directory
                         (str "transparent-alpha-" alpha-bits ".mov"))
        result (renderer/render!
                (benchmark-spec duration-seconds alpha-bits output)
                {:video-encoder (media/ffmpeg-video-encoder)})
        video-stream (first
                      (filter #(= "video" (:codec_type %))
                              (get-in result [:media :ffprobe :streams])))]
    {:alphaBits alpha-bits
     :wallSeconds (:wall-seconds result)
     :outputBytes (:output-bytes result)
     :sha256 (:sha256 result)
     :codec (get-in result [:media :video :codec])
     :profile (get-in result [:media :video :profile])
     :codecTag (:codec_tag_string video-stream)
     :pixelFormat (get-in result [:media :video :pixel-format])
     :reportedAlphaBits (get-in result [:media :video :alpha-bits])}))

(defn- parse-duration [value]
  (let [duration (parse-double value)]
    (when-not (and duration
                   (<= 1.0 duration 30.0)
                   (= (* duration 25.0)
                      (Math/rint (* duration 25.0))))
      (throw
       (ex-info
        "Duration must be 1 to 30 seconds and align to whole 25 fps frames"
        {:duration value})))
    duration))

(defn- round-to [value digits]
  (let [scale (Math/pow 10.0 digits)]
    (/ (Math/round (* (double value) scale)) scale)))

(defn -main [& [duration-value]]
  (let [duration-seconds (parse-duration (or duration-value "4"))
        directory (Files/createTempDirectory
                   "agg-transparent-alpha-benchmark-"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [sixteen (render-mode! directory duration-seconds 16)
            eight (render-mode! directory duration-seconds 8)
            size-reduction-percent
            (* 100.0
               (/ (- (:outputBytes sixteen) (:outputBytes eight))
                  (double (:outputBytes sixteen))))
            time-ratio (/ (:wallSeconds eight) (:wallSeconds sixteen))
            maximum-eight-seconds
            (+ (* maximum-render-time-ratio (:wallSeconds sixteen))
               render-time-slack-seconds)
            report
            {:preset "1080p25"
             :durationSeconds duration-seconds
             :executionOrder [16 8]
             :modes [sixteen eight]
             :sizeReductionPercent (round-to size-reduction-percent 2)
             :renderTimeRatio (round-to time-ratio 3)
             :acceptance
             {:minimumSizeReductionPercent
              minimum-useful-size-reduction-percent
              :maximumRenderTimeRatio maximum-render-time-ratio
              :renderTimeSlackSeconds render-time-slack-seconds
              :usefulSizeReduction
              (>= size-reduction-percent
                  minimum-useful-size-reduction-percent)
              :noMaterialRenderTimeRegression
              (<= (:wallSeconds eight) maximum-eight-seconds)}}]
        (println (json/write-str report))
        (when-not
         (and (get-in report [:acceptance :usefulSizeReduction])
              (get-in report
                      [:acceptance :noMaterialRenderTimeRegression]))
          (throw (ex-info "Transparent alpha benchmark acceptance failed"
                          report))))
      (finally
        (doseq [candidate (reverse (file-seq (.toFile directory)))]
          (Files/deleteIfExists (.toPath candidate)))))))
