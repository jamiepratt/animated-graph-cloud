(ns agg.render-test
  (:require [agg.render.audio :as audio]
            [agg.contracts.render :as contract]
            [agg.jobs.lifecycle :as jobs]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.preview.core :as preview]
            [agg.render.spec :as spec]
            [agg.render.watermark :as watermark]
            [agg.renderer.main :as renderer]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.awt Color)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream ByteArrayOutputStream IOException OutputStream)
           (java.nio.file Files OpenOption)
           (java.security MessageDigest)
           (java.time Instant ZoneId)
           (java.util HexFormat)
           (javax.imageio ImageIO)))

(defn- sha256-bytes [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256") value)))

(defn- golden-platform []
  (let [os-name (str/lower-case (System/getProperty "os.name" ""))]
    (cond
      (str/includes? os-name "mac") "mac"
      (str/includes? os-name "linux") "linux"
      :else (throw (ex-info "No golden render fixture for this platform"
                            {:os-name os-name})))))

(defn- golden-resource [name]
  (str "fixtures/golden/" name "-" (golden-platform) ".sha256"))

(defn- with-test-clock [render-spec]
  (merge {:section-start-at Instant/EPOCH
          :display-time-zone (ZoneId/of "UTC")}
         render-spec))

(defn- streamed-rgba [render-spec]
  (let [output (ByteArrayOutputStream.)]
    {:result (frames/stream! (with-test-clock render-spec) output)
     :rgba (.toByteArray output)}))

(defn- rendered-png [render-spec frame-index]
  (let [output (ByteArrayOutputStream.)]
    (frames/render-frame-png! frames/java2d-frame-renderer
                              (with-test-clock render-spec) frame-index output)
    (ImageIO/read (ByteArrayInputStream. (.toByteArray output)))))

(defn- heart-rate-red? [^Color color]
  (and (= 255 (.getRed color))
       (<= 50 (.getGreen color) 60)
       (<= 75 (.getBlue color) 90)
       (pos? (.getAlpha color))))

(defn- heart-rate-red-ys [^BufferedImage image x top bottom]
  (->> (range top (inc bottom))
       (filter (fn [y]
                 (heart-rate-red? (Color. (.getRGB image x y) true))))
       set))

(defn- heart-rate-red-rgba-ys
  [^bytes rgba width height frame-index x top bottom]
  (let [frame-offset (* frame-index width height 4)]
    (->> (range top (inc bottom))
         (filter
          (fn [y]
            (let [offset (+ frame-offset (* (+ x (* y width)) 4))
                  color (Color. (bit-and 0xff (aget rgba offset))
                                (bit-and 0xff (aget rgba (inc offset)))
                                (bit-and 0xff (aget rgba (+ offset 2)))
                                (bit-and 0xff (aget rgba (+ offset 3))))]
              (heart-rate-red? color))))
         set)))

(defn- heart-rate-red-alphas [^BufferedImage image x top bottom]
  (->> (range top (inc bottom))
       (keep (fn [y]
               (let [color (Color. (.getRGB image x y) true)]
                 (when (heart-rate-red? color)
                   (.getAlpha color)))))
       set))

(defn- spo2-cyan? [^Color color]
  (and (<= 50 (.getRed color) 55)
       (<= 195 (.getGreen color) 205)
       (<= 230 (.getBlue color) 240)
       (pos? (.getAlpha color))))

(defn- color-ys [^BufferedImage image x top bottom color?]
  (->> (range top (inc bottom))
       (filter (fn [y]
                 (color? (Color. (.getRGB image x y) true))))
       set))

(defn- executable-script! [contents]
  (let [path (Files/createTempFile
              "agg-media-test-" ".sh"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/writeString path contents (make-array OpenOption 0))
    (when-not (.setExecutable (.toFile path) true)
      (throw (IOException. "Could not make the media test script executable")))
    path))

(defn- png-bytes [width height opaque?]
  (let [image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        output (ByteArrayOutputStream.)]
    (when opaque?
      (let [graphics (.createGraphics image)]
        (try
          (.setColor graphics Color/BLACK)
          (.fillRect graphics 0 0 width height)
          (finally (.dispose graphics)))))
    (ImageIO/write image "png" output)
    (.toByteArray output)))

(defn- short-source-reader-script [exit-status]
  (str "#!/bin/sh\n"
       "input_number=0\n"
       "while [ \"$#\" -gt 0 ]; do\n"
       "  if [ \"$1\" = \"-i\" ]; then\n"
       "    shift\n"
       "    input_number=$((input_number + 1))\n"
       "    if [ \"$input_number\" -eq 2 ]; then\n"
       "      overlay=$1\n"
       "      break\n"
       "    fi\n"
       "  fi\n"
       "  shift\n"
       "done\n"
       "head -c 1 \"$overlay\" >/dev/null &\n"
       "overlay_reader=$!\n"
       "head -c 1 >/dev/null\n"
       "wait \"$overlay_reader\"\n"
       "exit " exit-status "\n"))

(defn- gallery-output-script [png-path exit-status]
  (str "#!/bin/sh\n"
       "cat >/dev/null\n"
       "cat \"" png-path "\"\n"
       "exit " exit-status "\n"))

(defn- delayed-composite-script [delay-seconds]
  (str "#!/bin/sh\n"
       "input_number=0\n"
       "while [ \"$#\" -gt 0 ]; do\n"
       "  if [ \"$1\" = \"-i\" ]; then\n"
       "    shift\n"
       "    input_number=$((input_number + 1))\n"
       "    if [ \"$input_number\" -eq 2 ]; then\n"
       "      overlay=$1\n"
       "      break\n"
       "    fi\n"
       "  fi\n"
       "  shift\n"
       "done\n"
       "head -c 1 \"$overlay\" >/dev/null &\n"
       "overlay_reader=$!\n"
       "head -c 1 >/dev/null\n"
       "wait \"$overlay_reader\"\n"
       "sleep " delay-seconds "\n"
       "exit 0\n"))

(defn- blocked-producer-failure-script [exit-status]
  (str "#!/bin/sh\n"
       "input_number=0\n"
       "while [ \"$#\" -gt 0 ]; do\n"
       "  if [ \"$1\" = \"-i\" ]; then\n"
       "    shift\n"
       "    input_number=$((input_number + 1))\n"
       "    if [ \"$input_number\" -eq 2 ]; then\n"
       "      overlay=$1\n"
       "      break\n"
       "    fi\n"
       "  fi\n"
       "  shift\n"
       "done\n"
       "head -c 1 \"$overlay\" >/dev/null\n"
       "exit " exit-status "\n"))

(defn- alpha-bounds [^bytes rgba width]
  (loop [pixel 0
         visible-count 0
         min-x width
         max-x -1
         min-y Integer/MAX_VALUE
         max-y -1]
    (if (< pixel (quot (alength rgba) 4))
      (if (pos? (bit-and 0xff (aget rgba (+ 3 (* pixel 4)))))
        (let [x (rem pixel width)
              y (quot pixel width)]
          (recur (inc pixel)
                 (inc visible-count)
                 (min min-x x)
                 (max max-x x)
                 (min min-y y)
                 (max max-y y)))
        (recur (inc pixel) visible-count min-x max-x min-y max-y))
      {:visible-count visible-count
       :min-x min-x
       :max-x max-x
       :min-y min-y
       :max-y max-y})))

(defn- heart-rate-alphas [^bytes rgba width height frame-index x-predicate]
  (let [frame-size (* width height 4)
        offset (* frame-index frame-size)]
    (->> (range (* width height))
         (keep (fn [pixel]
                 (let [x (rem pixel width)
                       y (quot pixel width)
                       byte-offset (+ offset (* pixel 4))
                       color (mapv #(bit-and 0xff (aget rgba (+ byte-offset %)))
                                   (range 4))
                       [red green blue alpha] color]
                   (when (and (x-predicate x)
                              (< 15 y (- height 10))
                              (= 255 red)
                              (<= 50 green 60)
                              (<= 75 blue 90))
                     alpha))))
         set)))

(deftest presets-lock-the-spike-matrix
  (is (= {:id "1080p25"
          :width 1920
          :height 1080
          :fps 25
          :duration-seconds 480}
         (spec/preset "1080p25")))
  (is (= {:id "2.7k25"
          :width 2704
          :height 1520
          :fps 25
          :duration-seconds 240}
         (spec/preset "2.7k25")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Duration"
                        (spec/with-duration (spec/preset "1080p25") 481))))

(deftest invalid-duration-text-cannot-fall-through-to-a-maximum-render
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Duration"
                        (renderer/parse-options ["--preset" "1080p25"
                                                 "--duration-seconds" "eight"]))))

(deftest renderer-cli-request-has-explicit-deterministic-clock-context
  (let [{:keys [output-path report-path] :as request}
        (renderer/parse-options ["--preset" "1080p25"
                                 "--duration-seconds" "1"
                                 "--profile" "false"])]
    (try
      (is (= Instant/EPOCH (:section-start-at request)))
      (is (= (ZoneId/of "UTC") (:display-time-zone request)))
      (finally
        (Files/deleteIfExists output-path)
        (Files/deleteIfExists report-path)))))

(deftest prores-4444-locks-encoder-input-and-decoder-output-formats
  (is (= {:encoder "prores_ks"
          :profile 4
          :encoder-input-pixel-format "yuva444p10le"
          :decoded-pixel-format "yuva444p12le"
          :alpha-bits 16}
         media/prores-4444-contract)))

(deftest aac-command-contract-locks-the-target-not-the-observed-average
  (is (= {:encoder "aac"
          :profile "aac_low"
          :sample-rate 48000
          :channels 2
          :target-bitrate "192k"
          :target-bitrate-bps 192000}
         media/aac-lc-contract)))

(deftest rendering-boundaries-are-protocol-backed
  (is (satisfies? frames/FrameRenderer frames/java2d-frame-renderer))
  (is (satisfies? media/VideoEncoder (media/ffmpeg-video-encoder)))
  (is (satisfies? media/CompositeEncoder (media/ffmpeg-video-encoder)))
  (is (satisfies? media/CompositeGalleryRenderer
                  (media/ffmpeg-video-encoder))))

(deftest selected-source-gallery-command-batch-decodes-real-output-frames
  (let [command (media/composite-gallery-command
                 "ffmpeg"
                 {:width 1920 :height 1080 :fps 25
                  :duration-seconds 480 :fit-mode "letterbox"}
                 [0 2500 11999]
                 "/tmp/selected-overlays.rgba")
        joined (str/join " " command)]
    (is (= 1 (count (filter #{"pipe:0"} command))))
    (is (some #{"/tmp/selected-overlays.rgba"} command))
    (is (str/includes? joined "eq(n\\,0)+eq(n\\,2500)+eq(n\\,11999)"))
    (is (str/includes? joined "hstack=inputs=2"))
    (is (= "3" (second (drop-while #(not= "-frames:v" %) command))))
    (is (= "pipe:1" (last command)))))

(deftest failed-selected-source-gallery-identifies-preview-decode-not-source-content
  (let [ffmpeg (executable-script! (short-source-reader-script 7))
        renderer (media/ffmpeg-video-encoder (str ffmpeg) "ffprobe")
        transparent (png-bytes 64 36 false)
        source-block (byte-array 65536)]
    (try
      (let [cause
            (try
              (jobs/with-durable-stage
                "composition_encode"
                (fn []
                  (media/render-composite-gallery!
                   renderer
                   {:width 64 :height 36 :fps 25 :duration-seconds 1
                    :fit-mode "letterbox"}
                   (fn [source-output]
                     (jobs/with-durable-stage
                       "source_content"
                       (fn []
                         (loop []
                           (.write ^OutputStream source-output source-block)
                           (recur)))))
                   [{:frameIndex 0 :overlay transparent}]
                   (fn [& _]
                     (throw (AssertionError. "failed decode emitted a frame"))))))
              (catch Throwable error error))
            diagnostics (jobs/failure-diagnostics cause 1)]
        (is (= {:failure-code "composition_encode_failed"
                :stage "composition_encode"
                :reason "preview_decode_failed"
                :retryable false
                :elapsed-ms 1}
               diagnostics))
        (is (not (re-find #"source|filename|oauth|https?://|token"
                          (pr-str diagnostics))))
        (is (= "preview_decode_failed"
               (:reason
                (jobs/job-resource
                 {:id "00000000-0000-0000-0000-000000000072"
                  :state :failed
                  :attempt 1
                  :created-at (java.time.Instant/parse
                               "2026-07-20T00:00:00Z")
                  :updated-at (java.time.Instant/parse
                               "2026-07-20T00:00:01Z")
                  :failure "composition_encode_failed"
                  :failure-diagnostics diagnostics}))))
        (is (nil? (:reason
                   (jobs/failure-diagnostics
                    (ex-info "private" {:reason "private_drive_id"}) 1)))))
      (finally
        (Files/deleteIfExists ffmpeg)))))

(deftest successful-short-source-gallery-output-is-a-bounded-partial-result
  (let [combined-path (Files/createTempFile
                       "agg-short-gallery-output-" ".png"
                       (make-array java.nio.file.attribute.FileAttribute 0))
        ffmpeg (executable-script!
                (gallery-output-script combined-path 0))
        renderer (media/ffmpeg-video-encoder (str ffmpeg) "ffprobe")
        transparent (png-bytes 64 36 false)
        emitted (atom [])]
    (try
      (Files/write combined-path (png-bytes 128 36 true)
                   (make-array OpenOption 0))
      (is (= {:requested-frame-count 2
              :generated-frame-count 1
              :omitted-frame-count 1
              :reason "source_duration_too_short"
              :source-decodes 1}
             (media/render-composite-gallery!
              renderer
              {:width 64 :height 36 :fps 25 :duration-seconds 2
               :fit-mode "letterbox"}
              (fn [source-output]
                (.write ^OutputStream source-output (byte-array 1024)))
              [{:frameIndex 0 :overlay transparent}
               {:frameIndex 49 :overlay transparent}]
              (fn [frame-index source-png final-png]
                (swap! emitted conj [frame-index
                                     (alength source-png)
                                     (alength final-png)])))))
      (is (= [0] (mapv first @emitted)))
      (finally
        (Files/deleteIfExists ffmpeg)
        (Files/deleteIfExists combined-path)))))

(deftest preview-moments-use-standard-prominence-and-stable-tie-breaking
  (let [section (first
                 (frames/preview-sections
                  {:fps 10 :duration-seconds 8
                   :telemetry (mapv (fn [seconds value]
                                      {:seconds seconds :heart-rate value})
                                    (range 9)
                                    [0 3 0 3 0 3 0 3 0])}))
        maxima (filter #(some #{"Prominent maximum"} (:labels %))
                       (:moments section))]
    (is (= [10 30 50] (mapv :frameIndex maxima)))
    (is (= [3.0 3.0 3.0] (mapv :value maxima)))
    (is (= ["Video start" "Trace start"]
           (:labels (first (:moments section)))))
    (is (= ["Trace stop" "Video end"]
           (:labels (last (:moments section)))))
    (is (= (sort (map :frameIndex (:moments section)))
           (map :frameIndex (:moments section))))))

(deftest noisy-prominence-selection-is-bounded-and-deterministic
  (let [render-spec {:fps 10 :duration-seconds 10
                     :telemetry
                     (mapv (fn [seconds value]
                             {:seconds seconds :heart-rate value})
                           (range 11)
                           [100 109 101 108 99 115 98 107 100 106 100])}
        first-moments (:moments (first (frames/preview-sections render-spec)))
        second-moments (:moments (first (frames/preview-sections render-spec)))
        extrema (filter #(some (fn [label]
                                 (str/starts-with? label "Prominent"))
                               (:labels %))
                        first-moments)]
    (is (= first-moments second-moments))
    (is (<= (count (filter #(some #{"Prominent minimum"} (:labels %))
                           extrema))
            3))
    (is (<= (count (filter #(some #{"Prominent maximum"} (:labels %))
                           extrema))
            3))
    (is (= (sort (map :frameIndex first-moments))
           (map :frameIndex first-moments)))))

(deftest preview-moments-collapse-plateaus-and-map-to-real-output-frames
  (let [section (first
                 (frames/preview-sections
                  {:fps 10 :duration-seconds 3
                   :telemetry [{:seconds 0.0 :heart-rate 1.0}
                               {:seconds 1.0 :heart-rate 4.0}
                               {:seconds 2.0 :heart-rate 4.0}
                               {:seconds 3.0 :heart-rate 1.0}]}))
        maximum (first (filter #(some #{"Prominent maximum"} (:labels %))
                               (:moments section)))]
    (is (= 10 (:frameIndex maximum)))
    (is (= "00:01.000" (:elapsed maximum)))
    (is (= 4.0 (:value maximum)))))

(deftest preview-moments-deduplicate-coincident-sub-frame-events
  (let [section (first
                 (frames/preview-sections
                  {:fps 4 :duration-seconds 1
                   :telemetry [{:seconds 0.0 :heart-rate 1.0}
                               {:seconds 0.24 :heart-rate 3.0}
                               {:seconds 0.26 :heart-rate 0.0}
                               {:seconds 1.0 :heart-rate 1.0}]}))
        coincident (first (filter #(= 1 (:frameIndex %)) (:moments section)))]
    (is (= ["Prominent minimum" "Prominent maximum"]
           (:labels coincident)))
    (is (= 1 (count (filter #(= 1 (:frameIndex %)) (:moments section)))))))

(deftest constant-and-monotonic-traces-have-only-combined-boundaries
  (doseq [values [[2.0 2.0 2.0] [1.0 2.0 3.0]]]
    (let [section (first
                   (frames/preview-sections
                    {:fps 2 :duration-seconds 2
                     :telemetry (mapv (fn [seconds value]
                                        {:seconds seconds :heart-rate value})
                                      [0.0 1.0 2.0]
                                      values)}))]
      (is (= 2 (count (:moments section))))
      (is (= ["Video start" "Trace start"]
             (:labels (first (:moments section)))))
      (is (= ["Trace stop" "Video end"]
             (:labels (last (:moments section))))))))

(deftest monotonic-preview-includes-distinct-timer-boundaries
  (let [moments
        (:moments
         (first
          (frames/preview-sections
           {:fps 2 :duration-seconds 4
            :telemetry [{:seconds 0.0 :heart-rate 100.0}
                        {:seconds 2.0 :heart-rate 120.0}
                        {:seconds 4.0 :heart-rate 140.0}]
            :timer {:start-seconds 1.0 :end-seconds 3.0}})))]
    (is (= [0 2 6 7] (mapv :frameIndex moments)))
    (is (= [["Video start" "Trace start"]
            ["Timer start"]
            ["Timer end"]
            ["Trace stop" "Video end"]]
           (mapv :labels moments)))))

(deftest timer-boundaries-combine-with-coincident-section-boundaries
  (let [moments
        (:moments
         (first
          (frames/preview-sections
           {:fps 2 :duration-seconds 2
            :telemetry [{:seconds 0.0 :heart-rate 100.0}
                        {:seconds 1.0 :heart-rate 120.0}
                        {:seconds 2.0 :heart-rate 140.0}]
            :timer {:start-seconds 0.0 :end-seconds 2.0}})))]
    (is (= [0 3] (mapv :frameIndex moments)))
    (is (= [["Video start" "Trace start" "Timer start"]
            ["Timer end" "Trace stop" "Video end"]]
           (mapv :labels moments)))))

(deftest timer-boundaries-deduplicate-after-sub-frame-mapping
  (let [moments
        (:moments
         (first
          (frames/preview-sections
           {:fps 4 :duration-seconds 1
            :telemetry [{:seconds 0.0 :heart-rate 100.0}
                        {:seconds 1.0 :heart-rate 140.0}]
            :timer {:start-seconds 0.24 :end-seconds 0.26}})))
        timer-moment (first (filter #(some #{"Timer start"} (:labels %))
                                    moments))]
    (is (= 1 (:frameIndex timer-moment)))
    (is (= ["Timer start" "Timer end"] (:labels timer-moment)))
    (is (= 1 (count (filter #(= 1 (:frameIndex %)) moments))))))

(deftest preview-sections-are-a-generic-trace-collection
  (let [sections
        (frames/preview-sections
         {:fps 2 :duration-seconds 2
          :telemetry [{:seconds 0.0 :heart-rate 120.0}
                      {:seconds 1.0 :heart-rate 140.0}
                      {:seconds 2.0 :heart-rate 110.0}]
          :spo2 [{:seconds 0.0 :spo2 96.0}
                 {:seconds 1.0 :spo2 92.0}
                 {:seconds 2.0 :spo2 97.0}]})]
    (is (= [{:id "heart-rate" :name "Heart rate" :unit "bpm"}
            {:id "spo2" :name "SpO2" :unit "%"}]
           (mapv #(select-keys % [:id :name :unit]) sections)))
    (is (every? seq (map :moments sections)))))

(deftest arbitrary-preview-frames-use-production-frame-timing
  (let [render-spec {:width 64 :height 36 :fps 4 :duration-seconds 1
                     :telemetry [{:seconds 0.0 :heart-rate 100.0}
                                 {:seconds 1.0 :heart-rate 140.0}]}
        output (ByteArrayOutputStream.)
        result (frames/render-frame-png! frames/java2d-frame-renderer
                                         (with-test-clock render-spec) 3 output)]
    (is (= {:width 64 :height 36 :frameIndex 3 :elapsedSeconds 0.75}
           result))
    (is (= [137 80 78 71 13 10 26 10]
           (mapv #(bit-and 0xff %) (take 8 (.toByteArray output)))))))

(deftest local-video-clock-follows-production-frame-timing-and-zone-rules
  (let [warsaw (ZoneId/of "Europe/Warsaw")
        section-start (Instant/parse "2026-07-17T12:37:00Z")]
    (is (= "14:37:11"
           (frames/local-clock-text section-start warsaw (/ 299.0 25))))
    (is (= "14:37:12"
           (frames/local-clock-text section-start warsaw (/ 300.0 25))))
    (is (= "00:00:00"
           (frames/local-clock-text
            (Instant/parse "2026-07-17T21:59:59Z") warsaw 1.0)))
    (is (= "03:00:00"
           (frames/local-clock-text
            (Instant/parse "2026-03-29T00:59:59Z") warsaw 1.0)))
    (is (= "14:37:12   00:12"
           (frames/readout-time-text section-start warsaw 12.0)))))

(deftest frame-readout-pixels-follow-the-selected-display-zone
  (let [render-spec {:width 800
                     :height 450
                     :fps 25
                     :duration-seconds 13
                     :section-start-at (Instant/parse "2026-07-17T12:37:00Z")
                     :telemetry [{:seconds 0.0 :heart-rate 140.0}
                                 {:seconds 13.0 :heart-rate 140.0}]}
        render (fn [zone]
                 (let [output (ByteArrayOutputStream.)]
                   (frames/render-frame-png!
                    frames/java2d-frame-renderer
                    (assoc render-spec :display-time-zone (ZoneId/of zone))
                    300 output)
                   (.toByteArray output)))]
    (is (not (java.util.Arrays/equals (render "Europe/Warsaw")
                                      (render "UTC"))))))

(deftest arbitrary-preview-frames-reject-the-exclusive-section-end
  (let [output (ByteArrayOutputStream.)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"real output frame"
         (frames/render-frame-png!
          frames/java2d-frame-renderer
          (with-test-clock
            {:width 64 :height 36 :fps 4 :duration-seconds 1
             :telemetry [{:seconds 0.0 :heart-rate 100.0}
                         {:seconds 1.0 :heart-rate 140.0}]})
          4 output)))))

(deftest preview-and-streamed-output-share-identical-clock-pixels
  (let [render-spec {:width 160
                     :height 90
                     :fps 2
                     :duration-seconds 2
                     :section-start-at (Instant/parse "2026-07-17T12:37:00Z")
                     :display-time-zone (ZoneId/of "Europe/Warsaw")
                     :telemetry [{:seconds 0.0 :heart-rate 140.0}
                                 {:seconds 2.0 :heart-rate 140.0}]}
        frame-index 2
        image (rendered-png render-spec frame-index)
        rgba (:rgba (streamed-rgba render-spec))
        frame-size (* (:width render-spec) (:height render-spec) 4)
        frame-offset (* frame-index frame-size)]
    (is (every?
         true?
         (for [pixel (range (* (:width render-spec) (:height render-spec)))
               :let [color (Color. (.getRGB image
                                            (rem pixel (:width render-spec))
                                            (quot pixel (:width render-spec)))
                                   true)
                     offset (+ frame-offset (* pixel 4))]]
           (= [(.getRed color) (.getGreen color)
               (.getBlue color) (.getAlpha color)]
              (mapv #(bit-and 0xff (aget ^bytes rgba (+ offset %)))
                    (range 4))))))))

(deftest preview-operation-results-expire-logically-at-24-hours
  (let [created (java.time.Instant/parse "2026-07-20T00:00:00Z")
        job {:id "00000000-0000-0000-0000-000000000061"
             :operationKind "preview"
             :state "succeeded"
             :createdAt (str created)
             :updatedAt (str created)
             :output {:output-bytes 123 :version 1 :sections [] :assets []}}
        resource (preview/operation-resource
                  job (.minusMillis (.plusSeconds created (* 24 60 60)) 1))]
    (is (= 1 (get-in resource [:result :version])))
    (is (nil? (get-in resource [:result :output-bytes])))
    (is (nil? (preview/operation-resource
               job (.plusSeconds created (* 24 60 60)))))))

(deftest cancelled-preview-operations-are-terminal-and-bounded
  (let [created (java.time.Instant/parse "2026-07-20T00:00:00Z")
        resource (fn [state]
                   (preview/operation-resource
                    {:id "00000000-0000-0000-0000-000000000061"
                     :operationKind "preview"
                     :state state
                     :createdAt (str created)
                     :updatedAt (str created)}
                    (.plusSeconds created 1)))]
    (is (= 75 (:progressPercent (resource "cancellation-requested"))))
    (is (nil? (:error (resource "cancellation-requested"))))
    (is (= {:code "preview_cancelled" :retryable false}
           (:error (resource "cancelled"))))
    (is (= 100 (:progressPercent (resource "cancelled"))))))

(deftest preview-asset-keys-require-uuid-operation-ids
  (let [store (preview/in-memory-asset-store)]
    (is (preview/valid-operation-id?
         "00000000-0000-0000-0000-000000000061"))
    (is (false? (preview/valid-operation-id? (apply str (repeat 512 "a")))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"key is invalid"
                          (preview/put-asset! store "not-a-uuid" "a000"
                                              :full (png-bytes 1 1 true))))))

(deftest overlay-gallery-renders-each-shared-frame-asset-once
  (let [store (preview/in-memory-asset-store)
        manifest
        (preview/render-gallery!
         "00000000-0000-0000-0000-000000000061"
         (with-test-clock
           {:width 64 :height 36 :fps 2 :duration-seconds 4
            :telemetry [{:seconds 0.0 :heart-rate 120.0}
                        {:seconds 2.0 :heart-rate 140.0}
                        {:seconds 4.0 :heart-rate 160.0}]
            :spo2 [{:seconds 0.0 :spo2 96.0}
                   {:seconds 2.0 :spo2 95.0}
                   {:seconds 4.0 :spo2 94.0}]
            :timer {:start-seconds 1.0 :end-seconds 3.0}})
         store frames/java2d-frame-renderer nil nil)
        moments (mapcat :moments (:sections manifest))]
    (is (= "overlay" (:mode manifest)))
    (is (= 2 (count (:sections manifest))))
    (is (every? #(= [0 2 6 7] (mapv :frameIndex (:moments %)))
                (:sections manifest)))
    (is (every? #(= [["Video start" "Trace start"]
                     ["Timer start"]
                     ["Timer end"]
                     ["Trace stop" "Video end"]]
                    (mapv :labels (:moments %)))
                (:sections manifest)))
    (is (every? #(= ["a000" "a001" "a002" "a003"]
                    (mapv :frameRef (:moments %)))
                (:sections manifest)))
    (is (= (count (set (map :frameIndex moments)))
           (count (:assets manifest))))
    (is (= [0 2 6 7] (mapv :frameIndex (:assets manifest))))
    (is (= (:frameRef (first (get-in manifest [:sections 0 :moments])))
           (:frameRef (first (get-in manifest [:sections 1 :moments])))))
    (is (every? #(= "overlay" (:kind %)) (:assets manifest)))
    (is (every? #(preview/get-asset
                  store "00000000-0000-0000-0000-000000000061"
                  (str (:id %) "-overlay") :thumbnail)
                (:assets manifest)))))

(deftest maximum-duration-source-gallery-uses-one-batched-decode
  (let [decode-count (atom 0)
        source-count (atom 0)
        store (preview/in-memory-asset-store)
        gallery-renderer
        (reify media/CompositeGalleryRenderer
          (render-composite-gallery!
            [_ _render-spec source-stream! overlays consume-frame!]
            (swap! decode-count inc)
            (with-open [output (ByteArrayOutputStream.)]
              (source-stream! output))
            (doseq [{:keys [frameIndex overlay]} overlays]
              (consume-frame! frameIndex (png-bytes 64 36 true) overlay))))
        manifest
        (preview/render-gallery!
         "00000000-0000-0000-0000-000000000062"
         (with-test-clock
           {:width 64 :height 36 :fps 25 :duration-seconds 480
            :source-video {:file-id "not-exposed"}
            :telemetry [{:seconds 0.0 :heart-rate 100.0}
                        {:seconds 480.0 :heart-rate 140.0}]})
         store frames/java2d-frame-renderer gallery-renderer
         (fn [output]
           (swap! source-count inc)
           (.write ^OutputStream output (byte-array 1024))))]
    (is (= 1 @decode-count))
    (is (= 1 @source-count))
    (is (= "source-final" (:mode manifest)))
    (is (= 2 (count (:assets manifest))))
    (is (nil? (:warnings manifest)))))

(deftest partial-source-gallery-keeps-complete-moments-and-bounded-warning
  (let [operation-id "00000000-0000-0000-0000-000000000084"
        decode-count (atom 0)
        source-count (atom 0)
        store (preview/in-memory-asset-store)
        gallery-renderer
        (reify media/CompositeGalleryRenderer
          (render-composite-gallery!
            [_ _render-spec source-stream! overlays consume-frame!]
            (swap! decode-count inc)
            (with-open [output (ByteArrayOutputStream.)]
              (source-stream! output))
            (doseq [{:keys [frameIndex overlay]}
                    (filter #(contains? #{0 6} (:frameIndex %)) overlays)]
              (consume-frame! frameIndex (png-bytes 64 36 true) overlay))
            {:requested-frame-count 4
             :generated-frame-count 2
             :omitted-frame-count 2
             :reason "source_duration_too_short"
             :source-decodes 1}))
        manifest
        (preview/render-gallery!
         operation-id
         (with-test-clock
           {:width 64 :height 36 :fps 2 :duration-seconds 4
            :source-video {:file-id "not-exposed"}
            :telemetry [{:seconds 0.0 :heart-rate 100.0}
                        {:seconds 2.0 :heart-rate 120.0}
                        {:seconds 4.0 :heart-rate 140.0}]
            :timer {:start-seconds 1.0 :end-seconds 3.0}})
         store frames/java2d-frame-renderer gallery-renderer
         (fn [output]
           (swap! source-count inc)
           (.write ^OutputStream output (byte-array 1024))))]
    (is (= 1 @decode-count))
    (is (= 1 @source-count))
    (is (= [0 6] (mapv :frameIndex (:assets manifest))))
    (is (= [0 6]
           (mapv :frameIndex (get-in manifest [:sections 0 :moments]))))
    (is (= ["a000" "a002"]
           (mapv :frameRef (get-in manifest [:sections 0 :moments]))))
    (is (= [{:reason "source_duration_too_short"
             :requestId operation-id
             :requestedMomentCount 4
             :generatedMomentCount 2
             :omittedMomentCount 2
             :requestedDurationSeconds 4
             :retryable false}]
           (:warnings manifest)))))

(deftest zero-source-gallery-is-a-bounded-failure-without-image-references
  (let [operation-id "00000000-0000-0000-0000-000000000085"
        store (preview/in-memory-asset-store)
        gallery-renderer
        (reify media/CompositeGalleryRenderer
          (render-composite-gallery!
            [_ _render-spec source-stream! overlays _consume-frame!]
            (with-open [output (ByteArrayOutputStream.)]
              (source-stream! output))
            {:requested-frame-count (count overlays)
             :generated-frame-count 0
             :omitted-frame-count (count overlays)
             :reason "source_duration_too_short"
             :source-decodes 1}))
        failure
        (try
          (preview/render-gallery!
           operation-id
           (with-test-clock
             {:width 64 :height 36 :fps 2 :duration-seconds 1
              :source-video {:file-id "not-exposed"}
              :telemetry [{:seconds 0.0 :heart-rate 100.0}
                          {:seconds 1.0 :heart-rate 140.0}]})
           store frames/java2d-frame-renderer gallery-renderer
           (fn [output]
             (.write ^OutputStream output (byte-array 1024))))
          (catch clojure.lang.ExceptionInfo error error))]
    (is (= {:type ::preview/source-duration-too-short
            :reason "source_duration_too_short"
            :limits {:requested-moment-count 2
                     :generated-moment-count 0
                     :omitted-moment-count 2
                     :requested-duration-seconds 1}
            :retryable false}
           (dissoc (ex-data failure) :source)))
    (doseq [asset-id ["a000-source" "a000-final"
                      "a001-source" "a001-final"]]
      (is (nil? (preview/get-asset store operation-id asset-id :thumbnail)))
      (is (nil? (preview/get-asset store operation-id asset-id :full))))))

(deftest transparent-complete-overlays-merge-source-and-final-assets
  (let [transparent (png-bytes 64 36 false)
        source (png-bytes 64 36 true)
        store (preview/in-memory-asset-store)
        frame-renderer
        (reify frames/PreviewFrameRenderer
          (render-frame-png! [_ _ frame-index output]
            (.write ^OutputStream output transparent)
            {:frameIndex frame-index}))
        gallery-renderer
        (reify media/CompositeGalleryRenderer
          (render-composite-gallery!
            [_ _ _ overlays consume-frame!]
            (doseq [{:keys [frameIndex]} overlays]
              (consume-frame! frameIndex source source))))
        manifest
        (preview/render-gallery!
         "00000000-0000-0000-0000-000000000063"
         {:width 64 :height 36 :fps 2 :duration-seconds 1
          :source-video {:file-id "not-exposed"}
          :telemetry [{:seconds 0.0 :heart-rate 100.0}
                      {:seconds 1.0 :heart-rate 140.0}]}
         store frame-renderer gallery-renderer (fn [_]))]
    (is (every? :merged (:assets manifest)))
    (is (every? #(= (:source %) (:final %)) (:assets manifest)))))

(deftest compositing-command-keeps-drive-source-on-a-non-seekable-pipe
  (let [command (media/composite-command
                 "ffmpeg"
                 {:width 1920 :height 1080 :fps 25 :duration-seconds 2
                  :output-format "h264-mp4"
                  :fit-mode "letterbox"
                  :audio-mode "source+heartbeat"}
                 "/tmp/heartbeat.wav"
                 "/tmp/overlay.pipe"
                 "/tmp/output.mp4")
        joined (str/join " " command)]
    (is (some #{"pipe:0"} command))
    (is (some #{"/tmp/overlay.pipe"} command))
    (is (str/includes? joined "[0:v]scale="))
    (is (str/includes? joined "[1:v]format=rgba"))
    (is (str/includes? joined "[0:a]aformat="))
    (is (str/includes? joined "force_original_aspect_ratio=decrease"))
    (is (str/includes? joined "amix=inputs=2"))
    (is (str/includes? joined "volume=0.5[src]"))
    (is (str/includes? joined "volume=1.0[beat]"))
    (is (not (some #(str/includes? % "source.mp4") command)))))

(deftest durable-composite-stops-before-its-render-deadline
  (let [ffmpeg (executable-script! (delayed-composite-script "0.5"))
        encoder (media/ffmpeg-video-encoder (str ffmpeg) "ffprobe")
        output (Files/createTempFile
                "agg-timeout-composite-" ".mp4"
                (make-array java.nio.file.attribute.FileAttribute 0))
        source-invocations (atom 0)]
    (try
      (let [started (System/nanoTime)
            cause
            (try
              (jobs/with-durable-stage
                "composition_encode"
                #(media/encode-composite!
                  encoder
                  {:width 64 :height 36 :fps 25 :duration-seconds 9
                   :output-format "h264-mp4"
                   :fit-mode "letterbox"
                   :audio-mode "source+heartbeat"
                   :timeout-ms 250}
                  "/tmp/heartbeat.wav"
                  output
                  (fn [source-output]
                    (swap! source-invocations inc)
                    (.write ^OutputStream source-output (byte-array [0])))
                  (fn [overlay-output]
                    (.write ^OutputStream overlay-output (byte-array [0])))))
              (catch Throwable error error))
            elapsed-ms (quot (- (System/nanoTime) started) 1000000)
            diagnostics (jobs/failure-diagnostics cause elapsed-ms)]
        (is (= {:failure-code "composition_timeout"
                :stage "composition_encode"
                :retryable true
                :elapsed-ms elapsed-ms
                :timeout-ms 250}
               diagnostics))
        (is (< elapsed-ms 1000))
        (is (= 1 @source-invocations)))
      (finally
        (Files/deleteIfExists output)
        (Files/deleteIfExists ffmpeg)))))

(deftest composite-producer-failure-is-not-masked-by-the-process-deadline
  (let [ffmpeg (executable-script! (delayed-composite-script "2"))
        encoder (media/ffmpeg-video-encoder (str ffmpeg) "ffprobe")
        output (Files/createTempFile
                "agg-producer-failure-composite-" ".mp4"
                (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [cause
            (try
              (media/encode-composite!
               encoder
               {:width 64 :height 36 :fps 25 :duration-seconds 9
                :output-format "h264-mp4"
                :fit-mode "letterbox"
                :audio-mode "heartbeat-only"
                :timeout-ms 500}
               "/tmp/heartbeat.wav"
               output
               (fn [source-output]
                 (.write ^OutputStream source-output (byte-array [0])))
               (fn [_]
                 (throw (IOException. "Overlay producer failed"))))
              (catch Throwable error error))]
        (is (= ::media/compositing-failed (:type (ex-data cause))))
        (is (instance? IOException (.getCause cause))))
      (finally
        (Files/deleteIfExists output)
        (Files/deleteIfExists ffmpeg)))))

(deftest ffmpeg-exit-is-not-masked-by-a-blocked-fifo-producer
  (let [ffmpeg (executable-script! (blocked-producer-failure-script 7))
        encoder (media/ffmpeg-video-encoder (str ffmpeg) "ffprobe")
        output (Files/createTempFile
                "agg-immediate-failure-composite-" ".mp4"
                (make-array java.nio.file.attribute.FileAttribute 0))
        release-producer (promise)]
    (try
      (let [cause
            (try
              (media/encode-composite!
               encoder
               {:width 64 :height 36 :fps 25 :duration-seconds 9
                :output-format "h264-mp4"
                :fit-mode "letterbox"
                :audio-mode "heartbeat-only"
                :timeout-ms 2000}
               "/tmp/heartbeat.wav"
               output
               (fn [_])
               (fn [overlay-output]
                 (.write ^OutputStream overlay-output (byte-array [0]))
                 (.flush ^OutputStream overlay-output)
                 @release-producer))
              (catch Throwable error error))]
        (is (= ::media/compositing-failed (:type (ex-data cause))))
        (is (= 7 (:exit-status (ex-data cause)))))
      (finally
        (deliver release-producer true)
        (Files/deleteIfExists output)
        (Files/deleteIfExists ffmpeg)))))

(deftest successful-duration-bounded-composite-accepts-a-closed-source-pipe
  (let [ffmpeg (executable-script!
                (short-source-reader-script 0))
        encoder (media/ffmpeg-video-encoder (str ffmpeg) "ffprobe")
        output (Files/createTempFile
                "agg-duration-bounded-composite-" ".mp4"
                (make-array java.nio.file.attribute.FileAttribute 0))
        source-block (byte-array 65536)
        source-invocations (atom 0)]
    (try
      (is (= {:exit-status 0}
             (media/encode-composite!
              encoder
              {:width 64 :height 36 :fps 25 :duration-seconds 2
               :output-format "h264-mp4"
               :fit-mode "letterbox"
               :audio-mode "source+heartbeat"}
              "/tmp/heartbeat.wav"
              output
              (fn [source-output]
                (swap! source-invocations inc)
                (jobs/with-durable-stage
                  "source_content"
                  #(loop []
                     (.write ^OutputStream source-output source-block)
                     (recur))))
              (fn [overlay-output]
                (.write ^OutputStream overlay-output (byte-array [0]))))))
      (is (= 1 @source-invocations))
      (finally
        (Files/deleteIfExists output)
        (Files/deleteIfExists ffmpeg)))))

(deftest successful-composite-still-reports-a-genuine-source-failure
  (let [ffmpeg (executable-script! (short-source-reader-script 0))
        encoder (media/ffmpeg-video-encoder (str ffmpeg) "ffprobe")
        output (Files/createTempFile
                "agg-source-failure-composite-" ".mp4"
                (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [error
            (try
              (media/encode-composite!
               encoder
               {:width 64 :height 36 :fps 25 :duration-seconds 2
                :output-format "h264-mp4"
                :fit-mode "letterbox"
                :audio-mode "heartbeat-only"}
               "/tmp/heartbeat.wav"
               output
               (fn [_]
                 (jobs/with-durable-stage
                   "source_content"
                   #(throw (IOException. "Source read failed"))))
               (fn [overlay-output]
                 (.write ^OutputStream overlay-output (byte-array [0]))))
              (catch Throwable cause cause))]
        (is (= ::media/compositing-failed (:type (ex-data error))))
        (is (= 0 (:exit-status (ex-data error))))
        (is (= "worker_failed" (:failure-code (ex-data (.getCause error)))))
        (is (= "source_content" (:stage (ex-data (.getCause error)))))
        (is (false? (:retryable (ex-data (.getCause error)))))
        (is (instance? IOException (some-> error .getCause .getCause))))
      (finally
        (Files/deleteIfExists output)
        (Files/deleteIfExists ffmpeg)))))

(deftest failed-composite-reports-ffmpeg-exit-after-source-pipe-closes
  (let [ffmpeg (executable-script! (short-source-reader-script 7))
        encoder (media/ffmpeg-video-encoder (str ffmpeg) "ffprobe")
        output (Files/createTempFile
                "agg-ffmpeg-failure-composite-" ".mp4"
                (make-array java.nio.file.attribute.FileAttribute 0))
        source-block (byte-array 65536)]
    (try
      (let [error
            (try
              (media/encode-composite!
               encoder
               {:width 64 :height 36 :fps 25 :duration-seconds 2
                :output-format "h264-mp4"
                :fit-mode "letterbox"
                :audio-mode "heartbeat-only"}
               "/tmp/heartbeat.wav"
               output
               (fn [source-output]
                 (jobs/with-durable-stage
                   "source_content"
                   #(loop []
                      (.write ^OutputStream source-output source-block)
                      (recur))))
               (fn [overlay-output]
                 (.write ^OutputStream overlay-output (byte-array [0]))))
              (catch Throwable cause cause))]
        (is (= ::media/compositing-failed (:type (ex-data error))))
        (is (= 7 (:exit-status (ex-data error)))))
      (finally
        (Files/deleteIfExists output)
        (Files/deleteIfExists ffmpeg)))))

(deftest arbitrary-polar-preview-frame-matches-the-golden-render
  (let [telemetry (slurp (io/resource "fixtures/polar/valid.csv"))
        render-spec (contract/prepare
                     {:telemetryFormat "polar-csv"
                      :telemetry telemetry
                      :preset "1080p25"
                      :futureTraceOpacityPercent 50
                      :telemetrySyncAt "2026-07-17T10:00:00Z"
                      :cameraSyncAt "2026-07-17T09:00:00Z"
                      :sectionStartAt "2026-07-17T09:00:00Z"
                      :sectionEndAt "2026-07-17T09:00:02Z"
                      :displayTimeZone "Europe/Warsaw"})
        first-output (ByteArrayOutputStream.)
        second-output (ByteArrayOutputStream.)]
    (is (= {:padding-ratio 0.10
            :minimum-heart-rate-padding 2.0
            :minimum-spo2-padding 0.5
            :tick-count 5}
           frames/trace-contract))
    (frames/render-frame-png! frames/java2d-frame-renderer render-spec 25
                              first-output)
    (frames/render-frame-png! frames/java2d-frame-renderer render-spec 25
                              second-output)
    (is (java.util.Arrays/equals (.toByteArray first-output)
                                 (.toByteArray second-output)))
    (is (= (str/trim
            (slurp (io/resource (golden-resource "polar-preview"))))
           (sha256-bytes (.toByteArray first-output))))))

(deftest frames-stream-as-rgba-with-transparency
  (let [{:keys [result rgba]} (streamed-rgba {:width 64
                                              :height 36
                                              :fps 2
                                              :duration-seconds 1})
        alpha (map #(bit-and 0xff (aget rgba %))
                   (range 3 (alength rgba) 4))]
    (is (= {:frame-count 2 :buffer-count 1} result))
    (is (= (* 64 36 4 2) (alength rgba)))
    (testing "the production-shaped overlay keeps both clear and visible pixels"
      (is (some zero? alpha))
      (is (some pos? alpha)))))

(deftest frame-stream-progress-is-bounded-and-monotonic
  (let [progress (atom [])
        output (ByteArrayOutputStream.)]
    (frames/stream!
     (with-test-clock
       {:width 64 :height 36 :fps 10 :duration-seconds 10
        :frame-progress! #(swap! progress conj %)})
     output)
    (is (= 0 (first @progress)))
    (is (= 100 (last @progress)))
    (is (apply <= @progress))
    (is (= (count @progress) (count (distinct @progress))))
    (is (<= (count @progress) 11))))

(deftest optional-spo2-timer-and-watermark-compose-into-streamed-frames
  (let [encoded-watermark
        (str/trim (slurp (io/resource "fixtures/watermark/tiny.png.b64")))
        {:keys [rgba]}
        (streamed-rgba
         {:width 64
          :height 36
          :fps 1
          :duration-seconds 2
          :telemetry [{:seconds 0.0 :heart-rate 120.0}
                      {:seconds 2.0 :heart-rate 128.0}]
          :spo2 [{:seconds 0.0 :spo2 97.0}
                 {:seconds 2.0 :spo2 93.0}]
          :timer {:start-seconds 0.5 :end-seconds 1.5}
          :watermark (watermark/decode-base64 encoded-watermark)})
        pixels (partition 4 rgba)
        unsigned (fn [pixel] (mapv #(bit-and 0xff %) pixel))
        colors (map unsigned pixels)]
    (testing "the SpO2 series has its own cyan trace"
      (is (some #(= [52 200 235] (subvec % 0 3)) colors)))
    (testing "the live readout and axes render in white"
      (is (some #(= [255 255 255] (subvec % 0 3)) colors)))
    (testing "the uploaded watermark retains its PNG color"
      (is (some #(= [255 0 0 255] %) colors)))))

(deftest both-locked-presets-have-deterministic-complete-frame-renders
  (doseq [preset-id ["1080p25" "2.7k25"]]
    (let [request (json/read-str
                   (slurp (io/resource
                           (str "fixtures/complete/" preset-id ".json")))
                   :key-fn keyword)
          render-spec (contract/prepare request)
          first-output (ByteArrayOutputStream.)
          second-output (ByteArrayOutputStream.)]
      (frames/render-frame-png! frames/java2d-frame-renderer render-spec
                                (quot (spec/frame-count render-spec) 2)
                                first-output)
      (frames/render-frame-png! frames/java2d-frame-renderer render-spec
                                (quot (spec/frame-count render-spec) 2)
                                second-output)
      (is (java.util.Arrays/equals (.toByteArray first-output)
                                   (.toByteArray second-output))
          preset-id)
      (is (= [137 80 78 71 13 10 26 10]
             (mapv #(bit-and 0xff %) (take 8 (.toByteArray first-output))))
          preset-id)
      (is (= (str/trim
              (slurp (io/resource
                      (golden-resource
                       (str "complete-" preset-id "-preview")))))
             (sha256-bytes (.toByteArray first-output)))
          preset-id)
      (let [image (ImageIO/read
                   (ByteArrayInputStream. (.toByteArray first-output)))
            readout-bottom (int (Math/round (* (:height render-spec) 0.10)))
            visible-xs
            (for [y (range readout-bottom)
                  x (range (:width render-spec))
                  :when (pos? (.getAlpha
                               (Color. (.getRGB image x y) true)))]
              x)]
        (is (seq visible-xs) (str preset-id " visible readout"))
        (is (pos? (apply min visible-xs))
            (str preset-id " readout is not left-clipped"))
        (is (< (apply max visible-xs) (dec (:width render-spec)))
            (str preset-id " readout is not right-clipped"))))))

(deftest frames-render-axes-readout-cursor-and-future-opacity
  (let [{:keys [rgba]}
        (streamed-rgba {:width 160
                        :height 90
                        :fps 1
                        :duration-seconds 2
                        :telemetry [{:seconds 0.0 :heart-rate 100.0}
                                    {:seconds 2.0 :heart-rate 140.0}]})
        pixels (partition 4 rgba)
        unsigned (fn [pixel] (mapv #(bit-and 0xff %) pixel))
        colors (map unsigned pixels)]
    (testing "the graph has opaque current pixels and subdued future pixels"
      (is (some #(= [255 55 82 255] %) colors))
      (is (some #(and (= [255 55 82] (subvec % 0 3))
                      (pos? (last %))
                      (< (last %) 255))
                colors)))
    (testing "axes and the readout render in white"
      (is (some #(= [255 255 255 255] %) colors)))
    (testing "the graph remains transparent outside its drawn content"
      (is (some #(= [0 0 0 0] %) colors)))))

(deftest timer-bounds-render-as-dashed-heart-rate-markers-at-their-trace-points
  (let [base-spec {:width 400
                   :height 200
                   :fps 4
                   :duration-seconds 4
                   :future-trace-opacity-percent 100
                   :telemetry [{:seconds 0.0 :heart-rate 120.0}
                               {:seconds 4.0 :heart-rate 120.0}]}
        without-timer (:rgba (streamed-rgba (assoc base-spec :fps 1)))
        with-timer (:rgba
                    (streamed-rgba
                     (assoc base-spec
                            :fps 1
                            :timer {:start-seconds 1.0 :end-seconds 3.0})))]
    (doseq [x [115 298]]
      (let [without-marker (heart-rate-red-rgba-ys
                            without-timer 400 200 0 x 80 120)
            marker (heart-rate-red-rgba-ys
                    with-timer 400 200 0 x 80 120)]
        (is (<= (count without-marker) 4) (str "no timer marker at x=" x))
        (is (> (count marker) 10) (str "timer marker at x=" x))
        (is (<= (apply min marker) 82) (str "upper half at x=" x))
        (is (>= (apply max marker) 118) (str "lower half at x=" x))
        (is (seq (remove marker (range 83 118)))
            (str "dashed gaps at x=" x))))))

(deftest timer-marker-alpha-follows-each-event-before-at-and-after-its-time
  (let [base-spec {:width 400
                   :height 200
                   :fps 4
                   :duration-seconds 4
                   :telemetry [{:seconds 0.0 :heart-rate 120.0}
                               {:seconds 4.0 :heart-rate 120.0}]
                   :timer {:start-seconds 1.0 :end-seconds 3.0}}
        marker-alphas (fn [image x]
                        (heart-rate-red-alphas image x 80 120))]
    (doseq [[percent future-alpha] [[nil 64] [0 nil] [25 64] [100 255]]]
      (let [render-spec (cond-> base-spec
                          (some? percent)
                          (assoc :future-trace-opacity-percent percent))
            before (rendered-png render-spec 0)
            at-start (rendered-png render-spec 4)
            at-end (rendered-png render-spec 12)]
        (if future-alpha
          (is (contains? (marker-alphas before 115) future-alpha)
              (str percent "% before start"))
          (is (empty? (marker-alphas before 115)) "0% before start"))
        (is (contains? (marker-alphas at-start 115) 255)
            (str percent "% at start"))
        (if future-alpha
          (is (contains? (marker-alphas at-start 298) future-alpha)
              (str percent "% before end"))
          (is (empty? (marker-alphas at-start 298)) "0% before end"))
        (is (contains? (marker-alphas at-end 298) 255)
            (str percent "% at end"))))))

(deftest timer-marker-lower-end-clamps-without-lengthening-its-upper-half
  (let [render-spec {:width 400
                     :height 200
                     :fps 4
                     :duration-seconds 4
                     :future-trace-opacity-percent 100
                     :telemetry [{:seconds 0.0 :heart-rate 10.0}
                                 {:seconds 1.0 :heart-rate 10.0}
                                 {:seconds 4.0 :heart-rate 110.0}]
                     :timer {:start-seconds 1.0 :end-seconds 3.0}}
        image (rendered-png render-spec 0)
        marker (heart-rate-red-ys image 115 130 190)]
    (is (<= 144 (apply min marker) 147)
        "upper end remains one nominal half-height above the trace")
    (is (<= (apply max marker) 175)
        "lower end does not cross the x-axis")))

(deftest timer-markers-center-on-heart-rate-when-spo2-is-rendered
  (let [base-spec {:width 400
                   :height 200
                   :fps 4
                   :duration-seconds 4
                   :future-trace-opacity-percent 100
                   :telemetry [{:seconds 0.0 :heart-rate 10.0}
                               {:seconds 2.0 :heart-rate 90.0}
                               {:seconds 4.0 :heart-rate 10.0}]
                   :spo2 [{:seconds 0.0 :spo2 90.0}
                          {:seconds 2.0 :spo2 90.0}
                          {:seconds 4.0 :spo2 100.0}]}
        without-timer (rendered-png base-spec 0)
        with-timer (rendered-png
                    (assoc base-spec
                           :timer {:start-seconds 2.0 :end-seconds 3.0})
                    0)
        x 200
        heart-rate-ys (heart-rate-red-ys without-timer x 20 175)
        spo2-ys (color-ys without-timer x 20 175 spo2-cyan?)
        added-marker-ys
        (->> (range 20 176)
             (filter (fn [y]
                       (and (heart-rate-red?
                             (Color. (.getRGB with-timer x y) true))
                            (not (heart-rate-red?
                                  (Color. (.getRGB without-timer x y)
                                          true))))))
             set)
        trace-center (/ (+ (apply min heart-rate-ys)
                           (apply max heart-rate-ys))
                        2.0)
        spo2-center (/ (+ (apply min spo2-ys) (apply max spo2-ys)) 2.0)
        marker-center (/ (+ (apply min added-marker-ys)
                            (apply max added-marker-ys))
                         2.0)]
    (is (<= (Math/abs (- marker-center trace-center)) 3.0))
    (is (> (Math/abs (- marker-center spo2-center)) 40.0))
    (is (< (apply min added-marker-ys) 24)
        "upper endpoint may extend above the graph plot")))

(deftest timer-marker-half-heights-scale-from-full-video-height-at-both-presets
  (doseq [preset-id ["1080p25" "2.7k25"]]
    (let [{:keys [width height]} (spec/preset preset-id)
          graph-left (int (Math/round (* width 0.060)))
          graph-right (- width (int (Math/round (* width 0.025))) 1)
          graph-top (int (Math/round (* height 0.120)))
          graph-bottom (- height (int (Math/round (* height 0.120))) 1)
          center-y (int (Math/round (/ (+ graph-top graph-bottom) 2.0)))
          half-height (int (Math/round (* height 0.10)))
          expected-top (- center-y half-height)
          expected-bottom (+ center-y half-height)
          final-dash-length (int (Math/ceil (* height 0.0125)))
          render-spec {:width width
                       :height height
                       :fps 1
                       :duration-seconds 4
                       :future-trace-opacity-percent 100
                       :telemetry [{:seconds 0.0 :heart-rate 120.0}
                                   {:seconds 4.0 :heart-rate 120.0}]
                       :timer {:start-seconds 1.0 :end-seconds 3.0}}
          image (rendered-png render-spec 0)]
      (doseq [ratio [0.25 0.75]]
        (let [x (int (Math/round
                      (+ graph-left (* ratio (- graph-right graph-left)))))
              marker (heart-rate-red-ys image x
                                        (- expected-top 2)
                                        (+ expected-bottom 2))]
          (is (<= (Math/abs (- (apply min marker) expected-top)) 1)
              (str preset-id " upper endpoint"))
          (is (<= (- expected-bottom final-dash-length 2)
                  (apply max marker)
                  (inc expected-bottom))
              (str preset-id " lower endpoint"))
          (is (> (count (remove marker
                                (range (inc expected-top) expected-bottom)))
                 10)
              (str preset-id " dashed stroke")))))))

(deftest configured-future-trace-opacity-controls-only-the-unreached-heart-rate-trace
  (let [width 160
        height 90
        midpoint (quot width 2)
        base-spec {:width width
                   :height height
                   :fps 1
                   :duration-seconds 2
                   :telemetry [{:seconds 0.0 :heart-rate 100.0}
                               {:seconds 2.0 :heart-rate 140.0}]}]
    (doseq [[percent expected-alpha] [[nil 64] [0 nil] [25 64] [100 255]]]
      (let [{:keys [rgba]}
            (streamed-rgba
             (cond-> base-spec
               (some? percent)
               (assoc :future-trace-opacity-percent percent)))
            reached-alphas (heart-rate-alphas rgba width height 1
                                              #(< 20 % (- midpoint 12)))
            future-alphas (heart-rate-alphas rgba width height 1
                                             #(> % (+ midpoint 12)))]
        (is (contains? reached-alphas 255)
            (str "reached trace at " percent "%"))
        (if expected-alpha
          (is (contains? future-alphas expected-alpha)
              (str "future trace at " percent "%"))
          (is (empty? future-alphas)
              "0% hides the future trace"))))))

(deftest preview-frames-use-the-same-future-trace-opacity
  (let [base-spec {:width 160
                   :height 90
                   :fps 1
                   :duration-seconds 2
                   :telemetry [{:seconds 0.0 :heart-rate 100.0}
                               {:seconds 2.0 :heart-rate 140.0}]}
        preview-hash
        (fn [percent]
          (let [output (ByteArrayOutputStream.)]
            (frames/render-frame-png!
             frames/java2d-frame-renderer
             (with-test-clock
               (cond-> base-spec
                 (some? percent)
                 (assoc :future-trace-opacity-percent percent)))
             1 output)
            (sha256-bytes (.toByteArray output))))
        hashes (mapv preview-hash [0 25 100])]
    (is (= 3 (count (set hashes))))
    (is (= (preview-hash nil) (preview-hash 25)))))

(deftest production-frame-dimensions-keep-the-trace-near-every-edge
  (doseq [preset-id ["1080p25" "2.7k25"]]
    (let [{:keys [width height]} (spec/preset preset-id)
          {:keys [result rgba]} (streamed-rgba {:width width
                                                :height height
                                                :fps 1
                                                :duration-seconds 1})
          {:keys [visible-count min-x max-x min-y max-y]} (alpha-bounds rgba width)]
      (testing preset-id
        (is (= {:frame-count 1 :buffer-count 1} result))
        (is (= (* width height 4) (alength rgba)))
        (is (pos? visible-count))
        (is (<= 0 min-x max-x (dec width)))
        (is (<= 0 min-y max-y (dec height)))))))

(deftest heartbeat-wav-is-exact-stereo-pcm
  (let [output (ByteArrayOutputStream.)
        result (audio/write-wav! {:duration-seconds 1} output)
        wav (.toByteArray output)
        little-u16 (fn [offset]
                     (+ (bit-and 0xff (aget wav offset))
                        (bit-shift-left (bit-and 0xff (aget wav (inc offset))) 8)))]
    (is (= {:sample-rate 48000
            :channels 2
            :sample-count 48000}
           result))
    (is (= "RIFF" (String. wav 0 4)))
    (is (= "WAVE" (String. wav 8 4)))
    (is (= 2 (little-u16 22)))
    (is (= 48000 (+ (bit-and 0xff (aget wav 24))
                    (bit-shift-left (bit-and 0xff (aget wav 25)) 8)
                    (bit-shift-left (bit-and 0xff (aget wav 26)) 16)
                    (bit-shift-left (bit-and 0xff (aget wav 27)) 24))))
    (testing "every interleaved left/right sample is identical"
      (is (every? true?
                  (for [offset (range 44 (alength wav) 4)]
                    (and (= (aget wav offset) (aget wav (+ offset 2)))
                         (= (aget wav (inc offset)) (aget wav (+ offset 3))))))))))

(deftest heartbeat-timing-follows-the-interpolated-polar-timeline
  (is (= [0 24000]
         (audio/beat-sample-indices
          {:duration-seconds 1
           :telemetry [{:seconds 0.0 :heart-rate 120.0}
                       {:seconds 1.0 :heart-rate 120.0}]}))))

(deftest render-job-completes-through-the-public-interface
  (let [output (Files/createTempFile "agg-render-test-" ".mov"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        fake-media
        (reify media/VideoEncoder
          (encode! [_ _ _ output-path write-frames!]
            (with-open [frames-output (OutputStream/nullOutputStream)]
              (write-frames! frames-output))
            (Files/write output-path
                         (.getBytes "synthetic-media" "UTF-8")
                         (make-array OpenOption 0))
            {:exit-status 0})
          (verify! [_ _render-spec _output-path]
            {:video {:codec "prores" :profile "4444" :alpha true}
             :audio {:codec "aac" :profile "LC" :channels 2}}))]
    (try
      (let [result (renderer/render! (with-test-clock
                                       {:id "test"
                                        :width 64
                                        :height 36
                                        :fps 2
                                        :duration-seconds 1
                                        :output-path output
                                        :profile? false})
                                     {:media fake-media})]
        (is (= 2 (:frame-count result)))
        (is (= 0 (:ffmpeg-exit-status result)))
        (is (= 15 (:output-bytes result)))
        (is (re-matches #"[0-9a-f]{64}" (:sha256 result)))
        (is (= "4444" (get-in result [:media :video :profile]))))
      (finally
        (Files/deleteIfExists output)))))

(deftest composite-frame-progress-stays-bounded-across-encoder-retries
  (let [output (Files/createTempFile "agg-progress-test-" ".mp4"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        progress (atom [])
        fake-media
        (reify
          media/VideoEncoder
          (encode! [_ _ _ _ _]
            (throw (AssertionError. "composite render used overlay encoder")))
          (verify! [_ _ _]
            (throw (AssertionError. "composite render used overlay verifier")))
          media/CompositeEncoder
          (encode-composite! [_ _ _ output-path _ write-overlay!]
            (with-open [sink (OutputStream/nullOutputStream)]
              (write-overlay! sink)
              (write-overlay! sink))
            (Files/write output-path (.getBytes "media" "UTF-8")
                         (make-array OpenOption 0))
            {:exit-status 0})
          (verify-composite! [_ _ _]
            {:video {:codec "h264"}
             :audio {:codec "aac"}}))]
    (try
      (renderer/render!
       (with-test-clock
         {:id "progress-test"
          :width 64 :height 36 :fps 10 :duration-seconds 10
          :output-format "h264-mp4"
          :source-video {:file-id "not-emitted"}
          :telemetry [{:seconds 0.0 :heart-rate 120.0}
                      {:seconds 10.0 :heart-rate 120.0}]
          :output-path output
          :profile? false})
       {:media fake-media
        :source-stream! (fn [_])
        :progress! (fn ([_]) ([_ fields]
                              (when-let [percent (:progressPercent fields)]
                                (swap! progress conj percent))))})
      (is (= [0 10 20 30 40 50 60 70 80 90 100] @progress))
      (finally
        (Files/deleteIfExists output)))))

(deftest render-job-defaults-to-path-ffmpeg-tools
  (let [output (Files/createTempFile "agg-default-tools-" ".mov"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        report (Files/createTempFile "agg-default-tools-" ".json"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        constructor-args (atom ::not-called)]
    (try
      (with-redefs [media/ffmpeg-video-encoder
                    (fn [& args]
                      (reset! constructor-args (vec args))
                      ::encoder)
                    renderer/render!
                    (fn [_request {:keys [video-encoder]}]
                      (is (= ::encoder video-encoder))
                      {:output-bytes 1 :sha256 "digest"})]
        (renderer/run-job! {:output-path output
                            :report-path report
                            :profile? false}))
      (is (= [] @constructor-args))
      (finally
        (Files/deleteIfExists output)
        (Files/deleteIfExists report)))))

(deftest durable-cloud-options-require-one-positive-attempt
  (is (= {:job-id "00000000-0000-0000-0000-000000000001"
          :attempt 2}
         (renderer/parse-cloud-options
          ["--job-id" "00000000-0000-0000-0000-000000000001"
           "--attempt" "2"])))
  (doseq [args [["--job-id" "00000000-0000-0000-0000-000000000001"]
                ["--job-id" "private-file-id" "--attempt" "1"]
                ["--job-id" "00000000-0000-0000-0000-000000000001"
                 "--attempt" "0"]
                ["--attempt" "1" "--job-id"
                 "00000000-0000-0000-0000-000000000001" "--secret"
                 "token"]]]
    (let [error (try
                  (renderer/parse-cloud-options args)
                  (catch Throwable cause cause))]
      (is (= ::renderer/invalid-cloud-options (:type (ex-data error))))
      (is (not (re-find #"private|secret|token|file-id"
                        (pr-str (ex-data error))))))))
