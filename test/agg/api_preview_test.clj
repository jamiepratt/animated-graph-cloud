(ns agg.api-preview-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.http-test-support :as test-http]
            [agg.jobs-test :as fixture]
            [agg.render.media :as media]
            [clojure.data.json :as json]
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
                            (reify media/CompositeEncoder
                              (encode-composite! [_ _ _ _ source-stream! _]
                                (with-open [output (java.io.OutputStream/nullOutputStream)]
                                  (source-stream! output)))
                              (verify-composite! [_ _ _] {}))
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
