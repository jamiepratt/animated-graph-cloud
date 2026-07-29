(ns agg.proto-playback-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.http-test-support :as test-http]
            [agg.proto.core :as proto]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]))

(defn- playback-fixture
  ([source-bytes ranges]
   (playback-fixture source-bytes ranges nil))
  ([source-bytes ranges open-range]
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
                                                     start (inc end)))})))
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
            [_ fields]
            (some #(when (= "drive_playback_range_opened" (first %)) %)
                  @events)]
        (is (= 206 (.statusCode response)))
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
        (is (<= 0 (:elapsedMs fields) 10000)))
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
  (doseq [[outcome reason upstream-status]
          [[(playback-failure-outcome
             (fn [_]
               (throw (java.io.IOException. "upstream unavailable"))))
            "drive_playback_open_failed"
            0]
           [(playback-failure-outcome
             (fn [_]
               {:status 200
                :headers {}
                :body (java.io.ByteArrayInputStream. (.getBytes "invalid"))}))
            "drive_playback_validation_failed"
            200]]]
    (let [[_ fields]
          (some #(when (and (= "request_failed" (first %))
                            (= reason (:reason (second %))))
                   %)
                (:events outcome))]
      (is (= 502 (:status outcome)))
      (is (= {"error" "drive_playback_unavailable"
              "retryable" true}
             (:body outcome)))
      (is (= "application/json; charset=utf-8" (:content-type outcome)))
      (is (= "" (:content-range outcome)))
      (is (= "no-store" (:cache-control outcome)))
      (is (= "nosniff" (:nosniff outcome)))
      (is (= (:request-id outcome) (:requestId fields)))
      (is (= {:reason reason
              :rangeSource "query"
              :receivedRange true
              :rangeStart 6
              :rangeEnd 10
              :upstreamStatus upstream-status
              :retryable true
              :trace "0123456789abcdef0123456789abcdef"
              :revision "revision-1"}
             (select-keys fields
                          [:reason :rangeSource :receivedRange :rangeStart
                           :rangeEnd :upstreamStatus :retryable :trace
                           :revision])))
      (is (<= 0 (:elapsedMs fields) 10000)))))
