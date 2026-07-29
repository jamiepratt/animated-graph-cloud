(ns agg.proto-playback-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.errors :as errors]
            [agg.http-test-support :as test-http]
            [agg.observability :as observability]
            [agg.proto.core :as proto]
            [agg.render.media :as media]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- with-probe-script [script f]
  (let [path
        (java.nio.file.Files/createTempFile
         "agg-safe-probe-" ".sh"
         (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (java.nio.file.Files/writeString
       path script (make-array java.nio.file.OpenOption 0))
      (is (.setExecutable (.toFile path) true))
      (f (str path))
      (finally
        (java.nio.file.Files/deleteIfExists path)))))

(defn- playback-fixture
  ([source-bytes ranges]
   (playback-fixture source-bytes ranges nil nil))
  ([source-bytes ranges open-range]
   (playback-fixture source-bytes ranges open-range nil))
  ([source-bytes ranges open-range inspect-playback]
   (let [gateway
         (reify
           drive/SourceGateway
           (source-metadata! [_ _ file-id]
             {:id file-id
              :name "source.mp4"
              :mimeType "video/mp4"
              :size (alength source-bytes)
              :trashed false})
           (stream-source! [_ _ _ _]
             (throw (AssertionError. "Playback must use ranged access")))
           drive/PlaybackGateway
           (open-source-range! [_ _ _ {:keys [start end] :as byte-range}]
             (swap! ranges conj byte-range)
             (if open-range
               (open-range byte-range)
               {:status 206
                :headers {"content-range"
                          (str "bytes " start "-" end "/"
                               (alength source-bytes))
                          "content-length" (str (inc (- end start)))}
                :body (java.io.ByteArrayInputStream.
                       (java.util.Arrays/copyOfRange source-bytes
                                                     start (inc end)))}))
           drive/PlaybackAnalysisGateway
           (inspect-playback! [_ _ _ metadata]
             (if inspect-playback
               (inspect-playback metadata)
               {:container {:format "mp4" :majorBrand "isom"}
                :video {:codec "h264" :codecTag "avc1"
                        :profile "High" :pixelFormat "yuv420p"}
                :audio {:codec "aac"}})))
         grant-store
         (reify auth/GrantStore
           (load-grant [_ _]
             {:refresh-token-ciphertext "kms:refresh"
              :folder-id "folder"})
           (save-grant! [_ _ grant] grant)
           (revoke-grant! [_ _] nil))
         cipher
         (reify auth/TokenCipher
           (encrypt-token! [_ value] (str "kms:" value))
           (decrypt-token! [_ value] (subs value 4)))
         token-client
         (reify auth/DriveTokenClient
           (refresh-drive-token! [_ _] {:access-token "drive-access"}))
         auth-system
         (auth/system
          {:client-id "client"
           :client-secret "secret"
           :base-url "https://proto.example.test"
           :allowlist #{"owner@example.test"}
           :session-key (.getBytes "01234567890123456789012345678901")
           :oauth (reify auth/OAuthClient
                    (exchange-code! [_ _ _ _ _]
                      (throw (UnsupportedOperationException.))))
           :grant-store grant-store
           :cipher cipher
           :drive gateway
           :drive-token-client token-client})
         subject "owner-subject"
         session (auth/issue-session
                  auth-system {:subject subject :email "owner@example.test"})]
     {:auth-system auth-system
      :session session
      :csrf (auth/issue-csrf-token auth-system {:subject subject})})))

(defn- create-playback! [port session csrf]
  (let [created
        (test-http/send-string!
         :post
         (str "http://127.0.0.1:" port "/v1/drive/playback-sessions")
         (json/write-str {:fileId "source"})
         {"Content-Type" "application/json"
          "Cookie" (str "agg_session=" session)
          "X-CSRF-Token" csrf})
        playback-url (get (json/read-str (.body created)) "playbackUrl")
        playback-cookie
        (-> (.firstValue (.headers created) "Set-Cookie")
            (.orElse "")
            (.split ";" 2)
            first)]
    (is (= 201 (.statusCode created)))
    {:url playback-url
     :cookie (str "agg_session=" session "; " playback-cookie)}))

(deftest proto-serves-the-hosting-range-adapter
  (let [port (test-http/available-port)
        server (api/start! port {:service-profile "proto"})]
    (try
      (let [response
            (test-http/send-string!
             :get
             (str "http://127.0.0.1:" port
                  "/proto-playback-range-worker.js")
             nil
             {})]
        (is (= 200 (.statusCode response)))
        (is (= "application/javascript; charset=utf-8"
               (.orElse (.firstValue (.headers response) "Content-Type") "")))
        (is (.contains ^String (.body response) "__agg_range"))
        (is (.contains ^String (.body response) "headers.get('Range')")))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest signed-in-proto-page-allows-only-the-same-origin-worker
  (let [port (test-http/available-port)
        {:keys [auth-system session]}
        (playback-fixture (.getBytes "0123456789") (atom []))
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system})]
    (try
      (let [response
            (test-http/send-string!
             :get
             (str "http://127.0.0.1:" port "/")
             nil
             {"Cookie" (str "agg_session=" session)})
            policy (.orElse
                    (.firstValue (.headers response)
                                 "Content-Security-Policy")
                    "")]
        (is (= 200 (.statusCode response)))
        (is (.contains ^String policy "worker-src 'self';"))
        (is (not (re-find #"worker-src [^;]*(?:https?:|\*)" policy))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest proto-page-registers-the-hosting-range-adapter-before-loading-sources
  (let [page (proto/page {:user {:email "owner@example.test"}
                          :csrf "csrf"
                          :folder-id "folder"})]
    (is (.contains ^String page
                   "serviceWorker.register('/proto-playback-range-worker.js'"))
    (is (.contains ^String page
                   "await prepareRangeAdapter();await loadSources()"))))

(deftest hosting-adapted-closed-range-remains-exact
  (let [port (test-http/available-port)
        ranges (atom [])
        source-bytes (.getBytes "0123456789abcdefghij")
        {:keys [auth-system session csrf]}
        (playback-fixture source-bytes ranges)
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system})]
    (try
      (let [{:keys [url cookie]} (create-playback! port session csrf)
            request
            (fn [range]
              (test-http/send-bytes!
               :get
               (str "http://127.0.0.1:" port url
                    (when range
                      (str "?__agg_range="
                           (java.net.URLEncoder/encode range "UTF-8"))))
               nil
               {"Cookie" cookie}))
            closed (request "bytes=6-10")
            open-ended (request "bytes=15-")
            suffix (request "bytes=-4")
            malformed (request "bytes=0-1,4-5")
            absent (request nil)
            header-wins
            (test-http/send-bytes!
             :get
             (str "http://127.0.0.1:" port url
                  "?__agg_range=bytes%3D0-2")
             nil
             {"Cookie" cookie
              "Range" "bytes=6-10"})]
        (doseq [[response content-range body-length]
                [[closed "bytes 6-10/20" 5]
                 [open-ended "bytes 15-19/20" 5]
                 [suffix "bytes 16-19/20" 4]
                 [absent "bytes 0-19/20" 20]
                 [header-wins "bytes 6-10/20" 5]]]
          (is (= 206 (.statusCode response)))
          (is (= content-range
                 (.orElse (.firstValue (.headers response) "Content-Range") "")))
          (is (= (str body-length)
                 (.orElse (.firstValue (.headers response) "Content-Length") "")))
          (is (= body-length (alength ^bytes (.body response))))
          (is (= "video/mp4"
                 (.orElse (.firstValue (.headers response) "Content-Type") "")))
          (is (= "bytes"
                 (.orElse (.firstValue (.headers response) "Accept-Ranges") "")))
          (is (= "no-store"
                 (.orElse (.firstValue (.headers response) "Cache-Control") "")))
          (is (= "nosniff"
                 (.orElse (.firstValue (.headers response)
                                       "X-Content-Type-Options")
                          ""))))
        (is (= 416 (.statusCode malformed)))
        (is (= "bytes */20"
               (.orElse (.firstValue (.headers malformed) "Content-Range") "")))
        (is (= [{:start 6 :end 10}
                {:start 15 :end 19}
                {:start 16 :end 19}
                {:start 0 :end 19}
                {:start 6 :end 10}]
               @ranges)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest playback-range-open-is-correlated-with-safe-request-evidence
  (let [port (test-http/available-port)
        ranges (atom [])
        events (atom [])
        source-bytes (.getBytes "0123456789abcdefghij")
        {:keys [auth-system session csrf]}
        (playback-fixture source-bytes ranges)
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system
                                 :revision "revision-1"
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj [event fields]))})]
    (try
      (let [{:keys [url cookie]} (create-playback! port session csrf)
            response
            (test-http/send-bytes!
             :get
             (str "http://127.0.0.1:" port url
                  "?__agg_range=bytes%3D6-10")
             nil
             {"Cookie" cookie
              "X-Cloud-Trace-Context"
              "0123456789abcdef0123456789abcdef/42;o=1"})
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") "")
            lifecycle-events
            (filterv
             #(contains?
               #{"drive_playback_range_received"
                 "drive_playback_range_resolved"
                 "drive_playback_range_opened"
                 "drive_playback_upstream_validated"
                 "drive_playback_transfer_succeeded"}
               (first %))
             @events)
            [_ fields]
            (some #(when (= "drive_playback_range_opened" (first %)) %)
                  @events)]
        (is (= 206 (.statusCode response)))
        (is (= ["drive_playback_range_received"
                "drive_playback_range_resolved"
                "drive_playback_range_opened"
                "drive_playback_upstream_validated"
                "drive_playback_transfer_succeeded"]
               (mapv first lifecycle-events)))
        (is (every? #(= request-id (:requestId (second %)))
                    lifecycle-events))
        (is (every?
             #(= "0123456789abcdef0123456789abcdef"
                 (:trace (second %)))
             lifecycle-events))
        (is (= {:operation "drive_playback_range"
                :status "received"
                :rangeSource "query"
                :receivedRange true}
               (select-keys
                (second (nth lifecycle-events 0))
                [:operation :status :rangeSource :receivedRange])))
        (is (= {:operation "drive_playback_range"
                :status "resolved"
                :rangeStart 6
                :rangeEnd 10
                :bytesRequested 5}
               (select-keys
                (second (nth lifecycle-events 1))
                [:operation :status :rangeStart :rangeEnd :bytesRequested])))
        (is (= request-id (:requestId fields)))
        (is (= {:rangeSource "query"
                :receivedRange true
                :rangeStart 6
                :rangeEnd 10
                :upstreamStatus 206
                :retryable false
                :trace "0123456789abcdef0123456789abcdef"
                :revision "revision-1"}
               (select-keys fields
                            [:rangeSource :receivedRange :rangeStart :rangeEnd
                             :upstreamStatus :retryable :trace :revision])))
        (is (<= 0 (:elapsedMs fields) 10000))
        (is (= {:operation "drive_playback_upstream_validation"
                :status "succeeded"
                :upstreamStatus 206
                :bytesRequested 5
                :retryable false}
               (select-keys
                (second (nth lifecycle-events 3))
                [:operation :status :upstreamStatus :bytesRequested
                 :retryable])))
        (is (= {:operation "drive_playback_transfer"
                :status "succeeded"
                :bytesRequested 5
                :bytesTransferred 5
                :retryable false}
               (select-keys
                (second (nth lifecycle-events 4))
                [:operation :status :bytesRequested :bytesTransferred
                 :retryable]))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest playback-analysis-events-share-the-browser-correlation
  (let [port (test-http/available-port)
        events (atom [])
        {:keys [auth-system session csrf]}
        (playback-fixture (.getBytes "0123456789") (atom []))
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system
                                 :revision "revision-1"
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj [event fields]))})]
    (try
      (let [trace-context
            "0123456789abcdef0123456789abcdef/42;o=1"
            response
            (test-http/send-string!
             :post
             (str "http://127.0.0.1:" port
                  "/v1/drive/playback-analyses")
             (json/write-str {:fileId "source"})
             {"Content-Type" "application/json"
              "Cookie" (str "agg_session=" session)
              "X-CSRF-Token" csrf
              "X-Cloud-Trace-Context" trace-context})
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") "")
            operation-events
            (filterv #(contains? #{"playback_analysis_started"
                                   "playback_analysis_succeeded"}
                                 (first %))
                     @events)]
        (is (= 200 (.statusCode response)))
        (is (= trace-context
               (.orElse
                (.firstValue (.headers response) "X-Cloud-Trace-Context")
                "")))
        (is (= ["playback_analysis_started"
                "playback_analysis_succeeded"]
               (mapv first operation-events)))
        (is (every?
             #(= {:operation "playback_analysis"
                  :requestId request-id
                  :trace "0123456789abcdef0123456789abcdef"
                  :revision "revision-1"}
                 (select-keys (second %)
                              [:operation :requestId :trace :revision]))
             operation-events))
        (is (= ["started" "succeeded"]
               (mapv (comp :status second) operation-events)))
        (is (every? #(<= 0 (:elapsedMs (second %)) 10000)
                    operation-events))
        (is (not (re-find
                  #"source|owner|access|fileId|fileName|token|credential"
                  (pr-str operation-events)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest playback-analysis-timeout-emits-one-bounded-failure
  (let [port (test-http/available-port)
        events (atom [])
        timeout-error
        (ex-info "private media details"
                 {:type :agg.render.media/media-tool-timeout
                  :timeout-ms 30000
                  :file-id "private-source"})
        {:keys [auth-system session csrf]}
        (playback-fixture
         (.getBytes "0123456789")
         (atom [])
         nil
         (fn [_] (throw timeout-error)))
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system
                                 :revision "revision-1"
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj [event fields]))})]
    (try
      (let [response
            (test-http/send-string!
             :post
             (str "http://127.0.0.1:" port
                  "/v1/drive/playback-analyses")
             (json/write-str {:fileId "source"})
             {"Content-Type" "application/json"
              "Cookie" (str "agg_session=" session)
              "X-CSRF-Token" csrf
              "X-Cloud-Trace-Context"
              "0123456789abcdef0123456789abcdef/42;o=1"})
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") "")
            failures
            (filterv #(= "playback_analysis_failed" (first %)) @events)
            fields (second (first failures))]
        (is (= 504 (.statusCode response)))
        (is (= 1 (count failures)))
        (is (= {:operation "playback_analysis"
                :status "failed"
                :reason "playback_analysis_timeout"
                :errorType ":agg.render.media/media-tool-timeout"
                :exceptionClass "clojure.lang.ExceptionInfo"
                :timeoutMs 30000
                :retryable true
                :requestId request-id
                :trace "0123456789abcdef0123456789abcdef"
                :revision "revision-1"}
               (select-keys
                fields
                [:operation :status :reason :errorType :exceptionClass
                 :timeoutMs :retryable :requestId :trace :revision])))
        (is (seq (:exceptionStack fields)))
        (is (every? string? (:exceptionStack fields)))
        (is (not (re-find
                  #"private|source|owner|access|token|credential|media details"
                  (pr-str fields)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest recording-clock-inspection-events-share-the-browser-correlation
  (let [port (test-http/available-port)
        events (atom [])
        source-bytes
        (.getBytes "movie mvhd 2026-07-23T14:30:15+02:00")
        {:keys [auth-system session csrf]}
        (playback-fixture source-bytes (atom []))
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system
                                 :revision "revision-1"
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj [event fields]))})]
    (try
      (let [response
            (test-http/send-string!
             :post
             (str "http://127.0.0.1:" port
                  "/v1/drive/recording-clock-inspections")
             (json/write-str {:fileId "source"})
             {"Content-Type" "application/json"
              "Cookie" (str "agg_session=" session)
              "X-CSRF-Token" csrf
              "X-Cloud-Trace-Context"
              "0123456789abcdef0123456789abcdef/42;o=1"})
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") "")
            operation-events
            (filterv #(contains?
                       #{"recording_clock_inspection_started"
                         "recording_clock_inspection_succeeded"}
                       (first %))
                     @events)]
        (is (= 200 (.statusCode response)))
        (is (= ["recording_clock_inspection_started"
                "recording_clock_inspection_succeeded"]
               (mapv first operation-events)))
        (is (= ["started" "succeeded"]
               (mapv (comp :status second) operation-events)))
        (is (every?
             #(= {:operation "recording_clock_inspection"
                  :requestId request-id
                  :trace "0123456789abcdef0123456789abcdef"
                  :revision "revision-1"}
                 (select-keys (second %)
                              [:operation :requestId :trace :revision]))
             operation-events))
        (is (every? #(<= 0 (:elapsedMs (second %)) 10000)
                    operation-events)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest recording-clock-drive-failure-emits-one-bounded-failure
  (let [port (test-http/available-port)
        events (atom [])
        fixture (playback-fixture (.getBytes "clock") (atom []))
        unavailable
        (reify
          drive/SourceGateway
          (source-metadata! [_ _ _]
            (throw
             (errors/raise! "private upstream details"
                            {:type ::drive/source-unavailable
                             :status 503
                             :file-id "private-source"})))
          (stream-source! [_ _ _ _]
            (throw (AssertionError. "Clock inspection must not stream")))
          drive/PlaybackGateway
          (open-source-range! [_ _ _ _]
            (throw (AssertionError. "Metadata failure must stop inspection"))))
        auth-system (assoc (:auth-system fixture) :drive unavailable)
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system
                                 :revision "revision-1"
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj [event fields]))})]
    (try
      (let [response
            (test-http/send-string!
             :post
             (str "http://127.0.0.1:" port
                  "/v1/drive/recording-clock-inspections")
             (json/write-str {:fileId "source"})
             {"Content-Type" "application/json"
              "Cookie" (str "agg_session=" (:session fixture))
              "X-CSRF-Token" (:csrf fixture)
              "X-Cloud-Trace-Context"
              "0123456789abcdef0123456789abcdef/42;o=1"})
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") "")
            failures
            (filterv #(= "recording_clock_inspection_failed" (first %))
                     @events)
            fields (second (first failures))]
        (is (= 503 (.statusCode response)))
        (is (= 1 (count failures)))
        (is (= {:operation "recording_clock_inspection"
                :status "failed"
                :reason "drive_source_unavailable"
                :errorType ":agg.drive.core/source-unavailable"
                :exceptionClass "clojure.lang.ExceptionInfo"
                :upstreamStatus 503
                :retryable true
                :requestId request-id
                :trace "0123456789abcdef0123456789abcdef"
                :revision "revision-1"}
               (select-keys
                fields
                [:operation :status :reason :errorType :exceptionClass
                 :upstreamStatus :retryable :requestId :trace :revision])))
        (is (seq (:exceptionStack fields)))
        (is (not (re-find
                  #"private-source|owner-subject|drive-access|fileId|fileName|token|credential|upstream details"
                  (pr-str fields)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest playback-session-creation-emits-one-correlated-event
  (let [port (test-http/available-port)
        events (atom [])
        {:keys [auth-system session csrf]}
        (playback-fixture (.getBytes "0123456789") (atom []))
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system
                                 :revision "revision-1"
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj [event fields]))})]
    (try
      (let [response
            (test-http/send-string!
             :post
             (str "http://127.0.0.1:" port
                  "/v1/drive/playback-sessions")
             (json/write-str {:fileId "source"})
             {"Content-Type" "application/json"
              "Cookie" (str "agg_session=" session)
              "X-CSRF-Token" csrf
              "X-Cloud-Trace-Context"
              "0123456789abcdef0123456789abcdef/42;o=1"})
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") "")
            created
            (filterv #(= "playback_session_created" (first %)) @events)
            fields (second (first created))]
        (is (= 201 (.statusCode response)))
        (is (= 1 (count created)))
        (is (= {:operation "playback_session_creation"
                :status "succeeded"
                :retryable false
                :requestId request-id
                :trace "0123456789abcdef0123456789abcdef"
                :revision "revision-1"}
               (select-keys fields
                            [:operation :status :retryable :requestId
                             :trace :revision])))
        (is (<= 0 (:elapsedMs fields) 10000))
        (is (not (re-find
                  #"source|owner|access|fileId|fileName|token|credential|playbackUrl"
                  (pr-str fields)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(defn- playback-failure-outcome [open-range]
  (let [port (test-http/available-port)
        events (atom [])
        source-bytes (.getBytes "0123456789abcdefghij")
        {:keys [auth-system session csrf]}
        (playback-fixture source-bytes (atom []) open-range)
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system
                                 :revision "revision-1"
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj [event fields]))})]
    (try
      (let [{:keys [url cookie]} (create-playback! port session csrf)
            response
            (test-http/send-string!
             :get
             (str "http://127.0.0.1:" port url
                  "?__agg_range=bytes%3D6-10")
             nil
             {"Cookie" cookie
              "X-Cloud-Trace-Context"
              "0123456789abcdef0123456789abcdef/42;o=1"})]
        {:status (.statusCode response)
         :body (json/read-str (.body response))
         :content-type
         (.orElse (.firstValue (.headers response) "Content-Type") "")
         :content-range
         (.orElse (.firstValue (.headers response) "Content-Range") "")
         :cache-control
         (.orElse (.firstValue (.headers response) "Cache-Control") "")
         :nosniff
         (.orElse (.firstValue (.headers response)
                               "X-Content-Type-Options")
                  "")
         :request-id
         (.orElse (.firstValue (.headers response) "X-Request-Id") "")
         :events @events})
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest upstream-open-and-validation-fail-before-media-headers
  (doseq [[outcome event operation reason upstream-status exception-class]
          [[(playback-failure-outcome
             (fn [_]
               (throw (java.io.IOException. "upstream unavailable"))))
            "drive_playback_open_failed"
            "drive_playback_open"
            "drive_playback_open_failed"
            0
            "java.io.IOException"]
           [(playback-failure-outcome
             (fn [_]
               {:status 200
                :headers {}
                :body (java.io.ByteArrayInputStream. (.getBytes "invalid"))}))
            "drive_playback_upstream_validation_failed"
            "drive_playback_upstream_validation"
            "drive_playback_validation_failed"
            200
            "clojure.lang.ExceptionInfo"]]]
    (let [failures (filterv #(= event (first %)) (:events outcome))
          fields (second (first failures))]
      (is (= 502 (:status outcome)))
      (is (= {"error" "drive_playback_unavailable"
              "retryable" true}
             (:body outcome)))
      (is (= "application/json; charset=utf-8" (:content-type outcome)))
      (is (= "" (:content-range outcome)))
      (is (= "no-store" (:cache-control outcome)))
      (is (= "nosniff" (:nosniff outcome)))
      (is (= 1 (count failures)))
      (is (= (:request-id outcome) (:requestId fields)))
      (is (= {:operation operation
              :status "failed"
              :reason reason
              :rangeSource "query"
              :receivedRange true
              :rangeStart 6
              :rangeEnd 10
              :upstreamStatus upstream-status
              :retryable true
              :trace "0123456789abcdef0123456789abcdef"
              :revision "revision-1"}
             (select-keys fields
                          [:operation :status :reason :rangeSource
                           :receivedRange :rangeStart :rangeEnd
                           :upstreamStatus :retryable :trace :revision])))
      (is (= exception-class (:exceptionClass fields)))
      (is (seq (:exceptionStack fields)))
      (is (<= 0 (:elapsedMs fields) 10000))
      (is (empty?
           (filter #(str/starts-with? (first %) "drive_playback_transfer_")
                   (:events outcome)))))))

(deftest malformed-range-presence-is-correlated-before-resolution-fails
  (let [port (test-http/available-port)
        events (atom [])
        {:keys [auth-system session csrf]}
        (playback-fixture (.getBytes "0123456789") (atom []))
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system
                                 :revision "revision-1"
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj [event fields]))})]
    (try
      (let [{:keys [url cookie]} (create-playback! port session csrf)
            response
            (test-http/send-string!
             :get
             (str "http://127.0.0.1:" port url)
             nil
             {"Cookie" cookie
              "Range" "bytes=0-1,4-5"
              "X-Cloud-Trace-Context"
              "0123456789abcdef0123456789abcdef/42;o=1"})
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") "")
            range-events
            (filterv #(str/starts-with? (first %) "drive_playback_range_")
                     @events)]
        (is (= 416 (.statusCode response)))
        (is (= ["drive_playback_range_received"
                "drive_playback_range_resolution_failed"]
               (mapv first range-events)))
        (is (= {:operation "drive_playback_range"
                :status "received"
                :rangeSource "header"
                :receivedRange true
                :requestId request-id}
               (select-keys
                (second (first range-events))
                [:operation :status :rangeSource :receivedRange
                 :requestId])))
        (is (= {:operation "drive_playback_range"
                :status "failed"
                :reason "playback_range_not_satisfiable"
                :rangeSource "header"
                :receivedRange true
                :retryable false
                :requestId request-id
                :trace "0123456789abcdef0123456789abcdef"
                :revision "revision-1"}
               (select-keys
                (second (second range-events))
                [:operation :status :reason :rangeSource :receivedRange
                 :retryable :requestId :trace :revision]))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest transfer-read-failure-emits-one-correlated-bounded-event
  (let [port (test-http/available-port)
        events (atom [])
        failing-input
        (proxy [java.io.InputStream] []
          (read
            ([]
             (throw (java.io.IOException. "private media bytes")))
            ([_buffer]
             (throw (java.io.IOException. "private media bytes")))
            ([_buffer _offset _length]
             (throw (java.io.IOException. "private media bytes")))))
        {:keys [auth-system session csrf]}
        (playback-fixture
         (.getBytes "0123456789")
         (atom [])
         (fn [{:keys [start end]}]
           {:status 206
            :headers {"content-range" (str "bytes " start "-" end "/10")
                      "content-length" (str (inc (- end start)))}
            :body failing-input}))
        server (api/start! port {:service-profile "proto"
                                 :auth-system auth-system
                                 :revision "revision-1"
                                 :event-sink
                                 (fn [event fields]
                                   (swap! events conj [event fields]))})]
    (try
      (let [{:keys [url cookie]} (create-playback! port session csrf)]
        (try
          (test-http/send-bytes!
           :get
           (str "http://127.0.0.1:" port url)
           nil
           {"Cookie" cookie
            "Range" "bytes=2-6"
            "X-Cloud-Trace-Context"
            "0123456789abcdef0123456789abcdef/42;o=1"})
          (catch Throwable _))
        (let [failures
              (filterv #(= "drive_playback_transfer_failed" (first %))
                       @events)
              fields (second (first failures))]
          (is (= 1 (count failures)))
          (is (= {:operation "drive_playback_transfer"
                  :status "failed"
                  :reason "drive_playback_read_failed"
                  :rangeSource "header"
                  :receivedRange true
                  :rangeStart 2
                  :rangeEnd 6
                  :bytesRequested 5
                  :bytesTransferred 0
                  :upstreamStatus 206
                  :retryable true
                  :trace "0123456789abcdef0123456789abcdef"
                  :revision "revision-1"}
                 (select-keys
                  fields
                  [:operation :status :reason :rangeSource :receivedRange
                   :rangeStart :rangeEnd :bytesRequested :bytesTransferred
                   :upstreamStatus :retryable :trace :revision])))
          (is (= "java.io.IOException" (:exceptionClass fields)))
          (is (seq (:exceptionStack fields)))
          (is (not (re-find #"private media bytes" (pr-str fields))))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest ffprobe-start-and-exit-inherit-the-request-correlation
  (let [events (atom [])
        script
        (str "#!/bin/sh\n"
             "printf '%s' "
             "'{\"format\":{\"format_name\":\"mov\","
             "\"tags\":{\"major_brand\":\"qt  \"}},"
             "\"streams\":[{\"codec_type\":\"video\","
             "\"codec_name\":\"h264\",\"codec_tag_string\":\"avc1\","
             "\"profile\":\"High\",\"pix_fmt\":\"yuv420p\"}]}'\n")]
    (with-probe-script
      script
      (fn [ffprobe]
        (observability/with-event-context
          {:fields {:requestId "request-1"
                    :trace "0123456789abcdef0123456789abcdef"
                    :revision "revision-1"}
           :event-sink
           (fn [event fields]
             (swap! events conj [event fields]))}
          (is (= "h264"
                 (get-in
                  (media/inspect-browser-playback-file!
                   ffprobe "private-media-path")
                  [:video :codec]))))))
    (let [probe-events
          (filterv #(str/starts-with? (first %) "ffprobe_") @events)]
      (is (= ["ffprobe_started" "ffprobe_exited"]
             (mapv first probe-events)))
      (is (= ["started" "succeeded"]
             (mapv (comp :status second) probe-events)))
      (is (every?
           #(= {:operation "ffprobe"
                :requestId "request-1"
                :trace "0123456789abcdef0123456789abcdef"
                :revision "revision-1"}
               (select-keys (second %)
                            [:operation :requestId :trace :revision]))
           probe-events))
      (is (= 30000 (:timeoutMs (second (first probe-events)))))
      (is (= 0 (:upstreamStatus (second (second probe-events)))))
      (is (every? #(<= 0 (:elapsedMs (second %)) 10000) probe-events))
      (is (not (re-find
                #"private-media-path|format_name|codec_name|command|output"
                (pr-str probe-events)))))))

(deftest ffprobe-nonzero-exit-has-one-bounded-failure-classification
  (let [events (atom [])]
    (with-probe-script
      "#!/bin/sh\nexit 7\n"
      (fn [ffprobe]
        (observability/with-event-context
          {:fields {:requestId "request-1"
                    :trace "0123456789abcdef0123456789abcdef"
                    :revision "revision-1"}
           :event-sink
           (fn [event fields]
             (swap! events conj [event fields]))}
          (try
            (media/inspect-browser-playback-file!
             ffprobe "private-media-path")
            (is false "Nonzero ffprobe must fail")
            (catch clojure.lang.ExceptionInfo _)))))
    (let [probe-events
          (filterv #(str/starts-with? (first %) "ffprobe_") @events)
          exit-fields (second (get probe-events 1))
          failure-fields (second (get probe-events 2))]
      (is (= ["ffprobe_started" "ffprobe_exited" "ffprobe_failed"]
             (mapv first probe-events)))
      (is (= {:operation "ffprobe"
              :status "failed"
              :upstreamStatus 7
              :retryable true}
             (select-keys exit-fields
                          [:operation :status :upstreamStatus :retryable])))
      (is (= {:operation "ffprobe"
              :status "failed"
              :reason "nonzero_exit"
              :errorType ":agg.render.media/media-tool-failed"
              :exceptionClass "clojure.lang.ExceptionInfo"
              :upstreamStatus 7
              :retryable true
              :requestId "request-1"
              :trace "0123456789abcdef0123456789abcdef"
              :revision "revision-1"}
             (select-keys
              failure-fields
              [:operation :status :reason :errorType :exceptionClass
               :upstreamStatus :retryable :requestId :trace :revision])))
      (is (seq (:exceptionStack failure-fields)))
      (is (not (re-find
                #"private-media-path|:command|:output|private media"
                (pr-str probe-events)))))))

(deftest ffprobe-timeout-emits-one-bounded-timeout-classification
  (let [events (atom [])]
    (with-probe-script
      "#!/bin/sh\nsleep 1\n"
      (fn [ffprobe]
        (observability/with-event-context
          {:fields {:requestId "request-1"
                    :trace "0123456789abcdef0123456789abcdef"
                    :revision "revision-1"}
           :event-sink
           (fn [event fields]
             (swap! events conj [event fields]))}
          (try
            (media/inspect-browser-playback-file!
             ffprobe "private-media-path" 25)
            (is false "Timed-out ffprobe must fail")
            (catch clojure.lang.ExceptionInfo _)))))
    (let [probe-events
          (filterv #(str/starts-with? (first %) "ffprobe_") @events)
          timeout-fields (second (get probe-events 1))]
      (is (= ["ffprobe_started" "ffprobe_timed_out"]
             (mapv first probe-events)))
      (is (= {:operation "ffprobe"
              :status "failed"
              :reason "deadline_exceeded"
              :errorType ":agg.render.media/media-tool-timeout"
              :exceptionClass "clojure.lang.ExceptionInfo"
              :timeoutMs 25
              :retryable true
              :requestId "request-1"
              :trace "0123456789abcdef0123456789abcdef"
              :revision "revision-1"}
             (select-keys
              timeout-fields
              [:operation :status :reason :errorType :exceptionClass
               :timeoutMs :retryable :requestId :trace :revision])))
      (is (seq (:exceptionStack timeout-fields)))
      (is (not (re-find
                #"private-media-path|:command|:output"
                (pr-str probe-events)))))))

(deftest ffprobe-unusable-evidence-has-one-bounded-failure-classification
  (let [events (atom [])]
    (with-probe-script
      "#!/bin/sh\nprintf '%s' '{\"format\":{},\"streams\":[]}'\n"
      (fn [ffprobe]
        (observability/with-event-context
          {:fields {:requestId "request-1"
                    :trace "0123456789abcdef0123456789abcdef"
                    :revision "revision-1"}
           :event-sink
           (fn [event fields]
             (swap! events conj [event fields]))}
          (try
            (media/inspect-browser-playback-file!
             ffprobe "private-media-path")
            (is false "Unusable evidence must fail")
            (catch clojure.lang.ExceptionInfo _)))))
    (let [probe-events
          (filterv #(str/starts-with? (first %) "ffprobe_") @events)
          failure-fields (second (get probe-events 2))]
      (is (= ["ffprobe_started" "ffprobe_exited" "ffprobe_failed"]
             (mapv first probe-events)))
      (is (= {:operation "ffprobe"
              :status "failed"
              :reason "invalid_evidence"
              :errorType ":agg.render.media/invalid-source-inspection"
              :exceptionClass "clojure.lang.ExceptionInfo"
              :retryable false
              :requestId "request-1"
              :trace "0123456789abcdef0123456789abcdef"
              :revision "revision-1"}
             (select-keys
              failure-fields
              [:operation :status :reason :errorType :exceptionClass
               :retryable :requestId :trace :revision])))
      (is (seq (:exceptionStack failure-fields)))
      (is (not (re-find
                #"private-media-path|:command|:output|streams"
                (pr-str probe-events)))))))

(deftest playback-observability-filter-allows-only-defined-domain-values
  (let [safe
        (observability/safe-event-fields
         {:component "api"
          :event "ffprobe_failed"
          :operation "ffprobe"
          :status "failed"
          :exceptionStack ["agg.render.media/run:media.clj:123"]})
        rejected
        (observability/safe-event-fields
         {:component "api"
          :event "ffprobe_failed"
          :operation "private-source"
          :status "private-state"
          :exceptionStack ["private-media-content"]})]
    (is (= {:component "api"
            :event "ffprobe_failed"
            :operation "ffprobe"
            :status "failed"
            :exceptionStack ["agg.render.media/run:media.clj:123"]}
           safe))
    (is (= {:component "api"
            :event "ffprobe_failed"}
           rejected))))

(deftest http-playback-analysis-propagates-correlation-into-ffprobe
  (let [script
        (str "#!/bin/sh\n"
             "printf '%s' "
             "'{\"format\":{\"format_name\":\"mov\","
             "\"tags\":{\"major_brand\":\"qt  \"}},"
             "\"streams\":[{\"codec_type\":\"video\","
             "\"codec_name\":\"h264\",\"codec_tag_string\":\"avc1\"}]}'\n")]
    (with-probe-script
      script
      (fn [ffprobe]
        (let [port (test-http/available-port)
              events (atom [])
              fixture
              (playback-fixture
               (.getBytes "0123456789")
               (atom [])
               nil
               (fn [_]
                 (media/inspect-browser-playback-file!
                  ffprobe "private-media-path")))
              server
              (api/start!
               port
               {:service-profile "proto"
                :auth-system (:auth-system fixture)
                :revision "revision-1"
                :event-sink
                (fn [event fields]
                  (swap! events conj [event fields]))})]
          (try
            (let [response
                  (test-http/send-string!
                   :post
                   (str "http://127.0.0.1:" port
                        "/v1/drive/playback-analyses")
                   (json/write-str {:fileId "source"})
                   {"Content-Type" "application/json"
                    "Cookie" (str "agg_session=" (:session fixture))
                    "X-CSRF-Token" (:csrf fixture)
                    "X-Cloud-Trace-Context"
                    "0123456789abcdef0123456789abcdef/42;o=1"})
                  request-id
                  (.orElse
                   (.firstValue (.headers response) "X-Request-Id")
                   "")
                  probe-events
                  (filterv #(str/starts-with? (first %) "ffprobe_")
                           @events)]
              (is (= 200 (.statusCode response)))
              (is (= ["ffprobe_started" "ffprobe_exited"]
                     (mapv first probe-events)))
              (is (every?
                   #(= {:requestId request-id
                        :trace "0123456789abcdef0123456789abcdef"
                        :revision "revision-1"}
                       (select-keys (second %)
                                    [:requestId :trace :revision]))
                   probe-events)))
            (finally
              (.close ^java.lang.AutoCloseable server))))))))
