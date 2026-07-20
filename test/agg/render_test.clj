(ns agg.render-test
  (:require [agg.render.audio :as audio]
            [agg.contracts.render :as contract]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.render.spec :as spec]
            [agg.render.watermark :as watermark]
            [agg.renderer.main :as renderer]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io ByteArrayOutputStream OutputStream)
           (java.nio.file Files OpenOption)
           (java.security MessageDigest)
           (java.util HexFormat)))

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

(defn- streamed-rgba [render-spec]
  (let [output (ByteArrayOutputStream.)]
    {:result (frames/stream! render-spec output)
     :rgba (.toByteArray output)}))

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
  (is (satisfies? media/CompositePreviewer (media/ffmpeg-video-encoder))))

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
    (is (str/includes? joined "force_original_aspect_ratio=decrease"))
    (is (str/includes? joined "amix=inputs=2"))
    (is (str/includes? joined "volume=0.5[src]"))
    (is (str/includes? joined "volume=1.0[beat]"))
    (is (not (some #(str/includes? % "source.mp4") command)))))

(deftest selected-source-preview-composites-only-the-midpoint-frame
  (let [command (media/composite-preview-command
                 "ffmpeg"
                 {:width 1920 :height 1080 :duration-seconds 157
                  :fit-mode "letterbox"}
                 "/tmp/overlay.png")
        joined (str/join " " command)]
    (is (some #{"pipe:0"} command))
    (is (some #{"/tmp/overlay.png"} command))
    (is (str/includes? joined "force_original_aspect_ratio=decrease"))
    (is (= "78.5" (second (drop-while #(not= "-ss" %) command))))
    (is (= "1" (second (drop-while #(not= "-frames:v" %) command))))
    (is (not (some #{"-t"} command)))
    (is (= "pipe:1" (last command)))))

(deftest polar-midpoint-preview-matches-the-golden-render
  (let [telemetry (slurp (io/resource "fixtures/polar/valid.csv"))
        render-spec (contract/prepare
                     {:telemetryFormat "polar-csv"
                      :telemetry telemetry
                      :preset "1080p25"
                      :telemetrySyncAt "2026-07-17T10:00:00Z"
                      :cameraSyncAt "2026-07-17T09:00:00Z"
                      :sectionStartAt "2026-07-17T09:00:00Z"
                      :sectionEndAt "2026-07-17T09:00:02Z"})
        first-output (ByteArrayOutputStream.)
        second-output (ByteArrayOutputStream.)]
    (is (= {:padding-ratio 0.10
            :minimum-heart-rate-padding 2.0
            :minimum-spo2-padding 0.5
            :tick-count 5}
           frames/trace-contract))
    (frames/render-preview! frames/java2d-frame-renderer render-spec first-output)
    (frames/render-preview! frames/java2d-frame-renderer render-spec second-output)
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

(deftest both-locked-presets-have-golden-complete-previews
  (doseq [preset-id ["1080p25" "2.7k25"]]
    (let [request (json/read-str
                   (slurp (io/resource
                           (str "fixtures/complete/" preset-id ".json")))
                   :key-fn keyword)
          render-spec (contract/prepare request)
          output (ByteArrayOutputStream.)]
      (frames/render-preview! frames/java2d-frame-renderer render-spec output)
      (is (= (str/trim
              (slurp (io/resource
                      (golden-resource (str "complete-"
                                            preset-id
                                            "-preview")))))
             (sha256-bytes (.toByteArray output)))
          preset-id))))

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
    (testing "the graph has opaque current pixels and half-alpha future pixels"
      (is (some #(= [255 55 82 255] %) colors))
      (is (some #(and (= [255 55 82] (subvec % 0 3))
                      (pos? (last %))
                      (< (last %) 255))
                colors)))
    (testing "axes and the readout render in white"
      (is (some #(= [255 255 255 255] %) colors)))
    (testing "the graph remains transparent outside its drawn content"
      (is (some #(= [0 0 0 0] %) colors)))))

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
      (let [result (renderer/render! {:id "test"
                                      :width 64
                                      :height 36
                                      :fps 2
                                      :duration-seconds 1
                                      :output-path output
                                      :profile? false}
                                     {:media fake-media})]
        (is (= 2 (:frame-count result)))
        (is (= 0 (:ffmpeg-exit-status result)))
        (is (= 15 (:output-bytes result)))
        (is (re-matches #"[0-9a-f]{64}" (:sha256 result)))
        (is (= "4444" (get-in result [:media :video :profile]))))
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
