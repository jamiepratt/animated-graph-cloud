(ns agg.render.derivative)

(def profile-v1
  {:version "h264-aac-1080p25-v1"
   :container {:format "mp4" :fast-start? true}
   :video {:codec "h264"
           :encoder "libx264"
           :profile "high"
           :level "4.0"
           :pixel-format "yuv420p"
           :fps 25
           :max-width 1920
           :max-height 1080
           :upscale? false
           :crf 23
           :preset "fast"
           :max-rate-bps 4000000
           :buffer-size-bps 8000000
           :gop-frames 50
           :scene-cut-keyframes? false}
   :audio {:codec "aac"
           :profile "aac_low"
           :sample-rate 48000
           :channels 2
           :bitrate-bps 128000
           :silence-when-missing? true}
   :verify #{:audio :codec :dimensions :duration :fast-start
             :size :stream-layout}})
