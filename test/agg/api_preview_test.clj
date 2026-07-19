(ns agg.api-preview-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.http-test-support :as test-http]
            [agg.jobs-test :as fixture]
            [agg.render.media :as media]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- available-port []
  (test-http/available-port))

(defn- auth-system [source-gateway]
  (let [grant-store (reify auth/GrantStore
                      (load-grant [_ _]
                        {:refresh-token-ciphertext "encrypted-refresh"
                         :folder-id "drive-folder"})
                      (save-grant! [_ _ grant] grant)
                      (revoke-grant! [_ _] nil))
        cipher (reify auth/TokenCipher
                 (encrypt-token! [_ value] value)
                 (decrypt-token! [_ value]
                   (is (= "encrypted-refresh" value))
                   "refresh-token"))
        token-client (reify auth/DriveTokenClient
                       (refresh-drive-token! [_ refresh-token]
                         (is (= "refresh-token" refresh-token))
                         {:access-token "drive-access-token"}))]
    (auth/system {:client-id "client-id"
                  :client-secret "client-secret"
                  :base-url "https://app.example.com"
                  :allowlist #{"owner@example.com"}
                  :session-key (.getBytes "01234567890123456789012345678901")
                  :oauth (reify auth/OAuthClient
                           (exchange-code! [_ _ _ _ _]
                             (throw (UnsupportedOperationException.))))
                  :cipher cipher
                  :grant-store grant-store
                  :drive source-gateway
                  :drive-token-client token-client})))

(deftest selected-drive-source-content-failure-is-structured-and-correlated
  (let [events (atom [])
        source-gateway (reify drive/SourceGateway
                         (source-metadata! [_ access-token file-id]
                           (is (= "drive-access-token" access-token))
                           {:id file-id
                            :name "selected-source.mp4"
                            :mimeType "video/mp4"
                            :size 1024
                            :trashed false})
                         (stream-source! [_ access-token file-id _]
                           (is (= "drive-access-token" access-token))
                           (is (= "drive-file-1" file-id))
                           (throw (ex-info "Drive source download failed"
                                           {:type :agg.drive.gcp/source-download-failed
                                            :status 403}))))
        auth-system (auth-system source-gateway)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        csrf (auth/issue-csrf-token auth-system
                                    {:subject "google-subject-1"})
        port (available-port)
        server (api/start! port
                           {:auth-system auth-system
                            :video-encoder
                            (reify media/CompositePreviewer
                              (render-composite-preview!
                                [_ _ source-stream! _overlay-png _output]
                                (with-open [output (java.io.OutputStream/nullOutputStream)]
                                  (source-stream! output))
                                nil))
                            :event-sink (fn [event fields]
                                          (swap! events conj
                                                 (assoc fields :event event)))})]
    (try
      (let [response (test-http/send-string!
                      :post
                      (str "http://127.0.0.1:" port "/v1/preview")
                      (json/write-str
                       (assoc (fixture/render-request)
                              :sourceVideo {:fileId "drive-file-1"}
                              :outputFormat "h264-mp4"
                              :fitMode "letterbox"
                              :audioMode "source+heartbeat"))
                      {"Content-Type" "application/json"
                       "Cookie" (str "agg_session=" session)
                       "X-CSRF-Token" csrf})
            body (json/read-str (.body response) :key-fn keyword)
            event (first @events)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (= 502 (.statusCode response)))
        (is (= "application/json; charset=utf-8"
               (.orElse (.firstValue (.headers response) "content-type") nil)))
        (is (= #{:error :category :requestId :retryable}
               (set (keys body))))
        (is (= "preview_failed" (:error body)))
        (is (= "drive_source_content" (:category body)))
        (is (= request-id (:requestId body)))
        (is (re-matches #"[0-9a-f-]{36}" request-id))
        (is (= "request_failed" (:event event)))
        (is (= request-id (:requestId event)))
        (is (= "drive_source_content" (:category event)))
        (is (not-any? #(contains? event %)
                      [:access-token :token :filename :file-id :fileId :name :body])))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest selected-drive-source-preview-returns-the-composited-midpoint
  (let [source-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _access-token file-id]
            {:id file-id
             :name "selected-source.mp4"
             :mimeType "video/mp4"
             :size 1024
             :trashed false})
          (stream-source! [_ _access-token _file-id output]
            (let [bytes (.getBytes "source-bytes"
                                   java.nio.charset.StandardCharsets/UTF_8)]
              (.write ^java.io.OutputStream output bytes 0 (alength bytes)))))
        auth-system (auth-system source-gateway)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        csrf (auth/issue-csrf-token auth-system
                                    {:subject "google-subject-1"})
        port (available-port)
        server
        (api/start!
         port
         {:auth-system auth-system
          :video-encoder
          (reify media/CompositePreviewer
            (render-composite-preview!
              [_ render-spec source-stream! overlay-png output]
              (is (= 2 (:duration-seconds render-spec)))
              (is (= [-119 80 78 71 13 10 26 10]
                     (mapv int (take 8 overlay-png))))
              (with-open [source-output
                          (java.io.ByteArrayOutputStream.)]
                (source-stream! source-output))
              (.write ^java.io.OutputStream output
                      (.getBytes "composited-midpoint"
                                 java.nio.charset.StandardCharsets/UTF_8))))})]
    (try
      (let [response
            (test-http/send-string!
             :post
             (str "http://127.0.0.1:" port "/v1/preview")
             (json/write-str
              (assoc (fixture/render-request)
                     :sourceVideo {:fileId "drive-file-1"}
                     :outputFormat "h264-mp4"
                     :fitMode "letterbox"
                     :audioMode "source+heartbeat"))
             {"Content-Type" "application/json"
              "Cookie" (str "agg_session=" session)
              "X-CSRF-Token" csrf})]
        (is (= 200 (.statusCode response)))
        (is (= "image/png"
               (.orElse (.firstValue (.headers response) "content-type") nil)))
        (is (= "composited-midpoint" (.body response))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest selected-drive-source-preview-times-out-before-the-platform-boundary
  (let [events (atom [])
        source-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _access-token file-id]
            {:id file-id
             :name "selected-source.mp4"
             :mimeType "video/mp4"
             :size 1024
             :trashed false})
          (stream-source! [_ _access-token _file-id _output]
            (Thread/sleep 300)
            (throw (ex-info "Drive source download failed"
                            {:type :agg.drive.gcp/source-download-failed
                             :status 504}))))
        auth-system (auth-system source-gateway)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        csrf (auth/issue-csrf-token auth-system
                                    {:subject "google-subject-1"})
        port (available-port)
        server (api/start! port
                           {:auth-system auth-system
                            :preview-timeout-ms 50
                            :video-encoder
                            (reify media/CompositePreviewer
                              (render-composite-preview!
                                [_ _ source-stream! _overlay-png _output]
                                (with-open [output (java.io.OutputStream/nullOutputStream)]
                                  (source-stream! output))
                                nil))
                            :event-sink (fn [event fields]
                                          (swap! events conj
                                                 (assoc fields :event event)))})]
    (try
      (let [started (System/nanoTime)
            response (test-http/send-string!
                      :post
                      (str "http://127.0.0.1:" port "/v1/preview")
                      (json/write-str
                       (assoc (fixture/render-request)
                              :sourceVideo {:fileId "drive-file-1"}
                              :outputFormat "h264-mp4"
                              :fitMode "letterbox"
                              :audioMode "source+heartbeat"))
                      {"Content-Type" "application/json"
                       "Cookie" (str "agg_session=" session)
                       "X-CSRF-Token" csrf})
            elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
            body (json/read-str (.body response) :key-fn keyword)
            stage (:stage body)
            event (first @events)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (< elapsed-ms 250.0))
        (is (= 504 (.statusCode response)))
        (is (= {:error "preview_failed"
                :category "preview_timeout"
                :requestId request-id
                :retryable true
                :timeoutMs 50}
               (dissoc body :elapsedMs :stage)))
        (is (contains? #{"source_content" "frame_compose"} stage))
        (is (<= 50 (:elapsedMs body) 250))
        (is (= {:severity "ERROR"
                :requestId request-id
                :category "preview_timeout"
                :status 504
                :timeoutMs 50
                :retryable true
                :event "request_failed"}
               (dissoc event :elapsedMs :stage)))
        (is (= stage (:stage event)))
        (is (<= 50 (:elapsedMs event) 250))
        (is (not-any? #(contains? event %)
                      [:access-token :token :filename :file-id :fileId
                       :name :body :request])))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest selected-drive-source-preview-timeout-is-visible-in-the-ui
  (let [source-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _access-token _file-id]
            (Thread/sleep 300)
            {:id "drive-file-1"
             :name "selected-source.mp4"
             :mimeType "video/mp4"
             :size 1024
             :trashed false})
          (stream-source! [_ _access-token _file-id _output]
            (throw (UnsupportedOperationException.))))
        auth-system (auth-system source-gateway)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        csrf (auth/issue-csrf-token auth-system
                                    {:subject "google-subject-1"})
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :preview-timeout-ms 50})
        request (assoc (fixture/render-request)
                       :sourceVideo {:fileId "drive-file-1"}
                       :outputFormat "h264-mp4"
                       :fitMode "letterbox"
                       :audioMode "source+heartbeat")]
    (try
      (let [response
            (test-http/send-string!
             :post
             (str "http://127.0.0.1:" port "/ui/preview")
             (str "request="
                  (java.net.URLEncoder/encode
                   (json/write-str request)
                   java.nio.charset.StandardCharsets/UTF_8))
             {"Content-Type" "application/x-www-form-urlencoded"
              "Cookie" (str "agg_session=" session)
              "X-CSRF-Token" csrf})
            body (.body response)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (= 200 (.statusCode response)))
        (is (str/includes? body "<article id=\"preview-result\""))
        (is (str/includes? body "preview_timeout"))
        (is (str/includes? body "source_metadata"))
        (is (str/includes? body "Deadline"))
        (is (str/includes? body "50 ms"))
        (is (str/includes? body "Retryable"))
        (is (str/includes? body request-id))
        (is (not (str/includes? body "selected-source")))
        (is (not (str/includes? body "drive-file-1"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preview-contract-failure-is-structured-and-logged
  (let [events (atom [])
        port (available-port)
        server (api/start! port
                           {:event-sink (fn [event fields]
                                          (swap! events conj
                                                 (assoc fields :event event)))})]
    (try
      (let [response (test-http/send-string!
                      :post
                      (str "http://127.0.0.1:" port "/v1/preview")
                      "{}"
                      {"Content-Type" "application/json"})
            body (json/read-str (.body response) :key-fn keyword)
            event (first @events)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (= 400 (.statusCode response)))
        (is (= "application/json; charset=utf-8"
               (.orElse (.firstValue (.headers response) "content-type") nil)))
        (is (= #{:error :category :requestId}
               (set (keys body))))
        (is (= "invalid_request" (:error body)))
        (is (= "request_contract" (:category body)))
        (is (= request-id (:requestId body)))
        (is (= "request_failed" (:event event)))
        (is (= "request_contract" (:category event)))
        (is (= request-id (:requestId event))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
