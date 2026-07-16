(ns agg.render.profile
  (:import (java.nio.file Files LinkOption Path)
           (java.time Duration)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong)
           (jdk.jfr Configuration Recording)
           (jdk.jfr.consumer RecordingFile)))

(def ^:private cgroup-current-paths
  [(Path/of "/sys/fs/cgroup/memory.current" (make-array String 0))
   (Path/of "/sys/fs/cgroup/memory/memory.usage_in_bytes" (make-array String 0))])

(def ^:private cgroup-peak-paths
  [(Path/of "/sys/fs/cgroup/memory.peak" (make-array String 0))
   (Path/of "/sys/fs/cgroup/memory/memory.max_usage_in_bytes" (make-array String 0))])

(defn- read-long [^Path path]
  (try
    (when (Files/exists path (make-array LinkOption 0))
      (parse-long (.trim (Files/readString path))))
    (catch Throwable _
      nil)))

(defn- first-readable [paths]
  (some read-long paths))

(defn start-memory-sampler []
  (let [running (AtomicBoolean. true)
        sampled-peak (AtomicLong. -1)
        thread (Thread.
                (fn []
                  (while (.get running)
                    (when-let [current (first-readable cgroup-current-paths)]
                      (.accumulateAndGet sampled-peak current max))
                    (try
                      (Thread/sleep 250)
                      (catch InterruptedException _))))
                "agg-cgroup-memory")]
    (.setDaemon thread true)
    (.start thread)
    {:stop!
     (fn []
       (.set running false)
       (.interrupt thread)
       (.join thread 1000)
       (let [sampled (.get sampled-peak)
             kernel-peak (first-readable cgroup-peak-paths)
             candidates (filter some? [(when-not (= -1 sampled) sampled) kernel-peak])]
         (when (seq candidates)
           (apply max candidates))))}))

(defn start-recording []
  (let [recording (Recording. (Configuration/getConfiguration "profile"))]
    (.setName recording "agg-render-profile")
    (.setMaxAge recording (Duration/ofHours 1))
    (.setMaxSize recording (* 256 1024 1024))
    (.start recording)
    recording))

(defn- event-long [event field]
  (try
    (.getLong event field)
    (catch Throwable _
      0)))

(defn summarize-recording [^Path path]
  (with-open [recording-file (RecordingFile. path)]
    (loop [allocation-samples 0
           sampled-allocation-bytes 0
           garbage-collections 0
           heap-summaries 0]
      (if (.hasMoreEvents recording-file)
        (let [event (.readEvent recording-file)
              event-name (some-> event .getEventType .getName)]
          (case event-name
            "jdk.ObjectAllocationSample"
            (recur (inc allocation-samples)
                   (+ sampled-allocation-bytes (event-long event "weight"))
                   garbage-collections
                   heap-summaries)

            "jdk.GarbageCollection"
            (recur allocation-samples
                   sampled-allocation-bytes
                   (inc garbage-collections)
                   heap-summaries)

            "jdk.GCHeapSummary"
            (recur allocation-samples
                   sampled-allocation-bytes
                   garbage-collections
                   (inc heap-summaries))

            (recur allocation-samples
                   sampled-allocation-bytes
                   garbage-collections
                   heap-summaries)))
        {:allocation-sample-count allocation-samples
         :sampled-allocation-bytes sampled-allocation-bytes
         :garbage-collection-count garbage-collections
         :heap-summary-count heap-summaries
         :recording-bytes (Files/size path)}))))

(defn finish-recording! [^Recording recording ^Path path]
  (try
    (.stop recording)
    (.dump recording path)
    (finally
      (.close recording)))
  (summarize-recording path))
