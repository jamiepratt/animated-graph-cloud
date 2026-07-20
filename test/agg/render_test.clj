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
           (java.io ByteArrayOutputStream IOException OutputStream)
           (java.nio.file Files OpenOption)
           (java.security MessageDigest)
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

(defn- streamed-rgba [render-spec]
  (let [output (ByteArrayOutputStream.)]
    {:result (frames/stream! render-spec output)
     :rgba (.toByteArray output)}))

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
      (is (= ["Video start"]
             (:labels (first (:moments section)))))
      (is (= ["Trace stop"]
             (:labels (last (:moments section))))))))

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
                                         render-spec 3 output)]
    (is (= {:width 64 :height 36 :frameIndex 3 :elapsedSeconds 0.75}
           result))
    (is (= [137 80 78 71 13 10 26 10]
           (mapv #(bit-and 0xff %) (take 8 (.toByteArray output)))))))

(deftest arbitrary-preview-frames-reject-the-exclusive-section-end
  (let [output (ByteArrayOutputStream.)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"real output frame"
         (frames/render-frame-png!
          frames/java2d-frame-renderer
          {:width 64 :height 36 :fps 4 :duration-seconds 1
           :telemetry [{:seconds 0.0 :heart-rate 100.0}
                       {:seconds 1.0 :heart-rate 140.0}]}
          4 output)))))

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
         {:width 64 :height 36 :fps 2 :duration-seconds 2
          :telemetry [{:seconds 0.0 :heart-rate 120.0}
                      {:seconds 1.0 :heart-rate 140.0}
                      {:seconds 2.0 :heart-rate 110.0}]
          :spo2 [{:seconds 0.0 :spo2 96.0}
                 {:seconds 1.0 :spo2 92.0}
                 {:seconds 2.0 :spo2 97.0}]}
         store frames/java2d-frame-renderer nil nil)
        moments (mapcat :moments (:sections manifest))]
    (is (= "overlay" (:mode manifest)))
    (is (= 2 (count (:sections manifest))))
    (is (= (count (set (map :frameIndex moments)))
           (count (:assets manifest))))
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
         {:width 64 :height 36 :fps 25 :duration-seconds 480
          :source-video {:file-id "not-exposed"}
          :telemetry [{:seconds 0.0 :heart-rate 100.0}
                      {:seconds 480.0 :heart-rate 140.0}]}
         store frames/java2d-frame-renderer gallery-renderer
         (fn [output]
           (swap! source-count inc)
           (.write ^OutputStream output (byte-array 1024))))]
    (is (= 1 @decode-count))
    (is (= 1 @source-count))
    (is (= "source-final" (:mode manifest)))
    (is (= 2 (count (:assets manifest))))))

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
    (is (str/includes? joined "force_original_aspect_ratio=decrease"))
    (is (str/includes? joined "amix=inputs=2"))
    (is (str/includes? joined "volume=0.5[src]"))
    (is (str/includes? joined "volume=1.0[beat]"))
    (is (not (some #(str/includes? % "source.mp4") command)))))

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
