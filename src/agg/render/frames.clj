(ns agg.render.frames
  (:require [agg.errors :as errors]
            [agg.render.spec :as spec])
  (:import (java.awt AlphaComposite BasicStroke Color Font RenderingHints)
           (java.awt.image BufferedImage ComponentColorModel DataBuffer DataBufferByte Raster)
           (java.awt.color ColorSpace)
           (java.io OutputStream)
           (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)
           (javax.imageio ImageIO)))

(def trace-contract
  {:padding-ratio 0.10
   :minimum-heart-rate-padding 2.0
   :minimum-spo2-padding 0.5
   :tick-count 5})

(def spo2-contract
  {:color [52 200 235]})

(def heart-rate-color [255 55 82])
(def max-frame-progress-steps 10)

(def ^:private local-clock-formatter
  (DateTimeFormatter/ofPattern "HH:mm:ss" Locale/ROOT))

(defprotocol FrameRenderer
  (stream-frames! [renderer render-spec output]
    "Streams RGBA frames without retaining a frame collection."))

(defprotocol PreviewFrameRenderer
  (render-frame-png! [renderer render-spec frame-index output]
    "Writes one real production-timeline output frame as PNG."))

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
                                          java.awt.Transparency/TRANSLUCENT
                                          DataBuffer/TYPE_BYTE)]
    {:image (BufferedImage. color-model raster false nil)
     :rgba rgba}))

(defn- clamp [value minimum maximum]
  (min maximum (max minimum value)))

(defn- synthetic-heart-rate [seconds]
  (+ 126.0
     (* 19.0 (Math/sin (* seconds 0.073)))
     (* 7.0 (Math/sin (* seconds 0.019)))))

(defn- elapsed-text [seconds]
  (let [total-seconds (long (Math/floor (max 0.0 (double seconds))))
        hours (quot total-seconds 3600)
        minutes (quot (rem total-seconds 3600) 60)
        seconds (rem total-seconds 60)]
    (if (pos? hours)
      (format "%d:%02d:%02d" hours minutes seconds)
      (format "%02d:%02d" minutes seconds))))

(defn local-clock-text
  "Formats the section instant at elapsed production-frame time in its display zone."
  [^Instant section-start-at ^ZoneId display-time-zone seconds]
  (let [elapsed-nanos (long (Math/round (* (double seconds) 1000000000.0)))]
    (.format local-clock-formatter
             (.atZone (.plusNanos section-start-at elapsed-nanos)
                      display-time-zone))))

(defn readout-time-text
  "Formats the local video clock followed by section elapsed time."
  [^Instant section-start-at ^ZoneId display-time-zone seconds]
  (str (local-clock-text section-start-at display-time-zone seconds)
       "   "
       (elapsed-text seconds)))

(defn- timer-text [{:keys [start-seconds end-seconds]} seconds]
  (elapsed-text
   (-> (- seconds start-seconds)
       (max 0.0)
       (min (- end-seconds start-seconds)))))

(defn- color-with-alpha [[red green blue] alpha]
  (Color. (int red) (int green) (int blue) (int alpha)))

(defn- font-size [height ratio minimum maximum]
  (int (clamp (Math/round (* height ratio)) minimum maximum)))

(defn- x-for [graph-left graph-right duration-seconds seconds]
  (let [ratio (clamp (/ (double seconds)
                        (max 1.0 (double duration-seconds)))
                     0.0
                     1.0)]
    (int (Math/round (+ graph-left (* ratio (- graph-right graph-left)))))))

(defn- interpolate-value [left right key seconds]
  (let [left-seconds (double (:seconds left))
        right-seconds (double (:seconds right))
        span (- right-seconds left-seconds)]
    (if (zero? span)
      (double (get left key))
      (+ (double (get left key))
         (* (/ (- (double seconds) left-seconds) span)
            (- (double (get right key)) (double (get left key))))))))

(defn- resample-series [samples key duration-seconds sample-count]
  (let [samples (vec samples)
        last-index (dec (count samples))]
    (loop [target-index 0
           source-index 0
           result []]
      (if (= target-index sample-count)
        result
        (let [target-seconds (* (/ target-index (double (dec sample-count)))
                                (double duration-seconds))
              [source-index value]
              (loop [index source-index]
                (let [left (nth samples index)
                      right (nth samples (min last-index (inc index)))]
                  (if (or (= index last-index)
                          (<= target-seconds (:seconds right)))
                    [index (interpolate-value left right key target-seconds)]
                    (recur (inc index)))))]
          (recur (inc target-index)
                 source-index
                 (conj result {:seconds target-seconds
                               key value})))))))

(defn- series-samples [render-spec source-key value-key graph-left graph-right]
  (let [duration-seconds (:duration-seconds render-spec)
        sample-count (max 64
                          (min 720
                               (inc (quot (- graph-right graph-left) 2))))
        samples (get render-spec source-key)
        resampled (if (seq samples)
                    (resample-series samples value-key duration-seconds sample-count)
                    (when (= value-key :heart-rate)
                      (mapv (fn [index]
                              (let [seconds (* (/ index (double (dec sample-count)))
                                               (double duration-seconds))]
                                {:seconds seconds
                                 :heart-rate (synthetic-heart-rate seconds)}))
                            (range sample-count))))]
    (mapv #(assoc % :x (x-for graph-left graph-right duration-seconds (:seconds %)))
          resampled)))

(defn- nice-step [minimum maximum tick-count]
  (let [raw-step (/ (- maximum minimum) (max 1 (dec tick-count)))
        magnitude (Math/pow 10.0 (Math/floor (Math/log10 (max raw-step 1.0e-9))))
        normalized (/ raw-step magnitude)
        factor (cond
                 (<= normalized 1.0) 1.0
                 (<= normalized 2.0) 2.0
                 (<= normalized 5.0) 5.0
                 :else 10.0)]
    (* factor magnitude)))

(defn- axis-bounds [samples key minimum-padding]
  (let [values (mapv #(double (get % key)) samples)
        raw-minimum (apply min values)
        raw-maximum (apply max values)
        raw-range (max 1.0e-9 (- raw-maximum raw-minimum))
        padding (max (* raw-range (:padding-ratio trace-contract))
                     minimum-padding)
        padded-minimum (- raw-minimum padding)
        padded-maximum (+ raw-maximum padding)
        step (nice-step padded-minimum
                        padded-maximum
                        (:tick-count trace-contract))
        minimum (* step (Math/floor (/ padded-minimum step)))
        maximum (* step (Math/ceil (/ padded-maximum step)))]
    {:minimum minimum
     :maximum maximum
     :step step}))

(defn- axis-ticks [{:keys [minimum maximum step]}]
  (let [count (inc (int (Math/floor (/ (- maximum minimum) step))))]
    (mapv #(+ minimum (* % step)) (range count))))

(defn- y-for [{:keys [minimum maximum]} graph-top graph-bottom value]
  (let [ratio (/ (- (double value) minimum)
                 (max 1.0e-9 (- maximum minimum)))]
    (int (Math/round (- graph-bottom
                        (* (clamp ratio 0.0 1.0)
                           (- graph-bottom graph-top)))))))

(defn- value-at-series [samples key seconds]
  (let [seconds (clamp seconds
                       (:seconds (first samples))
                       (:seconds (last samples)))]
    (loop [[left right & remaining] samples]
      (cond
        (nil? right) (double (get left key))
        (<= seconds (:seconds right)) (interpolate-value left right key seconds)
        :else (recur (cons right remaining))))))

(defn- draw-series-segment! [graphics previous current y-for]
  (.drawLine graphics
             (:x previous)
             (y-for (get previous :value))
             (:x current)
             (y-for (get current :value))))

(defn- value-key-samples [samples key]
  (mapv #(assoc % :value (get % key)) samples))

(defn- crossing-point [previous current seconds]
  (let [ratio (/ (- seconds (:seconds previous))
                 (- (:seconds current) (:seconds previous)))]
    {:seconds seconds
     :x (int (Math/round (+ (:x previous)
                            (* ratio (- (:x current) (:x previous))))))
     :value (+ (:value previous)
               (* ratio (- (:value current) (:value previous))))}))

(defn- draw-future-aware-series!
  [graphics samples y-for seconds color stroke future-alpha]
  (let [future-color (color-with-alpha color future-alpha)
        present-color (color-with-alpha color 255)]
    (.setStroke graphics stroke)
    (doseq [[previous current] (partition 2 1 samples)]
      (cond
        (<= (:seconds current) seconds)
        (do
          (.setColor graphics present-color)
          (draw-series-segment! graphics previous current y-for))

        (>= (:seconds previous) seconds)
        (do
          (.setColor graphics future-color)
          (draw-series-segment! graphics previous current y-for))

        :else
        (let [crossing (crossing-point previous current seconds)]
          (.setColor graphics present-color)
          (draw-series-segment! graphics previous crossing y-for)
          (.setColor graphics future-color)
          (draw-series-segment! graphics crossing current y-for))))))

(defn- draw-timer-markers!
  [graphics {:keys [height graph-left graph-right graph-bottom
                    duration-seconds heart-rate-samples heart-rate-axis
                    graph-top timer future-trace-alpha]}
   seconds]
  (when timer
    (let [half-height (int (Math/round (* height 0.10)))
          stroke-width (float (max 1.5 (* height 0.0032)))
          dash-length (float (max 3.0 (* height 0.0125)))
          stroke (BasicStroke. stroke-width
                               BasicStroke/CAP_BUTT
                               BasicStroke/JOIN_MITER
                               10.0
                               (float-array [dash-length dash-length])
                               0.0)]
      (.setStroke graphics stroke)
      (doseq [event-seconds [(:start-seconds timer) (:end-seconds timer)]]
        (let [x (x-for graph-left graph-right duration-seconds event-seconds)
              y (y-for heart-rate-axis graph-top graph-bottom
                       (value-at-series heart-rate-samples
                                        :heart-rate event-seconds))
              alpha (if (<= event-seconds seconds) 255 future-trace-alpha)]
          (.setColor graphics (color-with-alpha heart-rate-color alpha))
          (.drawLine graphics x (- y half-height)
                     x (min graph-bottom (+ y half-height))))))))

(defn- draw-cursor! [graphics {:keys [x y]} color radius]
  (.setColor graphics Color/WHITE)
  (.fillOval graphics
             (- x radius 2)
             (- y radius 2)
             (+ (* 2 radius) 4)
             (+ (* 2 radius) 4))
  (.setColor graphics (color-with-alpha color 255))
  (.fillOval graphics
             (- x radius)
             (- y radius)
             (* 2 radius)
             (* 2 radius)))

(defn- number-text [value step]
  (if (>= step 1.0)
    (format "%.0f" (double value))
    (format "%.1f" (double value))))

(defn- draw-y-axis! [graphics axis x side graph-top graph-bottom color title font]
  (let [tick-size (max 2 (int (Math/round (* (.getSize font) 0.22))))
        label-gap (max 3 (int (Math/round (* (.getSize font) 0.35))))
        metrics (.getFontMetrics graphics font)]
    (.setFont graphics font)
    (.setColor graphics (color-with-alpha color 210))
    (.setStroke graphics (BasicStroke. 1.0))
    (.drawLine graphics x graph-top x graph-bottom)
    (doseq [tick (axis-ticks axis)]
      (let [y (y-for axis graph-top graph-bottom tick)
            label (number-text tick (:step axis))
            label-width (.stringWidth metrics label)
            tick-start (if (= side :left) x (- x tick-size))
            tick-end (if (= side :left) (+ x tick-size) x)
            label-x (if (= side :left)
                      (- x label-gap label-width)
                      (+ x label-gap))]
        (.drawLine graphics tick-start y tick-end y)
        (.drawString graphics label (int label-x) (int (+ y (/ (.getAscent metrics) 2))))))
    (.drawString graphics title
                 (int (if (= side :left)
                        (+ x label-gap)
                        (- x label-gap (.stringWidth metrics title))))
                 (int (+ graph-top (.getAscent metrics))))))

(defn- draw-x-axis! [graphics graph-left graph-right graph-bottom duration-seconds font]
  (let [metrics (.getFontMetrics graphics font)
        tick-count (max 2 (min 8 (inc (long duration-seconds))))
        tick-size (max 2 (int (Math/round (* (.getSize font) 0.22))))
        label-gap (max 3 (int (Math/round (* (.getSize font) 0.30))))]
    (.setFont graphics font)
    (.setColor graphics (color-with-alpha [255 255 255] 210))
    (.setStroke graphics (BasicStroke. 1.0))
    (.drawLine graphics graph-left graph-bottom graph-right graph-bottom)
    (doseq [index (range tick-count)]
      (let [ratio (/ index (double (dec tick-count)))
            x (int (Math/round (+ graph-left (* ratio (- graph-right graph-left)))))
            label (elapsed-text (* ratio duration-seconds))
            label-width (.stringWidth metrics label)]
        (.drawLine graphics x graph-bottom x (+ graph-bottom tick-size))
        (.drawString graphics label
                     (int (- x (/ label-width 2)))
                     (int (+ graph-bottom (.getAscent metrics) label-gap)))))))

(defn- text-parts-width [graphics font parts]
  (let [metrics (.getFontMetrics graphics font)]
    (reduce + (map #(.stringWidth metrics ^String (first %)) parts))))

(defn- draw-readout! [graphics layout seconds]
  (let [{:keys [width height]} layout
        font (:readout-font layout)
        heart-rate (value-at-series (:heart-rate-samples layout)
                                    :heart-rate
                                    seconds)
        parts (cond-> [[(readout-time-text (:section-start-at layout)
                                           (:display-time-zone layout)
                                           seconds)
                        [255 255 255]]
                       ["   HR " [255 255 255]]
                       [(format "%.0f" heart-rate) heart-rate-color]]
                (:spo2-samples layout)
                (into [["   SpO2 " [255 255 255]]
                       [(format "%.0f"
                                (value-at-series (:spo2-samples layout)
                                                 :spo2
                                                 seconds))
                        (:color spo2-contract)]])
                (:timer layout)
                (conj ["   Timer " [255 255 255]]
                      [(timer-text (:timer layout) seconds) [255 255 255]]))
        total-width (text-parts-width graphics font parts)
        metrics (.getFontMetrics graphics font)
        baseline (max (.getAscent metrics)
                      (int (Math/round (* height 0.065))))
        start-x (- width (:right-margin layout) total-width)]
    (.setFont graphics font)
    (loop [remaining parts
           x start-x]
      (when-let [[[text color] & remaining] (seq remaining)]
        (.setColor graphics (color-with-alpha color 255))
        (.drawString graphics ^String text (int x) baseline)
        (recur remaining (+ x (.stringWidth metrics ^String text)))))))

(defn- render-layout [{:keys [width height duration-seconds] :as render-spec}]
  (let [axis-font (Font. Font/SANS_SERIF Font/BOLD (font-size height 0.028 8 44))
        readout-font (Font. Font/SANS_SERIF Font/BOLD (font-size height 0.040 9 64))
        graph-left (max 2 (int (Math/round (* width 0.060))))
        right-margin (max 2 (int (Math/round (* width
                                                (if (seq (:spo2 render-spec))
                                                  0.060
                                                  0.025)))))
        graph-right (- width right-margin 1)
        graph-top (max 8 (int (Math/round (* height 0.120))))
        bottom-margin (max 14 (int (Math/round (* height 0.120))))
        graph-bottom (- height bottom-margin 1)
        hr-samples (series-samples render-spec :telemetry :heart-rate
                                   graph-left graph-right)
        spo2-samples (when (seq (:spo2 render-spec))
                       (series-samples render-spec :spo2 :spo2
                                       graph-left graph-right))
        future-trace-alpha
        (int (Math/round (* 255.0
                            (/ (double (get render-spec
                                            :future-trace-opacity-percent
                                            25))
                               100.0))))]
    {:width width
     :height height
     :duration-seconds duration-seconds
     :graph-left graph-left
     :graph-right graph-right
     :graph-top graph-top
     :graph-bottom graph-bottom
     :right-margin right-margin
     :axis-font axis-font
     :readout-font readout-font
     :section-start-at (:section-start-at render-spec)
     :display-time-zone (:display-time-zone render-spec)
     :heart-rate-samples hr-samples
     :future-trace-alpha future-trace-alpha
     :spo2-samples spo2-samples
     :heart-rate-axis (axis-bounds hr-samples
                                   :heart-rate
                                   (:minimum-heart-rate-padding trace-contract))
     :spo2-axis (when spo2-samples
                  (axis-bounds spo2-samples
                               :spo2
                               (:minimum-spo2-padding trace-contract)))
     :timer (:timer render-spec)
     :watermark (:watermark render-spec)}))

(defn- render-frame! [^BufferedImage image layout seconds]
  (let [g (.createGraphics image)
        {:keys [width height graph-left graph-right graph-top graph-bottom
                axis-font heart-rate-samples spo2-samples
                heart-rate-axis spo2-axis watermark future-trace-alpha]} layout
        heart-rate-y (fn [value]
                       (y-for heart-rate-axis graph-top graph-bottom value))
        spo2-y (fn [value]
                 (y-for spo2-axis graph-top graph-bottom value))
        stroke (BasicStroke. (float (max 1.5 (* height 0.0032)))
                             BasicStroke/CAP_ROUND
                             BasicStroke/JOIN_ROUND)
        cursor-radius (max 3 (int (Math/round (* height 0.008))))]
    (try
      (.setComposite g AlphaComposite/Clear)
      (.fillRect g 0 0 width height)
      (.setComposite g AlphaComposite/SrcOver)
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (draw-y-axis! g heart-rate-axis graph-left :left graph-top graph-bottom
                    heart-rate-color "HR (bpm)" axis-font)
      (when spo2-samples
        (draw-y-axis! g spo2-axis graph-right :right graph-top graph-bottom
                      (:color spo2-contract) "SpO2 (%)" axis-font))
      (draw-x-axis! g graph-left graph-right graph-bottom (:duration-seconds layout)
                    axis-font)
      (draw-future-aware-series! g
                                 (value-key-samples heart-rate-samples :heart-rate)
                                 heart-rate-y
                                 seconds
                                 heart-rate-color
                                 stroke
                                 future-trace-alpha)
      (when spo2-samples
        (draw-future-aware-series! g
                                   (value-key-samples spo2-samples :spo2)
                                   spo2-y
                                   seconds
                                   (:color spo2-contract)
                                   stroke
                                   128))
      (draw-timer-markers! g layout seconds)
      (let [heart-rate-seconds (clamp seconds 0.0 (:duration-seconds layout))
            heart-rate-point {:x (x-for graph-left graph-right
                                        (:duration-seconds layout)
                                        heart-rate-seconds)
                              :y (heart-rate-y
                                  (value-at-series heart-rate-samples
                                                   :heart-rate
                                                   heart-rate-seconds))}]
        (draw-cursor! g heart-rate-point heart-rate-color cursor-radius)
        (when spo2-samples
          (draw-cursor! g
                        {:x (:x heart-rate-point)
                         :y (spo2-y (value-at-series spo2-samples
                                                     :spo2
                                                     heart-rate-seconds))}
                        (:color spo2-contract)
                        cursor-radius)))
      (draw-readout! g layout seconds)
      (when watermark
        (.drawImage g
                    (:image watermark)
                    (- width (:right-margin layout) (:width watermark))
                    graph-top
                    nil))
      (finally
        (.dispose g)))))

(defn- plateau-runs [samples value-key]
  (loop [start 0
         runs []]
    (if (>= start (count samples))
      runs
      (let [value (double (get (nth samples start) value-key))
            end (loop [index (inc start)]
                  (if (and (< index (count samples))
                           (= value (double (get (nth samples index) value-key))))
                    (recur (inc index))
                    (dec index)))]
        (recur (inc end) (conj runs {:start start :end end :value value}))))))

(defn- side-minimum [values start step peak]
  (loop [index start
         minimum peak]
    (if (or (neg? index) (>= index (count values)))
      minimum
      (let [value (nth values index)]
        (if (> value peak)
          minimum
          (recur (+ index step) (min minimum value)))))))

(defn- prominent-events [samples value-key event-label invert?]
  (let [samples (vec samples)
        values (mapv (fn [sample]
                       (* (if invert? -1.0 1.0)
                          (double (get sample value-key))))
                     samples)
        candidates
        (keep
         (fn [{:keys [start end]}]
           (when (and (pos? start) (< end (dec (count samples))))
             (let [peak (nth values start)
                   left (nth values (dec start))
                   right (nth values (inc end))]
               (when (and (> peak left) (> peak right))
                 (let [index (quot (+ start end) 2)
                       left-minimum (side-minimum values (dec start) -1 peak)
                       right-minimum (side-minimum values (inc end) 1 peak)]
                   {:seconds (double (:seconds (nth samples index)))
                    :label event-label
                    :prominence (- peak (max left-minimum right-minimum))})))))
         (plateau-runs samples value-key))]
    (->> candidates
         (sort-by (juxt (comp - :prominence) :seconds))
         (take 3))))

(defn- frame-index [fps frame-count seconds]
  (-> (Math/round (* (double seconds) (double fps)))
      (max 0)
      (min (dec frame-count))
      long))

(defn- first-present-frame [fps frame-count seconds]
  (-> (Math/ceil (* (double seconds) (double fps)))
      long
      (max 0)
      (min (dec frame-count))))

(defn- last-present-frame [fps frame-count seconds]
  (-> (Math/floor (* (double seconds) (double fps)))
      long
      (max 0)
      (min (dec frame-count))))

(defn- elapsed-milliseconds [frame fps]
  (Math/round (* 1000.0 (/ (double frame) (double fps)))))

(defn- elapsed-moment-text [frame fps]
  (let [milliseconds (elapsed-milliseconds frame fps)
        minutes (quot milliseconds 60000)
        seconds (quot (rem milliseconds 60000) 1000)
        millis (rem milliseconds 1000)]
    (format "%02d:%02d.%03d" minutes seconds millis)))

(defn- trace-section [render-spec {:keys [id name unit samples value-key]}]
  (let [fps (:fps render-spec)
        frame-count (spec/frame-count render-spec)
        first-frame (first-present-frame fps frame-count (:seconds (first samples)))
        last-frame (last-present-frame fps frame-count (:seconds (last samples)))
        extrema (concat (prominent-events samples value-key
                                          "Prominent minimum" true)
                        (prominent-events samples value-key
                                          "Prominent maximum" false))
        events (concat [{:frame 0 :label "Video start"}]
                       [{:frame first-frame :label "Trace start"}]
                       (when-let [timer (:timer render-spec)]
                         [{:frame (frame-index fps frame-count
                                               (:start-seconds timer))
                           :label "Timer start"}])
                       (map (fn [{:keys [seconds label]}]
                              {:frame (frame-index fps frame-count seconds)
                               :label label})
                            extrema)
                       (when-let [timer (:timer render-spec)]
                         [{:frame (frame-index fps frame-count
                                               (:end-seconds timer))
                           :label "Timer end"}])
                       [{:frame last-frame :label "Trace stop"}]
                       [{:frame (dec frame-count) :label "Video end"}])
        by-frame (reduce (fn [result {:keys [frame label]}]
                           (update result frame (fnil conj []) label))
                         (sorted-map)
                         events)]
    {:id id
     :name name
     :unit unit
     :moments
     (mapv (fn [[frame labels]]
             (let [seconds (/ (double frame) fps)]
               {:frameIndex frame
                :elapsedSeconds seconds
                :elapsed (elapsed-moment-text frame fps)
                :labels (vec (distinct labels))
                :value (value-at-series samples value-key seconds)}))
           by-frame)}))

(defn preview-sections
  "Selects deterministic, frame-aligned key moments for every rendered trace."
  [render-spec]
  (cond-> [(trace-section render-spec
                          {:id "heart-rate"
                           :name "Heart rate"
                           :unit "bpm"
                           :samples (:telemetry render-spec)
                           :value-key :heart-rate})]
    (seq (:spo2 render-spec))
    (conj (trace-section render-spec
                         {:id "spo2"
                          :name "SpO2"
                          :unit "%"
                          :samples (:spo2 render-spec)
                          :value-key :spo2}))))

(defrecord Java2dFrameRenderer []
  FrameRenderer
  (stream-frames! [_ {:keys [width height fps] :as render-spec} output]
    (let [{:keys [image rgba]} (rgba-surface width height)
          layout (render-layout render-spec)
          frames (spec/frame-count render-spec)
          progress! (:frame-progress! render-spec)
          progress-interval (max 1 (long (Math/ceil
                                          (/ (double frames)
                                             max-frame-progress-steps))))]
      (when progress! (progress! 0))
      (dotimes [frame-index frames]
        (render-frame! image layout (/ (double frame-index) fps))
        (.write output ^bytes rgba 0 (alength ^bytes rgba))
        (let [completed (inc frame-index)]
          (when (and progress!
                     (or (= completed frames)
                         (zero? (rem completed progress-interval))))
            (progress! (min 100 (long (Math/round
                                       (* 100.0 (/ completed frames)))))))))
      (.flush output)
      {:frame-count frames
       :buffer-count 1}))
  PreviewFrameRenderer
  (render-frame-png! [_ {:keys [width height fps] :as render-spec}
                      frame-index output]
    (let [frame-count (spec/frame-count render-spec)]
      (when-not (and (integer? frame-index)
                     (<= 0 frame-index)
                     (< frame-index frame-count))
        (throw (errors/raise! "Preview moment must map to a real output frame"
                              {:type ::invalid-preview-frame
                               :reported frame-index
                               :limit frame-count})))
      (let [seconds (/ (double frame-index) fps)
            {:keys [image]} (rgba-surface width height)
            layout (render-layout render-spec)]
        (render-frame! image layout seconds)
        (when-not (ImageIO/write image "png" output)
          (throw (errors/raise! "PNG encoder unavailable"
                                {:type ::png-unavailable})))
        (.flush ^OutputStream output)
        {:width width
         :height height
         :frameIndex frame-index
         :elapsedSeconds seconds}))))

(def java2d-frame-renderer (->Java2dFrameRenderer))

(defn stream!
  "Streams through the default bounded Java2D frame renderer."
  [render-spec output]
  (stream-frames! java2d-frame-renderer render-spec output))
