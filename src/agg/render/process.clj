(ns agg.render.process
  (:require [agg.errors :as errors]
            [agg.render.plan :as plan])
  (:import (java.io IOException OutputStream)
           (java.nio.file Files OpenOption)
           (java.util.concurrent TimeUnit)))

(def ^:private process-stop-grace-ms 1000)
(def ^:private timed-out (Object.))

(declare stop-process!)

(defn- stop-captured-process! [process captured error]
  (stop-process! process)
  (deref captured process-stop-grace-ms "")
  (when (instance? InterruptedException error)
    (.interrupt (Thread/currentThread)))
  (throw error))

(defn process-builder [command]
  (doto (ProcessBuilder. ^java.util.List command)
    (.redirectErrorStream true)))

(defn capture-output [input]
  (let [captured (promise)
        thread (Thread.
                (fn []
                  (deliver captured
                           (try
                             (slurp input)
                             (catch Throwable _
                               ""))))
                "agg-media-output")]
    (.setDaemon thread true)
    (.start thread)
    captured))

(defn run-captured!
  "Runs a media command, returning stdout while exposing only bounded errors."
  ([command]
   (let [process (.start (process-builder command))
         captured (capture-output (.getInputStream process))]
     (try
       (let [exit-status (.waitFor process)
             output @captured]
         (when-not (zero? exit-status)
           (throw (errors/raise! "Media tool failed"
                                 {:type ::tool-failed
                                  :exit-status exit-status})))
         output)
       (catch Throwable error
         (stop-captured-process! process captured error)))))
  ([command timeout-ms]
   (let [process (.start (process-builder command))
         captured (capture-output (.getInputStream process))]
     (try
       (if-not (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
         (throw (errors/raise! "Media tool exceeded its deadline"
                               {:type ::tool-timeout
                                :timeout-ms timeout-ms}))
         (let [exit-status (.exitValue process)
               output @captured]
           (when-not (zero? exit-status)
             (throw (errors/raise! "Media tool failed"
                                   {:type ::tool-failed
                                    :exit-status exit-status})))
           output))
       (catch Throwable error
         (stop-captured-process! process captured error))))))

(defn run-captured-as!
  "Runs a command while preserving a caller's established error taxonomy."
  ([command failure-type]
   (try
     (run-captured! command)
     (catch clojure.lang.ExceptionInfo error
       (if (= ::tool-failed (:type (ex-data error)))
         (throw (errors/raise! (.getMessage error)
                               (assoc (ex-data error) :type failure-type)
                               error))
         (throw error)))))
  ([command timeout-ms failure-type timeout-type]
   (try
     (run-captured! command timeout-ms)
     (catch clojure.lang.ExceptionInfo error
       (case (:type (ex-data error))
         ::tool-failed
         (throw (errors/raise! (.getMessage error)
                               (assoc (ex-data error) :type failure-type)
                               error))
         ::tool-timeout
         (throw (errors/raise! (.getMessage error)
                               (assoc (ex-data error) :type timeout-type)
                               error))
         (throw error))))))

(defn monitored-pipe-output [^OutputStream output write-error]
  (letfn [(record! [error]
            (compare-and-set! write-error nil error)
            (throw error))]
    (proxy [OutputStream] []
      (write
        ([value]
         (try
           (if (bytes? value)
             (.write output ^bytes value)
             (.write output (int value)))
           (catch IOException error
             (record! error))))
        ([buffer offset length]
         (try
           (.write output ^bytes buffer (int offset) (int length))
           (catch IOException error
             (record! error)))))
      (flush []
        (try
          (.flush output)
          (catch IOException error
            (record! error)))))))

(defn caused-by? [error cause]
  (loop [current error]
    (cond
      (nil? current) false
      (identical? current cause) true
      :else (recur (.getCause ^Throwable current)))))

(defn timeout-error [timeout-ms]
  (errors/raise! "FFmpeg compositing exceeded its deadline"
                 {:type :agg.render.media/composite-timeout
                  :failure-code "composition_timeout"
                  :stage "composition_encode"
                  :timeout-ms timeout-ms
                  :retryable true}))

(defn- fifo-path! [directory]
  (let [path (.resolve directory "overlay.rgba")
        child (.start (ProcessBuilder. ^java.util.List ["mkfifo" (str path)]))]
    (when-not (zero? (.waitFor child))
      (throw (errors/raise! "Could not create the overlay pipe"
                            {:type :agg.render.media/pipe-creation-failed})))
    path))

(defn- write-pipe! [stream-fn output result]
  (try
    (stream-fn output)
    (deliver result nil)
    (catch Throwable error
      (deliver result error))))

(defn- remaining-ms [deadline-nanos]
  (max 0 (long (Math/ceil
                (/ (- deadline-nanos (System/nanoTime)) 1000000.0)))))

(defn- delivered-error [result]
  (when (realized? result)
    (let [value @result]
      (when (instance? Throwable value) value))))

(defn- await-process! [^Process child overlay-result deadline-nanos timeout-ms]
  (loop []
    (when-let [overlay-error (delivered-error overlay-result)]
      (throw (errors/raise! "FFmpeg compositing failed"
                            {:type :agg.render.media/compositing-failed}
                            overlay-error)))
    (if-not (.isAlive child)
      (.exitValue child)
      (let [remaining (remaining-ms deadline-nanos)]
        (if (pos? remaining)
          (do
            (.waitFor child (min remaining 25) TimeUnit/MILLISECONDS)
            (recur))
          (throw (timeout-error timeout-ms)))))))

(defn- await-producer! [result deadline-nanos timeout-ms]
  (let [remaining (remaining-ms deadline-nanos)
        value (if (pos? remaining)
                (deref result remaining timed-out)
                timed-out)]
    (if (identical? timed-out value)
      (throw (timeout-error timeout-ms))
      value)))

(defn- wait-for-process! [^Process child timeout-ms]
  (let [deadline-nanos (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop [interrupted? false]
      (let [remaining (remaining-ms deadline-nanos)
            stopped?
            (try
              (or (not (.isAlive child))
                  (and (pos? remaining)
                       (.waitFor child remaining TimeUnit/MILLISECONDS)))
              (catch InterruptedException _
                ::interrupted))]
        (if (= ::interrupted stopped?)
          (recur true)
          (do
            (when interrupted?
              (.interrupt (Thread/currentThread)))
            stopped?))))))

(defn- stop-process! [^Process child]
  (when (.isAlive child)
    (.destroy child)
    (when-not (wait-for-process! child process-stop-grace-ms)
      (.destroyForcibly child)
      (wait-for-process! child process-stop-grace-ms))))

(defn- join-thread! [^Thread thread timeout-ms]
  (let [deadline-nanos (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop [interrupted? false]
      (let [remaining (remaining-ms deadline-nanos)
            joined?
            (try
              (when (and (.isAlive thread) (pos? remaining))
                (.join thread remaining))
              (not (.isAlive thread))
              (catch InterruptedException _
                ::interrupted))]
        (if (= ::interrupted joined?)
          (recur true)
          (do
            (when interrupted?
              (.interrupt (Thread/currentThread)))
            joined?))))))

(defn- quiesce-producers! [threads]
  (let [threads (vec (remove nil? threads))]
    (doseq [thread threads]
      (join-thread! thread 25))
    (doseq [^Thread thread threads
            :when (.isAlive thread)]
      (.interrupt thread))
    (doseq [thread threads]
      (join-thread! thread process-stop-grace-ms))
    (when-let [^Thread thread (first (filter #(.isAlive ^Thread %) threads))]
      (throw (IllegalStateException.
              (str "Composite producer did not stop: " (.getName thread)))))))

(defn- close-output! [^OutputStream output]
  (when output
    (try
      (.close output)
      (catch IOException _))))

(defn- unblock-overlay-open!
  [^Thread overlay-thread overlay-output overlay-pipe]
  (when (and (.isAlive overlay-thread) (nil? @overlay-output))
    (let [reader
          (.start
           (doto (ProcessBuilder.
                  ^java.util.List ["cat" (str overlay-pipe)])
             (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
             (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)))
          deadline (+ (System/nanoTime) (* 100 1000000))]
      (try
        (loop []
          (when (and (.isAlive overlay-thread)
                     (nil? @overlay-output)
                     (< (System/nanoTime) deadline))
            (Thread/sleep 1)
            (recur)))
        (close-output! @overlay-output)
        (finally
          (stop-process! reader))))))

(defn- delete-composite-directory! [overlay-pipe directory]
  (Files/deleteIfExists overlay-pipe)
  (Files/deleteIfExists directory))

(defn- encode-composite-attempt!
  [ffmpeg render-spec heartbeat-path output-path source-stream! write-overlay!
   deadline-nanos timeout-ms]
  (let [directory (Files/createTempDirectory
                   "agg-composite-pipe-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        overlay-pipe (fifo-path! directory)
        child (.start (process-builder
                       (plan/composite-command ffmpeg render-spec heartbeat-path
                                               overlay-pipe output-path)))
        captured (capture-output (.getInputStream child))
        seekable? (some? (plan/seekable-source-url render-spec))
        source-result (promise)
        source-pipe-write-error (atom nil)
        source-output (.getOutputStream child)
        overlay-result (promise)
        overlay-output (atom nil)
        source-thread
        (when-not seekable?
          (Thread.
           #(try
              (with-open [output source-output]
                (write-pipe! source-stream!
                             (monitored-pipe-output
                              output source-pipe-write-error)
                             source-result))
              (catch Throwable error
                (deliver source-result error)))
           "agg-drive-source"))
        overlay-thread
        (Thread.
         #(try
            (with-open [output (Files/newOutputStream
                                overlay-pipe
                                (make-array OpenOption 0))]
              (reset! overlay-output output)
              (try
                (write-pipe! write-overlay! output overlay-result)
                (finally
                  (reset! overlay-output nil))))
            (catch Throwable error
              (deliver overlay-result error)))
         "agg-overlay-pipe")]
    (when source-thread
      (.setDaemon source-thread true))
    (.setDaemon overlay-thread true)
    (try
      (if source-thread
        (.start source-thread)
        (do
          (.close source-output)
          (deliver source-result nil)))
      (.start overlay-thread)
      (let [exit-status (await-process! child overlay-result deadline-nanos
                                        timeout-ms)]
        (when-not (zero? exit-status)
          (throw (errors/raise! "FFmpeg compositing failed"
                                {:type :agg.render.media/compositing-failed
                                 :exit-status exit-status})))
        (let [source-error (await-producer! source-result deadline-nanos timeout-ms)
              overlay-error (await-producer! overlay-result deadline-nanos timeout-ms)]
          (when (and source-error
                     (not (caused-by? source-error
                                      @source-pipe-write-error)))
            (throw (errors/raise! "FFmpeg compositing failed"
                                  {:type :agg.render.media/compositing-failed
                                   :exit-status exit-status}
                                  source-error)))
          (when overlay-error
            (throw (errors/raise! "FFmpeg compositing failed"
                                  {:type :agg.render.media/compositing-failed
                                   :exit-status exit-status}
                                  overlay-error)))
          {:exit-status exit-status}))
      (finally
        (stop-process! child)
        (close-output! source-output)
        (close-output! @overlay-output)
        (unblock-overlay-open! overlay-thread overlay-output overlay-pipe)
        (quiesce-producers! [source-thread overlay-thread])
        (deref captured process-stop-grace-ms "")
        (delete-composite-directory! overlay-pipe directory)))))

(defn encode-composite!
  "Supervises FFmpeg and both producers against one shared deadline."
  [ffmpeg render-spec heartbeat-path output-path source-stream! write-overlay!
   default-timeout-ms]
  (let [timeout-ms (long (or (:timeout-ms render-spec) default-timeout-ms))
        deadline-nanos (+ (System/nanoTime) (* timeout-ms 1000000))]
    (try
      (encode-composite-attempt! ffmpeg render-spec heartbeat-path output-path
                                 source-stream! write-overlay!
                                 deadline-nanos timeout-ms)
      (catch clojure.lang.ExceptionInfo error
        (if (and (= "source+heartbeat" (:audio-mode render-spec))
                 (= :agg.render.media/compositing-failed
                    (:type (ex-data error))))
          (encode-composite-attempt!
           ffmpeg
           (assoc render-spec :audio-mode "heartbeat-only")
           heartbeat-path output-path source-stream! write-overlay!
           deadline-nanos timeout-ms)
          (throw error))))))
