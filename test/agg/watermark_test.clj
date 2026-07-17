(ns agg.watermark-test
  (:require [agg.render.watermark :as watermark]
            [clojure.test :refer [deftest is testing]])
  (:import (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (java.util Base64)
           (javax.imageio ImageIO)))

(defn- png-base64 [width height]
  (let [image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        output (ByteArrayOutputStream.)]
    (ImageIO/write image "png" output)
    (.encodeToString (Base64/getEncoder) (.toByteArray output))))

(defn- error-type [encoded]
  (try
    (watermark/decode-base64 encoded)
    nil
    (catch clojure.lang.ExceptionInfo error
      (:type (ex-data error)))))

(deftest watermark-rejects-non-png-and-oversized-images
  (testing "content signature"
    (is (= ::watermark/invalid-png
           (error-type "bm90LWEtcG5n"))))
  (testing "width before pixel decoding"
    (is (= ::watermark/png-dimensions-too-large
           (error-type (png-base64 1025 1)))))
  (testing "encoded byte budget before base64 decoding"
    (is (= ::watermark/png-too-large
           (error-type (apply str
                              (repeat (inc (* 4 (quot (+ (* 2 1024 1024) 2)
                                                      3)))
                                      "A")))))))
