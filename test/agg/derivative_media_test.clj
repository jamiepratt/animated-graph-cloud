(ns agg.derivative-media-test
  (:require [agg.derivative.worker :as worker]
            [agg.drive.core :as drive]
            [agg.render.derivative :as derivative]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.io ByteArrayInputStream)
           (java.lang ProcessBuilder$Redirect)
           (java.net InetSocketAddress)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent TimeUnit)))

(defn- temp-path! [prefix suffix]
  (Files/createTempFile prefix suffix (make-array FileAttribute 0)))

(defn- run-command! [command]
  (let [process
        (-> (doto (ProcessBuilder. ^java.util.List command)
              (.redirectErrorStream true)
              (.redirectOutput ProcessBuilder$Redirect/DISCARD))
            (.start))]
    (is (.waitFor process 30 TimeUnit/SECONDS)
        (str "command timed out: " (first command)))
    (is (zero? (.exitValue process))
        (str "command failed: " (first command)))))

(defn- hevc-fixture! [^Path path width height audio?]
  (run-command!
   (cond->
    ["ffmpeg" "-hide_banner" "-nostdin" "-loglevel" "error"
     "-f" "lavfi" "-i"
     (str "testsrc2=size=" width "x" height ":rate=30")]
     audio?
     (into ["-f" "lavfi" "-i"
            "sine=frequency=440:sample_rate=44100"])
     true
     (into
      (concat ["-t" "1" "-c:v" "libx265" "-preset" "ultrafast"]
              (if audio?
                ["-c:a" "pcm_s16le"]
                ["-an"])
              ["-y" (str path)]))))
  path)

(defn- large-hvc1-fixture! [^Path path]
  (run-command!
   ["ffmpeg" "-hide_banner" "-nostdin" "-loglevel" "error"
    "-f" "lavfi" "-i" "testsrc2=size=2048x1152:rate=30"
    "-f" "lavfi" "-i" "sine=frequency=440:sample_rate=44100"
    "-t" "3"
    "-c:v" "libx265" "-preset" "ultrafast"
    "-x265-params" "lossless=1" "-tag:v" "hvc1"
    "-c:a" "aac"
    "-y" (str path)])
  path)

(defn- multi-video-hvc1-fixture! [^Path path]
  (run-command!
   ["ffmpeg" "-hide_banner" "-nostdin" "-loglevel" "error"
    "-f" "lavfi" "-i" "testsrc2=size=320x180:rate=30"
    "-f" "lavfi" "-i" "testsrc2=size=160x90:rate=30"
    "-f" "lavfi" "-i" "sine=frequency=440:sample_rate=44100"
    "-t" "1"
    "-map" "0:v:0" "-map" "1:v:0" "-map" "2:a:0"
    "-c:v" "libx265" "-preset" "ultrafast" "-tag:v" "hvc1"
    "-c:a" "aac"
    "-y" (str path)])
  path)

(defn- local-file-gateway [^bytes source]
  (let [size (alength source)]
    (reify drive/PlaybackGateway
      (open-source-range! [_ _ _ {:keys [start end]}]
        (let [length (inc (- end start))
              body (byte-array length)]
          (System/arraycopy source (int start) body 0 (int length))
          {:status 206
           :headers {"content-range" (str "bytes " start "-" end "/" size)
                     "content-length" (str length)}
           :body (ByteArrayInputStream. body)})))))

(defn- with-source-server [^Path path operation]
  (let [body (Files/readAllBytes path)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/source/opaque"
     (reify HttpHandler
       (handle [_ exchange]
         (.add (.getResponseHeaders exchange)
               "Content-Type" "application/octet-stream")
         (.sendResponseHeaders exchange 200 (alength body))
         (with-open [output (.getResponseBody exchange)]
           (.write output body)))))
    (.start server)
    (try
      (operation
       (str "http://127.0.0.1:"
            (.getPort (.getAddress server))
            "/source/opaque"))
      (finally
        (.stop server 0)))))

(defn- caught-data [operation]
  (try
    (operation)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest encode-command-locks-the-approved-browser-profile
  (let [command
        (derivative/encode-command
         {:ffmpeg "ffmpeg"
          :source-url "http://127.0.0.1:43123/source/opaque"
          :source-duration-seconds 480
          :source-has-audio? true
          :output-path "/tmp/derivative.mp4"})]
    (testing "the source is an identity-free loopback proxy"
      (is (= "http://127.0.0.1:43123/source/opaque"
             (nth command 6))))
    (testing "video is fixed, seekable, bounded, and browser playable"
      (doseq [argument ["libx264" "high" "4.0" "yuv420p" "25"
                        "fast" "23" "4000000" "8000000" "50"
                        "0" "+faststart"]]
        (is (some #{argument} command) argument))
      (let [filter-argument
            (nth command (inc (.indexOf command "-vf")))]
        (is (str/includes? filter-argument ",setsar=1,"))
        (is (not (str/includes? filter-argument "reset_sar=")))))
    (testing "audio is deterministic AAC-LC stereo"
      (doseq [argument ["aac" "aac_low" "48000" "2" "128000"]]
        (is (some #{argument} command) argument)))))

(deftest encode-command-generates-bounded-silence-when-audio-is-missing
  (let [command
        (derivative/encode-command
         {:ffmpeg "ffmpeg"
          :source-url "http://127.0.0.1:43123/source/opaque"
          :source-duration-seconds 17
          :source-has-audio? false
          :output-path "/tmp/derivative.mp4"})]
    (is (some #{"anullsrc=r=48000:cl=stereo"} command))
    (is (some #{"17.000"} command))))

(deftest encoder-rejects-source-identity-or-authority-in-its-command
  (let [data
        (caught-data
         #(derivative/encode-command
           {:ffmpeg "ffmpeg"
            :source-url
            "https://private-authority.example/source/private-drive-id"
            :source-duration-seconds 10
            :source-has-audio? true
            :output-path "/tmp/opaque-output.mp4"}))]
    (is (= ::derivative/invalid-encode-request (:type data)))
    (is (not (str/includes? (pr-str data) "private-authority")))
    (is (not (str/includes? (pr-str data) "private-drive-id")))))

(deftest browser-incompatible-source-produces-a-verified-derivative
  (let [source (temp-path! "agg-generated-hevc-" ".mkv")
        output (temp-path! "agg-generated-derivative-" ".mp4")]
    (try
      (hevc-fixture! source 320 180 true)
      (Files/deleteIfExists output)
      (with-source-server
        source
        (fn [source-url]
          (is (= {:width 320 :height 180 :audio? true}
                 (derivative/inspect-source!
                  {:ffprobe "ffprobe"
                   :source-url source-url
                   :timeout-ms 30000})))
          (is
           (=
            {:content-type "video/mp4"
             :output-bytes :positive
             :duration-seconds 1.0
             :video {:codec "h264"
                     :profile "High"
                     :level 40
                     :pixel-format "yuv420p"
                     :width 320
                     :height 180
                     :fps "25/1"}
             :audio {:codec "aac"
                     :profile "LC"
                     :sample-rate 48000
                     :channels 2}
             :fast-start? true}
            (-> (derivative/encode!
                 {:ffmpeg "ffmpeg"
                  :ffprobe "ffprobe"
                  :source-url source-url
                  :source-duration-seconds 1
                  :source-width 320
                  :source-height 180
                  :source-has-audio? true
                  :output-path output
                  :timeout-ms 30000
                  :cancelled? (constantly false)})
                (update :output-bytes
                        #(if (pos? %) :positive :not-positive))
                (dissoc :output-path))))))
      (finally
        (Files/deleteIfExists source)
        (Files/deleteIfExists output)))))

(deftest representative-hvc1-source-completes-the-worker-path-with-safe-margin
  (let [source (temp-path! "agg-generated-large-hvc1-" ".mp4")
        output (temp-path! "agg-generated-large-derivative-" ".mp4")]
    (try
      (large-hvc1-fixture! source)
      (Files/deleteIfExists output)
      (let [source-bytes (Files/readAllBytes source)
            stages (atom [])
            started (System/nanoTime)
            result
            (worker/run!
             {:classification :derivative-required
              :source-duration-seconds 3
              :source-bytes (alength source-bytes)
              :output-path output}
             {:proxy-config
              {:gateway (local-file-gateway source-bytes)
               :access-token "opaque"
               :file-id "opaque"}
              :inspect-source!
              #(derivative/inspect-source!
                (assoc % :timeout-ms 10000))
              :stage! #(swap! stages conj %)})
            elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
        (is (= :derivative-ready (:classification result)))
        (is (= [1920 1080]
               ((juxt :width :height) (:video result))))
        (is (> (get-in result [:transfer :upstream-bytes])
               (* 8 1024 1024)))
        (is (> (get-in result [:transfer :request-count]) 1))
        (is (= [:streaming-started
                :inspection-started
                :inspection-completed
                :ffmpeg-started
                :ffmpeg-completed
                :verification-started
                :verification-completed
                :streaming-stopped]
               @stages))
        (is (< elapsed-ms 60000)
            "synthetic HVC1 worker path must keep 14 minutes of headroom"))
      (finally
        (Files/deleteIfExists source)
        (Files/deleteIfExists output)))))

(deftest primary-video-with-auxiliary-video-completes-the-worker-path
  (let [source (temp-path! "agg-generated-multi-video-hvc1-" ".mp4")
        output (temp-path! "agg-generated-primary-video-derivative-" ".mp4")]
    (try
      (multi-video-hvc1-fixture! source)
      (Files/deleteIfExists output)
      (let [source-bytes (Files/readAllBytes source)
            result
            (worker/run!
             {:classification :derivative-required
              :source-duration-seconds 1
              :source-bytes (alength source-bytes)
              :output-path output}
             {:proxy-config
              {:gateway (local-file-gateway source-bytes)
               :access-token "opaque"
               :file-id "opaque"}})]
        (is (= :derivative-ready (:classification result)))
        (is (= [320 180]
               ((juxt :width :height) (:video result))))
        (is (= {:codec "aac"
                :profile "LC"
                :sample-rate 48000
                :channels 2}
               (:audio result))))
      (finally
        (Files/deleteIfExists source)
        (Files/deleteIfExists output)))))

(deftest portrait-audio-less-source-keeps-its-size-and-receives-silence
  (let [source (temp-path! "agg-generated-portrait-" ".mkv")
        output (temp-path! "agg-generated-portrait-derivative-" ".mp4")]
    (try
      (hevc-fixture! source 180 320 false)
      (Files/deleteIfExists output)
      (with-source-server
        source
        (fn [source-url]
          (let [result
                (derivative/encode!
                 {:ffmpeg "ffmpeg"
                  :ffprobe "ffprobe"
                  :source-url source-url
                  :source-duration-seconds 1
                  :source-width 180
                  :source-height 320
                  :source-has-audio? false
                  :output-path output})]
            (is (= [180 320]
                   ((juxt :width :height) (:video result))))
            (is (= {:codec "aac"
                    :profile "LC"
                    :sample-rate 48000
                    :channels 2}
                   (:audio result))))))
      (finally
        (Files/deleteIfExists source)
        (Files/deleteIfExists output)))))

(deftest approved-bitrate-ceiling-fits-the-maximum-duration
  (is (= 247680000
         (derivative/predicted-maximum-output-bytes 480)))
  (is (< (derivative/predicted-maximum-output-bytes 480)
         (* 256 1024 1024))))

(deftest runtime-boundaries-accept-the-exact-limit-and-reject-one-over
  (let [at-limit {:elapsed-ms 900000
                  :output-bytes 268435456}]
    (is (= at-limit (derivative/validate-runtime! at-limit)))
    (is (= "derivative_timeout"
           (:failure-code
            (caught-data
             #(derivative/validate-runtime! (update at-limit :elapsed-ms inc))))))
    (is (= "derivative_size_exceeded"
           (:failure-code
            (caught-data
             #(derivative/validate-runtime!
               (update at-limit :output-bytes inc))))))))

(deftest cancellation-timeout-and-verification-failure-remove-output
  (let [source (temp-path! "agg-generated-failure-source-" ".mkv")
        output (temp-path! "agg-generated-failure-output-" ".mp4")]
    (try
      (hevc-fixture! source 320 180 true)
      (doseq [[expected-type expected-failures overrides]
              [[::derivative/cancelled
                nil
                {:cancelled? (constantly true)}]
               [::derivative/timeout
                nil
                {:timeout-ms 1}]
               [::derivative/verification-failed
                ["probe_readable"]
                {:ffprobe "/usr/bin/false"}]]]
        (Files/deleteIfExists output)
        (with-source-server
          source
          (fn [source-url]
            (let [data
                  (caught-data
                   #(derivative/encode!
                     (merge
                      {:ffmpeg "ffmpeg"
                       :ffprobe "ffprobe"
                       :source-url source-url
                       :source-duration-seconds 1
                       :source-width 320
                       :source-height 180
                       :source-has-audio? true
                       :output-path output}
                      overrides)))]
              (is (= expected-type (:type data)))
              (when expected-failures
                (is (= expected-failures
                       (:verification-failures data)))))))
        (is (not (Files/exists
                  output (make-array java.nio.file.LinkOption 0)))))
      (finally
        (Files/deleteIfExists source)
        (Files/deleteIfExists output)))))

(deftest missing-output-reports-only-the-failed-presence-constraint
  (let [output (temp-path! "agg-missing-derivative-output-" ".mp4")]
    (Files/deleteIfExists output)
    (let [data
          (caught-data
           #(derivative/verify!
             {:ffprobe "ffprobe"
              :output-path output
              :source-duration-seconds 1
              :source-width 320
              :source-height 180}))]
      (is (= ::derivative/verification-failed (:type data)))
      (is (= "derivative_verification_failed" (:failure-code data)))
      (is (= ["output_present"] (:verification-failures data)))
      (is (= #{:type :source :failure-code :verification-failures}
             (set (keys data))))
      (is (not (str/includes? (pr-str data) (str output)))))))

(deftest invalid-or-premature-source-fails-with-bounded-diagnostics
  (let [source (temp-path! "agg-generated-invalid-source-" ".bin")
        output (temp-path! "agg-generated-invalid-output-" ".mp4")]
    (try
      (Files/write source (byte-array [1 2 3 4])
                   (make-array java.nio.file.OpenOption 0))
      (Files/deleteIfExists output)
      (with-source-server
        source
        (fn [source-url]
          (let [data
                (caught-data
                 #(derivative/encode!
                   {:ffmpeg "ffmpeg"
                    :ffprobe "ffprobe"
                    :source-url source-url
                    :source-duration-seconds 1
                    :source-width 320
                    :source-height 180
                    :source-has-audio? false
                    :output-path output}))]
            (is (= ::derivative/encode-failed (:type data)))
            (is (= "derivative_encode_failed" (:failure-code data)))
            (is (= #{:type :source :failure-code :exit-status}
                   (set (keys data)))))))
      (is (not (Files/exists
                output (make-array java.nio.file.LinkOption 0))))
      (finally
        (Files/deleteIfExists source)
        (Files/deleteIfExists output)))))

(deftest premature-video-eof-cannot-hide-behind-generated-silence
  (let [source (temp-path! "agg-generated-short-source-" ".mkv")
        output (temp-path! "agg-generated-short-output-" ".mp4")]
    (try
      (hevc-fixture! source 320 180 false)
      (Files/deleteIfExists output)
      (with-source-server
        source
        (fn [source-url]
          (let [data
                (caught-data
                 #(derivative/encode!
                   {:ffmpeg "ffmpeg"
                    :ffprobe "ffprobe"
                    :source-url source-url
                    :source-duration-seconds 2
                    :source-width 320
                    :source-height 180
                    :source-has-audio? false
                    :output-path output}))]
            (is (= ::derivative/verification-failed (:type data)))
            (is (= ["video_duration_match"]
                   (:verification-failures data)))
            (is (= #{:type :source :failure-code :verification-failures}
                   (set (keys data)))))))
      (is (not (Files/exists
                output (make-array java.nio.file.LinkOption 0))))
      (finally
        (Files/deleteIfExists source)
        (Files/deleteIfExists output)))))
