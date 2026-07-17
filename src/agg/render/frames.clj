(ns agg.render.frames
  (:require [agg.render.spec :as spec]
            [agg.telemetry.timeline :as timeline])
  (:import (java.awt AlphaComposite BasicStroke Color RenderingHints Transparency)
           (java.awt.color ColorSpace)
           (java.awt.image BufferedImage ComponentColorModel DataBuffer DataBufferByte Raster)
           (java.io OutputStream)
           (javax.imageio ImageIO)))

(def trace-contract
  {:window-seconds 30.0
   :heart-rate-min 40.0
   :heart-rate-max 220.0})

(def spo2-contract
  {:minimum 70.0
   :maximum 100.0
   :color [52 200 235 255]})

(def ^:private timer-glyphs
  {\0 ["111" "101" "101" "101" "111"]
   \1 ["010" "110" "010" "010" "111"]
   \2 ["111" "001" "111" "100" "111"]
   \3 ["111" "001" "111" "001" "111"]
   \4 ["101" "101" "111" "001" "001"]
   \5 ["111" "100" "111" "001" "111"]
   \6 ["111" "100" "111" "101" "111"]
   \7 ["111" "001" "010" "010" "010"]
   \8 ["111" "101" "111" "101" "111"]
   \9 ["111" "101" "111" "001" "111"]
   \: ["0" "1" "0" "1" "0"]})

(defprotocol FrameRenderer
  (stream-frames! [renderer render-spec output]
    "Streams RGBA frames without retaining a frame collection.")
  (render-preview! [renderer render-spec output]
    "Writes the section midpoint as one deterministic PNG."))

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

(defn- window-bounds [duration-seconds seconds]
  (let [window (:window-seconds trace-contract)]
    (if (<= duration-seconds window)
      [0.0 (double duration-seconds)]
      (let [start (-> (- seconds (/ window 2.0))
                      (max 0.0)
                      (min (- duration-seconds window)))]
        [start (+ start window)]))))

(defn- trace-samples [{:keys [telemetry duration-seconds]} seconds
                      graph-left graph-right]
  (let [sample-count (max 8 (min 160 (inc (- graph-right graph-left))))]
    (if (seq telemetry)
      (let [[window-start window-end] (window-bounds duration-seconds seconds)]
        {:samples (mapv (fn [point]
                          (let [ratio (/ (double point) (double (dec sample-count)))
                                sample-time (+ window-start
                                               (* ratio (- window-end window-start)))]
                            {:x (+ graph-left
                                   (int (* ratio (- graph-right graph-left))))
                             :heart-rate (timeline/heart-rate-at-seconds
                                          telemetry
                                          sample-time)}))
                        (range sample-count))
         :minimum (:heart-rate-min trace-contract)
         :maximum (:heart-rate-max trace-contract)})
      (let [samples (mapv (fn [point]
                            (let [ratio (/ (double point) (double (dec sample-count)))
                                  sample-time (+ (- seconds 8.0) (* ratio 8.0))]
                              {:x (+ graph-left
                                     (int (* ratio (- graph-right graph-left))))
                               :heart-rate (synthetic-heart-rate sample-time)}))
                          (range sample-count))]
        {:samples samples
         :minimum (apply min (map :heart-rate samples))
         :maximum (apply max (map :heart-rate samples))}))))

(defn- spo2-samples [{:keys [spo2 duration-seconds]} seconds
                     graph-left graph-right]
  (when (seq spo2)
    (let [sample-count (max 8 (min 160 (inc (- graph-right graph-left))))
          [window-start window-end] (window-bounds duration-seconds seconds)]
      (mapv (fn [point]
              (let [ratio (/ (double point) (double (dec sample-count)))
                    sample-time (+ window-start
                                   (* ratio (- window-end window-start)))]
                {:x (+ graph-left
                       (int (* ratio (- graph-right graph-left))))
                 :value (timeline/value-at-seconds spo2 :spo2 sample-time)}))
            (range sample-count)))))

(defn- draw-series! [graphics samples y-for]
  (loop [[previous current & remaining] samples]
    (when current
      (.drawLine graphics
                 (:x previous)
                 (y-for previous)
                 (:x current)
                 (y-for current))
      (recur (cons current remaining)))))

(defn- timer-text [{:keys [start-seconds end-seconds]} seconds]
  (let [elapsed (-> (- seconds start-seconds)
                    (max 0.0)
                    (min (- end-seconds start-seconds)))
        elapsed-seconds (long (Math/floor elapsed))]
    (format "%02d:%02d"
            (quot elapsed-seconds 60)
            (rem elapsed-seconds 60))))

(defn- draw-timer! [graphics timer seconds x y scale]
  (loop [characters (seq (timer-text timer seconds))
         cursor x]
    (when-let [character (first characters)]
      (let [glyph (get timer-glyphs character)
            columns (count (first glyph))]
        (doseq [row (range (count glyph))
                column (range columns)
                :when (= \1 (nth (nth glyph row) column))]
          (.fillRect graphics
                     (+ cursor (* column scale))
                     (+ y (* row scale))
                     scale
                     scale))
        (recur (next characters)
               (+ cursor (* (inc columns) scale)))))))

(defn- render-frame! [^BufferedImage image render-spec seconds]
  (let [g (.createGraphics image)
        width (.getWidth image)
        height (.getHeight image)
        horizontal-margin (max 1 (int (Math/round (* width 0.015))))
        vertical-margin (max 1 (int (Math/round (* height 0.02))))
        graph-left horizontal-margin
        graph-right (- width horizontal-margin 1)
        graph-top vertical-margin
        graph-bottom (- height vertical-margin 1)
        {:keys [samples minimum maximum]}
        (trace-samples render-spec seconds graph-left graph-right)
        minimum-heart-rate minimum
        maximum-heart-rate maximum
        heart-rate-range (max 1.0 (- maximum-heart-rate minimum-heart-rate))
        y-for (fn [heart-rate]
                (let [bounded (-> heart-rate
                                  (max minimum-heart-rate)
                                  (min maximum-heart-rate))]
                  (int (- graph-bottom
                          (* (/ (- bounded minimum-heart-rate) heart-rate-range)
                             (- graph-bottom graph-top))))))]
    (try
      (.setComposite g AlphaComposite/Clear)
      (.fillRect g 0 0 width height)
      (.setComposite g AlphaComposite/SrcOver)
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setColor g (Color. 255 55 82 255))
      (.setStroke g (BasicStroke. (float (max 1.5 (* height 0.0032)))
                                  BasicStroke/CAP_ROUND
                                  BasicStroke/JOIN_ROUND))
      (draw-series! g samples #(y-for (:heart-rate %)))
      (when-let [oxygen-samples
                 (spo2-samples render-spec seconds graph-left graph-right)]
        (let [minimum (:minimum spo2-contract)
              maximum (:maximum spo2-contract)
              y-for-spo2
              (fn [{:keys [value]}]
                (let [bounded (-> value (max minimum) (min maximum))]
                  (int (- graph-bottom
                          (* (/ (- bounded minimum) (- maximum minimum))
                             (- graph-bottom graph-top))))))]
          (let [[red green blue alpha] (:color spo2-contract)]
            (.setColor g (Color. red green blue alpha)))
          (draw-series! g oxygen-samples y-for-spo2)))
      (when-let [timer (:timer render-spec)]
        (let [scale (max 1 (int (Math/round (* height 0.004))))]
          (.setColor g Color/WHITE)
          (draw-timer! g timer seconds horizontal-margin vertical-margin scale)))
      (when-let [{:keys [image] :as watermark} (:watermark render-spec)]
        (.drawImage g
                    image
                    (- width horizontal-margin (:width watermark))
                    vertical-margin
                    nil))
      (finally
        (.dispose g)))))

(defrecord Java2dFrameRenderer []
  FrameRenderer
  (stream-frames! [_ {:keys [width height fps] :as render-spec} output]
    (let [{:keys [image rgba]} (rgba-surface width height)
          frames (spec/frame-count render-spec)]
      (dotimes [frame-index frames]
        (render-frame! image render-spec (/ (double frame-index) fps))
        (.write output ^bytes rgba 0 (alength ^bytes rgba)))
      (.flush output)
      {:frame-count frames
       :buffer-count 1}))
  (render-preview! [_ {:keys [width height duration-seconds] :as render-spec}
                    output]
    (let [{:keys [image]} (rgba-surface width height)
          midpoint (/ (double duration-seconds) 2.0)]
      (render-frame! image render-spec midpoint)
      (when-not (ImageIO/write image "png" output)
        (throw (ex-info "PNG encoder unavailable" {:type ::png-unavailable})))
      (.flush ^OutputStream output)
      {:width width :height height :at-seconds midpoint})))

(def java2d-frame-renderer (->Java2dFrameRenderer))

(defn stream!
  "Streams through the default bounded Java2D frame renderer."
  [render-spec output]
  (stream-frames! java2d-frame-renderer render-spec output))
