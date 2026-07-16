(ns agg.render-test
  (:require [agg.render.audio :as audio]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.render.spec :as spec]
            [agg.renderer.main :as renderer]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io ByteArrayOutputStream OutputStream)
           (java.nio.file Files OpenOption)))

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
  (let [output (ByteArrayOutputStream.)
        result (frames/stream! {:width 64
                                :height 36
                                :fps 2
                                :duration-seconds 1}
                               output)
        rgba (.toByteArray output)
        alpha (map #(bit-and 0xff (aget rgba %))
                   (range 3 (alength rgba) 4))]
    (is (= {:frame-count 2 :buffer-count 1} result))
    (is (= (* 64 36 4 2) (alength rgba)))
    (testing "the production-shaped overlay keeps both clear and visible pixels"
      (is (some zero? alpha))
      (is (some pos? alpha)))))

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
