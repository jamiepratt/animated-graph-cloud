(ns agg.render.audio
  (:require [agg.telemetry.timeline :as timeline])
  (:import (java.io OutputStream)))

(def ^:private sample-rate 48000)
(def ^:private channels 2)
(def ^:private bits-per-sample 16)

(defn- write-u16-le! [^OutputStream output value]
  (.write output (bit-and value 0xff))
  (.write output (bit-and (unsigned-bit-shift-right value 8) 0xff)))

(defn- write-u32-le! [^OutputStream output value]
  (dotimes [shift 4]
    (.write output (bit-and (unsigned-bit-shift-right value (* shift 8)) 0xff))))

(defn- write-header! [^OutputStream output sample-count]
  (let [block-align (* channels (quot bits-per-sample 8))
        data-bytes (* sample-count block-align)]
    (.write output (.getBytes "RIFF" "US-ASCII"))
    (write-u32-le! output (+ 36 data-bytes))
    (.write output (.getBytes "WAVEfmt " "US-ASCII"))
    (write-u32-le! output 16)
    (write-u16-le! output 1)
    (write-u16-le! output channels)
    (write-u32-le! output sample-rate)
    (write-u32-le! output (* sample-rate block-align))
    (write-u16-le! output block-align)
    (write-u16-le! output bits-per-sample)
    (.write output (.getBytes "data" "US-ASCII"))
    (write-u32-le! output data-bytes)))

(defn- pulse-waveform []
  (let [length (int (* sample-rate 0.14))
        pulse (short-array length)]
    (dotimes [index length]
      (let [seconds (/ (double index) sample-rate)
            first-beat (* (Math/sin (* 2.0 Math/PI 72.0 seconds))
                          (Math/exp (* -48.0 seconds)))
            delayed (max 0.0 (- seconds 0.075))
            second-beat (if (pos? delayed)
                          (* 0.55
                             (Math/sin (* 2.0 Math/PI 94.0 delayed))
                             (Math/exp (* -65.0 delayed)))
                          0.0)
            sample (-> (+ first-beat second-beat)
                       (* 12500.0)
                       (max -32768.0)
                       (min 32767.0)
                       Math/round)]
        (aset-short pulse index (short sample))))
    pulse))

(defn- beat-interval [render-spec sample-index]
  (let [seconds (/ (double sample-index) sample-rate)
        bpm (if (seq (:telemetry render-spec))
              (timeline/heart-rate-at-seconds (:telemetry render-spec) seconds)
              (+ 126.0
                 (* 19.0 (Math/sin (* seconds 0.073)))
                 (* 7.0 (Math/sin (* seconds 0.019)))))]
    (long (Math/round (/ (* sample-rate 60.0) bpm)))))

(defn beat-sample-indices
  "Returns deterministic heartbeat onsets for a render contract."
  [{:keys [duration-seconds] :as render-spec}]
  (let [sample-count (* sample-rate duration-seconds)]
    (loop [beat-at 0
           beats []]
      (if (< beat-at sample-count)
        (recur (+ beat-at (beat-interval render-spec beat-at))
               (conj beats beat-at))
        beats))))

(defn write-wav!
  "Writes deterministic stereo PCM heartbeat audio suitable as FFmpeg input."
  [{:keys [duration-seconds] :as render-spec} ^OutputStream output]
  (let [sample-count (* sample-rate duration-seconds)
        pulse (pulse-waveform)
        pulse-length (alength pulse)
        chunk-frames 4096
        chunk (byte-array (* chunk-frames channels 2))]
    (write-header! output sample-count)
    (loop [sample-index 0
           next-beat 0
           pulse-age pulse-length]
      (when (< sample-index sample-count)
        (let [frames (min chunk-frames (- sample-count sample-index))
              [new-next-beat new-pulse-age]
              (loop [offset 0
                     beat-at next-beat
                     age pulse-age]
                (if (= offset frames)
                  [beat-at age]
                  (let [absolute (+ sample-index offset)
                        starts-beat (>= absolute beat-at)
                        beat-at' (if starts-beat
                                   (+ absolute (beat-interval render-spec absolute))
                                   beat-at)
                        age' (if starts-beat 0 age)
                        sample (if (< age' pulse-length)
                                 (aget pulse age')
                                 0)
                        byte-offset (* offset 4)
                        low (unchecked-byte (bit-and sample 0xff))
                        high (unchecked-byte (bit-and (bit-shift-right sample 8) 0xff))]
                    (aset-byte chunk byte-offset low)
                    (aset-byte chunk (inc byte-offset) high)
                    (aset-byte chunk (+ byte-offset 2) low)
                    (aset-byte chunk (+ byte-offset 3) high)
                    (recur (inc offset) beat-at' (inc age')))))]
          (.write output chunk 0 (* frames channels 2))
          (recur (+ sample-index frames) new-next-beat new-pulse-age))))
    (.flush output)
    {:sample-rate sample-rate
     :channels channels
     :sample-count sample-count}))
