(ns agg.browser-process
  (:refer-clojure :exclude [run!])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.lang Process ProcessBuilder ProcessHandle Thread)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util UUID)
           (java.util.concurrent TimeUnit)))

(def ^:private max-output-bytes (* 16 1024 1024))
(def ^:private cleanup-grace-ms 3000)
(def ^:private outcome-pattern #"data-outcome=\"[^\"]+\"")

(defn- read-output [^InputStream input completion]
  (let [buffer (byte-array 8192)
        output (ByteArrayOutputStream.)]
    (loop [size 0]
      (let [read (.read input buffer)]
        (if (= -1 read)
          (do
            (deliver completion false)
            (.toString output StandardCharsets/UTF_8))
          (let [next-size (+ size read)]
            (when (> next-size max-output-bytes)
              (throw
               (ex-info "Browser fixture output exceeded its safe limit"
                        {:type ::output-limit
                         :phase :reading-output
                         :limit-bytes max-output-bytes})))
            (.write output buffer 0 read)
            (when (and (not (realized? completion))
                       (re-find
                        outcome-pattern
                        (.toString output StandardCharsets/ISO_8859_1)))
              (deliver completion true))
            (recur next-size)))))))

(defn- output-reader [^InputStream input]
  (let [completion (promise)
        outcome (promise)
        thread
        (Thread/startVirtualThread
         (fn []
           (deliver
            outcome
            (try
              {:output (read-output input completion)}
              (catch Throwable error
                (deliver completion false)
                {:error error})))))]
    {:completion completion :outcome outcome :thread thread}))

(defn- await-browser! [^Process process completion outcome timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (and (realized? completion) @completion)
        :outcome-complete

        (realized? outcome)
        (if (:error @outcome) :output-failed :output-closed)

        (not (.isAlive process))
        :process-exited

        (>= (System/nanoTime) deadline)
        :timeout

        :else
        (do
          (Thread/sleep 10)
          (recur))))))

(defn- current-descendants [^Process process]
  (let [handles (.descendants (.toHandle process))]
    (try
      (vec (.toList handles))
      (finally
        (.close handles)))))

(defn- remember-descendants! [observed process]
  (swap! observed
         (fn [known]
           (reduce (fn [result ^ProcessHandle handle]
                     (assoc result (.pid handle) handle))
                   known
                   (current-descendants process)))))

(defn- descendant-monitor [running? observed process]
  (Thread/startVirtualThread
   (fn []
     (try
       (while @running?
         (remember-descendants! observed process)
         (Thread/sleep 10))
       (catch InterruptedException _)
       (catch Throwable _)))))

(defn- signal! [^ProcessHandle handle forcibly?]
  (when (.isAlive handle)
    (try
      (if forcibly?
        (.destroyForcibly handle)
        (.destroy handle))
      (catch Throwable _
        false))))

(defn- wait-for-termination! [handles timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (when (and (some #(.isAlive ^ProcessHandle %) handles)
                 (< (System/nanoTime) deadline))
        (Thread/sleep 10)
        (recur)))))

(defn- stop-process-tree! [^Process process observed]
  (if-not process
    {:process-tree-terminated? true
     :observed-descendants 0}
    (let [root (.toHandle process)
          descendants (vals @observed)
          handles (vec (concat descendants [root]))]
      (doseq [handle descendants]
        (signal! handle false))
      (signal! root false)
      (wait-for-termination! handles (quot cleanup-grace-ms 2))
      (doseq [handle handles]
        (signal! handle true))
      (wait-for-termination! handles (quot cleanup-grace-ms 2))
      (.waitFor process cleanup-grace-ms TimeUnit/MILLISECONDS)
      {:process-tree-terminated? (not-any? #(.isAlive ^ProcessHandle %)
                                           handles)
       :observed-descendants (count descendants)})))

(defn- delete-tree! [^Path root]
  (try
    (when (.exists (.toFile root))
      (with-open [paths (Files/walk
                         root
                         (make-array java.nio.file.FileVisitOption 0))]
        (doseq [path (reverse (vec (.toList paths)))]
          (Files/deleteIfExists path))))
    (not (.exists (.toFile root)))
    (catch Throwable _
      false)))

(defn run!
  "Runs one isolated headless-browser fixture with bounded output and lifetime.
  Returns process output plus privacy-safe cleanup evidence."
  [{:keys [executable fixture location virtual-time-budget-ms timeout-ms
           browser-args]
    :or {browser-args []}}]
  (when-not (and (string? executable) (not-empty executable))
    (throw (IllegalArgumentException. "Browser executable is required")))
  (when-not (and (string? fixture) (not-empty fixture))
    (throw (IllegalArgumentException. "Browser fixture name is required")))
  (when-not (and (integer? timeout-ms) (pos? timeout-ms))
    (throw (IllegalArgumentException. "Browser fixture timeout must be positive")))
  (let [isolation-id (str (UUID/randomUUID))
        profile
        (Files/createTempDirectory
         (str "agg-chrome-" isolation-id "-")
         (make-array FileAttribute 0))
        command
        (into [executable
               "--headless=new"
               "--disable-gpu"
               "--no-sandbox"
               "--no-first-run"
               "--no-default-browser-check"
               (str "--user-data-dir=" profile)
               "--dump-dom"
               (str "--virtual-time-budget=" virtual-time-budget-ms)]
              (concat browser-args [(str location)]))
        process* (atom nil)
        reader* (atom nil)
        monitor* (atom nil)
        monitor-running? (atom true)
        observed (atom {})
        root-pid (atom nil)
        failure (atom nil)
        result (atom nil)
        cleanup (atom nil)
        interrupted? (atom false)]
    (try
      (let [builder (doto (ProcessBuilder. ^java.util.List command)
                      (.redirectErrorStream true))
            process (.start builder)]
        (reset! process* process)
        (reset! root-pid (.pid process))
        (remember-descendants! observed process)
        (reset! monitor* (descendant-monitor monitor-running? observed process))
        (reset! reader* (output-reader (.getInputStream process)))
        (let [{:keys [completion outcome]} @reader*
              status (await-browser! process completion outcome timeout-ms)]
          (case status
            :outcome-complete
            (reset! result {:exit 0 :completion :outcome-marker})

            :process-exited
            (reset! result {:exit (.exitValue process)
                            :completion :process-exit})

            :output-closed
            (reset! result {:exit (if (.isAlive process)
                                    0
                                    (.exitValue process))
                            :completion :output-closed})

            :output-failed
            (reset! failure
                    {:kind :error
                     :type ::output-failure
                     :phase :reading-output
                     :cause (:error @outcome)})

            :timeout
            (reset! failure
                    {:kind :timeout
                     :type ::timeout
                     :phase :browser-process}))))
      (catch InterruptedException error
        (reset! interrupted? true)
        (reset! failure
                {:kind :interrupted
                 :type ::interrupted
                 :phase :browser-process
                 :cause error}))
      (catch Throwable error
        (reset! failure
                {:kind :error
                 :type ::process-failure
                 :phase :starting-browser
                 :cause error}))
      (finally
        (when-let [process @process*]
          (try
            (remember-descendants! observed process)
            (catch Throwable _)))
        (reset! monitor-running? false)
        (when-let [monitor @monitor*]
          (.interrupt ^Thread monitor)
          (try
            (.join ^Thread monitor cleanup-grace-ms)
            (catch InterruptedException _
              (reset! interrupted? true))))
        (let [process-cleanup
              (try
                (stop-process-tree! @process* observed)
                (catch Throwable _
                  {:process-tree-terminated? false
                   :observed-descendants (count @observed)}))]
          (when-let [process @process*]
            (try
              (.close (.getInputStream ^Process process))
              (catch Throwable _)))
          (when-let [{:keys [thread]} @reader*]
            (.interrupt ^Thread thread)
            (try
              (.join ^Thread thread cleanup-grace-ms)
              (catch InterruptedException _
                (reset! interrupted? true))))
          (reset! cleanup
                  (assoc process-cleanup
                         :profile-removed? (delete-tree! profile))))))
    (when @interrupted?
      (.interrupt (Thread/currentThread)))
    (let [reader-outcome
          (when-let [{:keys [outcome]} @reader*]
            (deref outcome 0 nil))
          _ (when (and (nil? @failure) (:error reader-outcome))
              (reset! failure
                      {:kind :error
                       :type ::output-failure
                       :phase :reading-output
                       :cause (:error reader-outcome)}))
          diagnostics
          {:fixture fixture
           :timeout-ms timeout-ms
           :root-pid @root-pid
           :isolation-id isolation-id
           :cleanup @cleanup}]
      (if-let [{:keys [kind type phase cause]} @failure]
        (throw
         (ex-info
          (case kind
            :timeout "Browser fixture timed out"
            :interrupted "Browser fixture was interrupted"
            "Browser fixture process failed")
          (assoc diagnostics :type type :phase phase)
          cause))
        (merge @result
               {:output (or (:output reader-outcome) "")}
               diagnostics)))))
