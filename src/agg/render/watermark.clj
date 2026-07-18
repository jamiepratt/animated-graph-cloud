(ns agg.render.watermark
  (:require [agg.errors :as errors])
  (:import (java.io ByteArrayInputStream)
           (java.nio ByteBuffer)
           (java.util Base64)
           (javax.imageio ImageIO)))

(def limits
  {:bytes (* 2 1024 1024)
   :width 1024
   :height 1024
   :pixels (* 1024 1024)})

(def ^:private png-signature [137 80 78 71 13 10 26 10])
(def ^:private max-base64-characters
  (* 4 (quot (+ (:bytes limits) 2) 3)))

(defn- require-png! [condition message type]
  (when-not condition
    (throw (errors/raise! message {:type type :limits limits}))))

(defn decode-base64
  "Validates bounded PNG headers before decoding one reusable image."
  [encoded]
  (try
    (require-png! (and (string? encoded)
                       (<= (count encoded) max-base64-characters))
                  "Watermark exceeds the encoded size limit"
                  ::png-too-large)
    (let [bytes (.decode (Base64/getDecoder) ^String encoded)]
      (require-png! (and (<= 24 (alength bytes) (:bytes limits))
                         (= png-signature
                            (mapv #(bit-and 0xff (aget bytes %)) (range 8)))
                         (= "IHDR" (String. bytes 12 4 "US-ASCII")))
                    "Watermark must be PNG content"
                    ::invalid-png)
      (let [header (ByteBuffer/wrap bytes)
            width (.getInt header 16)
            height (.getInt header 20)]
        (require-png! (and (pos? width)
                           (pos? height)
                           (<= width (:width limits))
                           (<= height (:height limits))
                           (<= (* (long width) height) (:pixels limits)))
                      "Watermark dimensions exceed the limit"
                      ::png-dimensions-too-large)
        (with-open [input (ByteArrayInputStream. bytes)]
          (let [image (ImageIO/read input)]
            (require-png! (and image
                               (= width (.getWidth image))
                               (= height (.getHeight image)))
                          "Watermark PNG could not be decoded"
                          ::invalid-png)
            {:image image :width width :height height}))))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Throwable cause
      (throw (errors/raise! "Watermark must be valid base64 PNG content"
                      {:type ::invalid-png :limits limits}
                      cause)))))
