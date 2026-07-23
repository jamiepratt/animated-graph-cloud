(ns agg.render.spec
  (:require [agg.errors :as errors]))

(def ^:private presets
  {"1080p25" {:id "1080p25"
              :width 1920
              :height 1080
              :fps 25
              :duration-seconds 480}
   "2.7k25" {:id "2.7k25"
             :width 2704
             :height 1520
             :fps 25
             :duration-seconds 240}})

(defn preset [id]
  (or (get presets id)
      (throw (errors/raise! "Unsupported renderer preset"
                            {:type ::unsupported-preset}))))

(defn with-duration [preset-map duration-seconds]
  (let [raw-duration-frames
        (when (number? duration-seconds)
          (* (double (:fps preset-map)) (double duration-seconds)))
        duration-frames
        (when (and raw-duration-frames
                   (Double/isFinite raw-duration-frames)
                   (< (Math/abs (- raw-duration-frames
                                   (Math/rint raw-duration-frames)))
                      1.0e-9))
          (long (Math/rint raw-duration-frames)))]
    (when-not (and duration-frames
                   (pos? duration-frames)
                   (<= duration-frames
                       (* (:fps preset-map)
                          (:duration-seconds preset-map))))
      (throw (errors/raise! "Duration must be a whole number of frames within the preset maximum"
                            {:type ::invalid-duration})))
    (assoc preset-map
           :duration-frames duration-frames
           :duration-seconds (/ duration-frames (:fps preset-map)))))

(defn with-frame-duration [preset-map duration-frames]
  (when-not (and (integer? duration-frames)
                 (pos? duration-frames)
                 (<= duration-frames
                     (* (:fps preset-map)
                        (:duration-seconds preset-map))))
    (throw (errors/raise! "Duration must be within the preset maximum"
                          {:type ::invalid-duration})))
  (assoc preset-map
         :duration-frames duration-frames
         :duration-seconds (/ duration-frames (:fps preset-map))))

(defn frame-count [{:keys [fps duration-frames duration-seconds]}]
  (or duration-frames (* fps duration-seconds)))
