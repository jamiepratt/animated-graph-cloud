(ns agg.render.spec)

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
      (throw (ex-info "Unsupported renderer preset"
                      {:type ::unsupported-preset}))))

(defn with-duration [preset-map duration-seconds]
  (when-not (and (integer? duration-seconds)
                 (pos? duration-seconds)
                 (<= duration-seconds (:duration-seconds preset-map)))
    (throw (ex-info "Duration must be within the preset maximum"
                    {:type ::invalid-duration})))
  (assoc preset-map :duration-seconds duration-seconds))

(defn frame-count [{:keys [fps duration-seconds]}]
  (* fps duration-seconds))
