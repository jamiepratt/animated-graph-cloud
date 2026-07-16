(ns agg.render.frames
  (:require [agg.render.spec :as spec])
  (:import (java.awt AlphaComposite BasicStroke Color Font RenderingHints Transparency)
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
        panel-y (int (* height 0.61))
        panel-height (- height panel-y)
        margin (max 2 (int (* width 0.025)))
        graph-left margin
        graph-right (- width margin)
        graph-top (+ panel-y (max 2 (int (* panel-height 0.18))))
        graph-bottom (- height (max 2 (int (* panel-height 0.16))))
        seconds (/ (double frame-index) (double fps))
        sample-count (max 8 (min 160 (- graph-right graph-left)))]
    (try
      (.setComposite g AlphaComposite/Clear)
      (.fillRect g 0 0 width height)
      (.setComposite g AlphaComposite/SrcOver)
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setColor g (Color. 5 14 28 112))
      (.fillRoundRect g
                      (quot margin 2)
                      panel-y
                      (- width margin)
                      panel-height
                      (max 4 (int (* height 0.018)))
                      (max 4 (int (* height 0.018))))
      (.setColor g (Color. 255 255 255 55))
      (.setStroke g (BasicStroke. (float (max 1.0 (* height 0.0012)))))
      (doseq [line (range 4)]
        (let [y (+ graph-top
                   (int (* line (/ (- graph-bottom graph-top) 3.0))))]
          (.drawLine g graph-left y graph-right y)))
      (.setColor g (Color. 255 55 82 245))
      (.setStroke g (BasicStroke. (float (max 1.5 (* height 0.0032)))
                                  BasicStroke/CAP_ROUND
                                  BasicStroke/JOIN_ROUND))
      (loop [point 1
             previous-x graph-left
             previous-y (int (- graph-bottom
                                (* (/ (- (synthetic-heart-rate (- seconds 8.0)) 80.0)
                                      100.0)
                                   (- graph-bottom graph-top))))]
        (when (< point sample-count)
          (let [ratio (/ (double point) (double (dec sample-count)))
                x (+ graph-left (int (* ratio (- graph-right graph-left))))
                sample-time (+ (- seconds 8.0) (* ratio 8.0))
                normalized (-> (- (synthetic-heart-rate sample-time) 80.0)
                               (/ 100.0)
                               (max 0.0)
                               (min 1.0))
                y (int (- graph-bottom (* normalized (- graph-bottom graph-top))))]
            (.drawLine g previous-x previous-y x y)
            (recur (inc point) x y))))
      (.setFont g (Font. Font/SANS_SERIF Font/BOLD (max 10 (int (* height 0.075)))))
      (.setColor g (Color. 255 255 255 245))
      (.drawString g
                   (str (Math/round (synthetic-heart-rate seconds)))
                   (float graph-left)
                   (float (- graph-top (max 2 (int (* height 0.012))))))
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
