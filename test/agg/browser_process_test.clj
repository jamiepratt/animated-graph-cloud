(ns agg.browser-process-test
  (:require [agg.browser-process :as browser]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.io File IOException InputStream)
           (java.lang ProcessHandle)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- shell-quote [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn- executable! [^File file body]
  (spit file (str "#!/bin/sh\n" body))
  (is (.setExecutable file true))
  file)

(defn- process-alive? [pid]
  (when pid
    (let [process (ProcessHandle/of (long pid))]
      (and (.isPresent process)
           (.isAlive ^ProcessHandle (.get process))))))

(defn- delete-tree! [^File root]
  (when (.exists root)
    (with-open [paths (Files/walk (.toPath root)
                                  (make-array java.nio.file.FileVisitOption 0))]
      (doseq [path (reverse (vec (.toList paths)))]
        (Files/deleteIfExists path)))))

(deftest unresponsive-browser-is-bounded-cleaned-up-and-does-not-poison-retry
  (let [root (.toFile
              (Files/createTempDirectory
               "agg-fake-browser-"
               (make-array FileAttribute 0)))
        child-pid-file (io/file root "child.pid")
        hanging-browser
        (executable!
         (io/file root "hanging-browser")
         (str "sleep 300 &\n"
              "child=$!\n"
              "printf '%s' \"$child\" > "
              (shell-quote child-pid-file) "\n"
              "wait \"$child\"\n"))
        successful-browser
        (executable!
         (io/file root "successful-browser")
         "printf '%s\\n' \"$@\"\nprintf '<main data-outcome=\"e30=\"></main>\\n'\n")]
    (try
      (let [started-at (System/nanoTime)
            failure
            (try
              (browser/run!
               {:executable (str hanging-browser)
                :fixture "simulated unresponsive browser"
                :location "about:blank"
                :virtual-time-budget-ms 0
                :timeout-ms 500})
              nil
              (catch clojure.lang.ExceptionInfo error error))
            elapsed-ms (/ (- (System/nanoTime) started-at) 1000000)
            failure-data (ex-data failure)
            child-pid (when (.exists child-pid-file)
                        (parse-long (str/trim (slurp child-pid-file))))
            retry
            (browser/run!
             {:executable (str successful-browser)
              :fixture "successful retry"
              :location "about:blank"
              :virtual-time-budget-ms 0
              :timeout-ms 2000})]
        (is failure)
        (is (= ::browser/timeout (:type failure-data)))
        (is (= "simulated unresponsive browser" (:fixture failure-data)))
        (is (= :browser-process (:phase failure-data)))
        (is (= 500 (:timeout-ms failure-data)))
        (is (< elapsed-ms 5000))
        (is child-pid)
        (is (false? (process-alive? (:root-pid failure-data))))
        (is (false? (process-alive? child-pid)))
        (is (true? (get-in failure-data [:cleanup :process-tree-terminated?])))
        (is (true? (get-in failure-data [:cleanup :profile-removed?])))
        (is (= 0 (:exit retry)))
        (is (str/includes? (:output retry) "data-outcome=\"e30=\""))
        (is (some #(str/starts-with? % "--user-data-dir=")
                  (str/split-lines (:output retry))))
        (is (true? (get-in retry [:cleanup :process-tree-terminated?])))
        (is (true? (get-in retry [:cleanup :profile-removed?])))
        (is (not= (:isolation-id failure-data) (:isolation-id retry))))
      (finally
        (delete-tree! root)))))

(deftest cleanup-time-stream-close-keeps-captured-browser-output
  (let [payload "<main data-outcome=\"e30=\"></main>\n"
        bytes (.getBytes payload "UTF-8")
        cursor (atom 0)
        input (proxy [InputStream] []
                (read
                  ([] -1)
                  ([buffer]
                   (if (zero? @cursor)
                     (do
                       (System/arraycopy bytes 0 buffer 0 (alength bytes))
                       (reset! cursor (alength bytes))
                       (alength bytes))
                     (throw (IOException. "Stream closed"))))
                  ([buffer off len]
                   (if (zero? @cursor)
                     (let [size (min len (alength bytes))]
                       (System/arraycopy bytes 0 buffer off size)
                       (reset! cursor size)
                       size)
                     (throw (IOException. "Stream closed"))))))
        completion (promise)
        output (#'agg.browser-process/read-output input completion)]
    (is (= payload output))
    (is (true? @completion))))
