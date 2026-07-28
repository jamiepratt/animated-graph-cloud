(ns agg.render.gallery
  (:require [agg.errors :as errors])
  (:import (java.io ByteArrayInputStream)
           (java.util Arrays)
           (javax.imageio ImageIO)))

(def ^:private png-signature
  (byte-array [(unchecked-byte 137) 80 78 71 13 10 26 10]))

(defn- bytes-match? [bytes offset expected]
  (and (<= (+ offset (alength ^bytes expected)) (alength ^bytes bytes))
       (every? (fn [index]
                 (= (aget ^bytes bytes (+ offset index))
                    (aget ^bytes expected index)))
               (range (alength ^bytes expected)))))

(defn- unsigned-int-at [bytes offset]
  (+ (bit-shift-left (bit-and 0xff (aget ^bytes bytes offset)) 24)
     (bit-shift-left (bit-and 0xff (aget ^bytes bytes (inc offset))) 16)
     (bit-shift-left (bit-and 0xff (aget ^bytes bytes (+ offset 2))) 8)
     (bit-and 0xff (aget ^bytes bytes (+ offset 3)))))

(defn decode-png-stream
  "Splits one bounded image2pipe payload into complete PNG images."
  [bytes]
  (loop [offset 0
         images []]
    (if (= offset (alength ^bytes bytes))
      images
      (do
        (when-not (bytes-match? bytes offset png-signature)
          (throw (errors/raise! "Source gallery emitted invalid PNG data"
                                {:type ::invalid-output})))
        (let [end
              (loop [chunk-offset (+ offset 8)]
                (when (> (+ chunk-offset 12) (alength ^bytes bytes))
                  (throw (errors/raise! "Source gallery PNG is truncated"
                                        {:type ::invalid-output})))
                (let [length (unsigned-int-at bytes chunk-offset)
                      chunk-end (+ chunk-offset 12 length)]
                  (when (> chunk-end (alength ^bytes bytes))
                    (throw (errors/raise! "Source gallery PNG is truncated"
                                          {:type ::invalid-output})))
                  (if (= [73 69 78 68]
                         (mapv #(bit-and 0xff (aget ^bytes bytes %))
                               (range (+ chunk-offset 4) (+ chunk-offset 8))))
                    chunk-end
                    (recur chunk-end))))]
          (recur end (conj images (Arrays/copyOfRange bytes offset end))))))))

(defn consume-png!
  "Validates one decoded gallery frame before exposing it to the consumer."
  [render-spec frame-index final-png consume-frame!]
  (let [{:keys [width height]} render-spec
        image (ImageIO/read (ByteArrayInputStream. final-png))]
    (when-not (and image (= width (.getWidth image))
                   (= height (.getHeight image)))
      (throw (errors/raise! "Source gallery frame dimensions are invalid"
                            {:type ::invalid-output})))
    (consume-frame! frame-index final-png)))
