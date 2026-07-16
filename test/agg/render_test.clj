(ns agg.render-test
  (:require [agg.render.audio :as audio]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.render.spec :as spec]
            [agg.renderer.main :as renderer]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io ByteArrayOutputStream OutputStream)
           (java.nio.file Files OpenOption)))

(defn- streamed-rgba [render-spec]
  (let [output (ByteArrayOutputStream.)]
    {:result (frames/stream! render-spec output)
     :rgba (.toByteArray output)}))

(defn- visible-pixels [rgba width height]
  (vec (for [y (range height)
             x (range width)
             :when (pos? (bit-and 0xff
                                  (aget rgba (+ 3 (* 4 (+ x (* y width)))))))]
         [x y])))

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

(deftest frames-stream-only-a-full-frame-heart-rate-trace
  (let [width 64
        height 36
        {:keys [rgba]} (streamed-rgba {:width width
                                       :height height
                                       :fps 1
                                       :duration-seconds 1})
        visible (visible-pixels rgba width height)
        visible-by-column (group-by first visible)
        xs (map first visible)
        ys (map second visible)]
    (testing "only the antialiased trace has alpha"
      (is (pos? (count visible)))
      (is (<= (count visible) (* width 8)))
      (is (every? #(<= (count %) 8) (vals visible-by-column))))
    (testing "the trace uses nearly the full frame coordinate system"
      (is (>= (count visible-by-column) (- width 4)))
      (is (<= (apply min xs) 2))
      (is (>= (apply max xs) (- width 3)))
      (is (<= (apply min ys) 2))
      (is (>= (apply max ys) (- height 3))))))

(deftest production-frame-dimensions-keep-the-trace-near-every-edge
  (doseq [preset-id ["1080p25" "2.7k25"]]
    (let [{:keys [width height]} (spec/preset preset-id)
          {:keys [result rgba]} (streamed-rgba {:width width
                                                :height height
                                                :fps 1
                                                :duration-seconds 1})
          {:keys [visible-count min-x max-x min-y max-y]} (alpha-bounds rgba width)
          horizontal-tolerance (Math/ceil (* width 0.02))
          vertical-tolerance (Math/ceil (* height 0.03))]
      (testing preset-id
        (is (= {:frame-count 1 :buffer-count 1} result))
        (is (= (* width height 4) (alength rgba)))
        (is (pos? visible-count))
        (is (<= min-x horizontal-tolerance))
        (is (>= max-x (- width horizontal-tolerance 1)))
        (is (<= min-y vertical-tolerance))
        (is (>= max-y (- height vertical-tolerance 1)))))))

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

(deftest render-job-completes-through-the-public-interface
  (let [output (Files/createTempFile "agg-render-test-" ".mov"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        fake-media
        (reify media/Media
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
