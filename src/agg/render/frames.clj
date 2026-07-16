(ns agg.render.frames
  (:require [agg.render.spec :as spec])
  (:import (java.awt AlphaComposite BasicStroke Color RenderingHints Transparency)
           (java.awt.color ColorSpace)
           (java.awt.image BufferedImage ComponentColorModel DataBuffer DataBufferByte Raster)
           (java.io OutputStream)))

(defn- rgba-surface [width height]
  (let [rgba (byte-array (* width height 4))
        data-buffer (DataBufferByte. rgba (alength rgba))
        raster (Raster/createInterleavedRaster data-buffer
                                               width
                                               height
                                               (* width 4)
                                               4
                                               (int-array [0 1 2 3])
                                               nil)
        color-model (ComponentColorModel. (ColorSpace/getInstance ColorSpace/CS_sRGB)
                                          true
                                          false
                                          Transparency/TRANSLUCENT
                                          DataBuffer/TYPE_BYTE)]
    {:image (BufferedImage. color-model raster false nil)
     :rgba rgba}))

(defn- synthetic-heart-rate [seconds]
  (+ 126.0
     (* 19.0 (Math/sin (* seconds 0.073)))
     (* 7.0 (Math/sin (* seconds 0.019)))))

(defn- render-frame! [^BufferedImage image frame-index fps]
  (let [g (.createGraphics image)
        width (.getWidth image)
        height (.getHeight image)
        horizontal-margin (max 1 (int (Math/round (* width 0.015))))
        vertical-margin (max 1 (int (Math/round (* height 0.02))))
        graph-left horizontal-margin
        graph-right (- width horizontal-margin 1)
        graph-top vertical-margin
        graph-bottom (- height vertical-margin 1)
        seconds (/ (double frame-index) (double fps))
        sample-count (max 8 (min 160 (inc (- graph-right graph-left))))
        samples (mapv (fn [point]
                        (let [ratio (/ (double point) (double (dec sample-count)))
                              sample-time (+ (- seconds 8.0) (* ratio 8.0))]
                          {:x (+ graph-left
                                 (int (* ratio (- graph-right graph-left))))
                           :heart-rate (synthetic-heart-rate sample-time)}))
                      (range sample-count))
        minimum-heart-rate (apply min (map :heart-rate samples))
        maximum-heart-rate (apply max (map :heart-rate samples))
        heart-rate-range (max 1.0 (- maximum-heart-rate minimum-heart-rate))
        y-for (fn [heart-rate]
                (int (- graph-bottom
                        (* (/ (- heart-rate minimum-heart-rate) heart-rate-range)
                           (- graph-bottom graph-top)))))]
    (try
      (.setComposite g AlphaComposite/Clear)
      (.fillRect g 0 0 width height)
      (.setComposite g AlphaComposite/SrcOver)
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setColor g (Color. 255 55 82 255))
      (.setStroke g (BasicStroke. (float (max 1.5 (* height 0.0032)))
                                  BasicStroke/CAP_ROUND
                                  BasicStroke/JOIN_ROUND))
      (loop [[previous current & remaining] samples]
        (when current
          (.drawLine g
                     (:x previous)
                     (y-for (:heart-rate previous))
                     (:x current)
                     (y-for (:heart-rate current)))
          (recur (cons current remaining))))
      (finally
        (.dispose g)))))

(defn stream!
  "Streams exactly one reusable RGBA frame buffer at a time to output."
  [{:keys [width height fps] :as render-spec} ^OutputStream output]
  (let [{:keys [image rgba]} (rgba-surface width height)
        frames (spec/frame-count render-spec)]
    (dotimes [frame-index frames]
      (render-frame! image frame-index fps)
      (.write output ^bytes rgba 0 (alength ^bytes rgba)))
    (.flush output)
    {:frame-count frames
     :buffer-count 1}))
