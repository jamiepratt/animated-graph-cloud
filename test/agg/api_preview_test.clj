(ns agg.api-preview-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.errors :as errors]
            [agg.http-test-support :as test-http]
            [agg.jobs.lifecycle :as jobs]
            [agg.jobs-test :as fixture]
            [agg.contracts.render :as contract]
            [agg.preview.core :as preview]
            [agg.render.frames :as frames]
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
                  :allowlist #{"owner@example.com" "other@example.com"}
                  :session-key (.getBytes "01234567890123456789012345678901")
                  :oauth (reify auth/OAuthClient
                           (exchange-code! [_ _ _ _ _]
                             (throw (UnsupportedOperationException.))))
                  :cipher cipher
                  :grant-store grant-store
                  :drive source-gateway
                  :drive-token-client token-client})))

(deftest preview-starts-an-owned-asynchronous-operation
  (let [auth-system (auth-system nil)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        csrf (auth/issue-csrf-token auth-system {:subject "google-subject-1"})
        preview-system (jobs/in-memory-system)
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :preview-job-service
                                 (:service preview-system)})]
    (try
      (let [started (System/nanoTime)
            response (test-http/send-string!
                      :post (str "http://127.0.0.1:" port "/v1/preview")
                      (json/write-str (fixture/render-request))
                      {"Content-Type" "application/json"
                       "Cookie" (str "agg_session=" session)
                       "X-CSRF-Token" csrf})
            elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
            operation (json/read-str (.body response) :key-fn keyword)
            status (test-http/send-string!
                    :get
                    (str "http://127.0.0.1:" port (:statusUrl operation))
                    nil
                    {"Cookie" (str "agg_session=" session)})
            unauthenticated (test-http/send-string!
                             :get
                             (str "http://127.0.0.1:" port
                                  (:statusUrl operation))
                             nil {})]
        (is (< elapsed-ms 250.0))
        (is (= 202 (.statusCode response)))
        (is (= "application/json; charset=utf-8"
               (.orElse (.firstValue (.headers response) "content-type") nil)))
        (is (= "queued" (:state operation)))
        (is (= "key-moment-gallery" (:operationKind operation)))
        (is (= "preview" (:operationKind
                          (jobs/get-job (:service preview-system)
                                        (:id operation)))))
        (is (= (str "/v1/previews/" (:id operation))
               (:statusUrl operation)))
        (is (= (:statusUrl operation) (:resultUrl operation)))
        (is (= 0 (:progressPercent operation)))
        (is (string? (:expiresAt operation)))
        (is (= 200 (.statusCode status)))
        (is (= "queued" (:state (json/read-str (.body status)
                                               :key-fn keyword))))
        (is (= 401 (.statusCode unauthenticated))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest completed-hr-and-spo2-gallery-serves-owned-no-store-images
  (let [auth-system (auth-system nil)
        owner-session (auth/issue-session auth-system
                                          {:subject "google-subject-1"
                                           :email "owner@example.com"})
        other-session (auth/issue-session auth-system
                                          {:subject "google-subject-2"
                                           :email "other@example.com"})
        csrf (auth/issue-csrf-token auth-system {:subject "google-subject-1"})
        asset-store (preview/in-memory-asset-store)
        worker
        (reify jobs/RenderWorker
          (perform-render! [_ operation-id request]
            (preview/render-gallery!
             operation-id (contract/prepare request) asset-store
             frames/java2d-frame-renderer nil nil)))
        preview-system (jobs/in-memory-system {:worker worker})
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :preview-job-service (:service preview-system)
                                 :preview-asset-store asset-store})
        request (assoc (fixture/render-request)
                       :spo2 {:format "oxiwear-spo2-csv"
                              :telemetry
                              (str "reading_time,spo2\n"
                                   "2026-07-17T10:00:00Z,96\n"
                                   "2026-07-17T10:00:01Z,92\n"
                                   "2026-07-17T10:00:02Z,97\n")})]
    (try
      (let [started (test-http/send-string!
                     :post (str "http://127.0.0.1:" port "/v1/preview")
                     (json/write-str request)
                     {"Content-Type" "application/json"
                      "Cookie" (str "agg_session=" owner-session)
                      "X-CSRF-Token" csrf})
            operation (json/read-str (.body started) :key-fn keyword)
            operation-id (:id operation)]
        (jobs/dispatch-job! (:service preview-system) operation-id)
        (jobs/run-job! (:service preview-system) operation-id)
        (let [completed (test-http/send-string!
                         :get (str "http://127.0.0.1:" port
                                   (:statusUrl operation))
                         nil {"Cookie" (str "agg_session=" owner-session)})
              result (:result (json/read-str (.body completed) :key-fn keyword))
              thumbnail-url (get-in result [:assets 0 :image :thumbnailUrl])
              thumbnail (test-http/send-bytes!
                         :get (str "http://127.0.0.1:" port thumbnail-url)
                         nil {"Cookie" (str "agg_session=" owner-session)})
              full-url (get-in result [:assets 0 :image :fullUrl])
              full (test-http/send-bytes!
                    :get (str "http://127.0.0.1:" port full-url)
                    nil {"Cookie" (str "agg_session=" owner-session)})
              wrong-owner (test-http/send-string!
                           :get (str "http://127.0.0.1:" port
                                     (:statusUrl operation))
                           nil {"Cookie" (str "agg_session=" other-session)})
              wrong-owner-image (test-http/send-string!
                                 :get (str "http://127.0.0.1:" port
                                           thumbnail-url)
                                 nil {"Cookie" (str "agg_session=" other-session)})
              anonymous-image (test-http/send-string!
                               :get (str "http://127.0.0.1:" port thumbnail-url)
                               nil {})]
          (is (= 200 (.statusCode completed)))
          (is (= "succeeded" (:state (json/read-str (.body completed)
                                                    :key-fn keyword))))
          (is (= ["heart-rate" "spo2"]
                 (mapv :id (:sections result))))
          (is (not (contains? result :output-bytes)))
          (is (= (count (:assets result))
                 (count (set (map :id (:assets result))))))
          (is (not (str/includes? (pr-str result) "reading_time")))
          (is (= 200 (.statusCode thumbnail)))
          (is (= "no-store"
                 (.orElse (.firstValue (.headers thumbnail) "cache-control") nil)))
          (is (= [137 80 78 71 13 10 26 10]
                 (mapv #(bit-and 0xff %) (take 8 (.body thumbnail)))))
          (is (= 200 (.statusCode full)))
          (is (= "no-store"
                 (.orElse (.firstValue (.headers full) "cache-control") nil)))
          (is (= 404 (.statusCode wrong-owner)))
          (is (= 404 (.statusCode wrong-owner-image)))
          (is (= 401 (.statusCode anonymous-image)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest selected-source-preview-downloads-and-batch-decodes-on-the-worker-once
  (let [metadata-count (atom 0)
        source-count (atom 0)
        decode-count (atom 0)
        source-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _ file-id]
            (swap! metadata-count inc)
            {:id file-id :name "private-name.mp4" :mimeType "video/mp4"
             :size 1024 :trashed false})
          (stream-source! [_ _ _ _]
            (throw (AssertionError. "API must not stream the source"))))
        auth-system (auth-system source-gateway)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        csrf (auth/issue-csrf-token auth-system {:subject "google-subject-1"})
        asset-store (preview/in-memory-asset-store)
        gallery-renderer
        (reify media/CompositeGalleryRenderer
          (render-composite-gallery!
            [_ _ source-stream! overlays consume-frame!]
            (swap! decode-count inc)
            (with-open [output (java.io.ByteArrayOutputStream.)]
              (source-stream! output))
            (doseq [{:keys [frameIndex overlay]} overlays]
              (consume-frame! frameIndex overlay overlay))))
        worker
        (reify jobs/RenderWorker
          (perform-render! [_ operation-id request]
            (preview/render-gallery!
             operation-id (contract/prepare request) asset-store
             frames/java2d-frame-renderer gallery-renderer
             (fn [output]
               (swap! source-count inc)
               (.write ^java.io.OutputStream output (byte-array 1024))))))
        system (jobs/in-memory-system {:worker worker})
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :preview-job-service (:service system)
                                 :preview-asset-store asset-store})
        request (assoc (fixture/render-request)
                       :sourceVideo {:fileId "drive-file-1"}
                       :fitMode "letterbox")]
    (try
      (let [started (test-http/send-string!
                     :post (str "http://127.0.0.1:" port "/v1/preview")
                     (json/write-str request)
                     {"Content-Type" "application/json"
                      "Cookie" (str "agg_session=" session)
                      "X-CSRF-Token" csrf})
            operation (json/read-str (.body started) :key-fn keyword)]
        (is (= 202 (.statusCode started)))
        (is (= 0 @metadata-count))
        (is (= 0 @source-count))
        (jobs/dispatch-job! (:service system) (:id operation))
        (jobs/run-job! (:service system) (:id operation))
        (let [completed (test-http/send-string!
                         :get (str "http://127.0.0.1:" port
                                   (:statusUrl operation))
                         nil {"Cookie" (str "agg_session=" session)})
              result (:result (json/read-str (.body completed) :key-fn keyword))]
          (is (= 1 @source-count))
          (is (= 1 @decode-count))
          (is (= 0 @metadata-count))
          (is (= "source-final" (:mode result)))
          (is (every? #(= "source-final" (:kind %)) (:assets result)))
          (is (not (str/includes? (pr-str result) "private-name")))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest asynchronous-preview-failures-expose-only-bounded-diagnostics
  (let [auth-system (auth-system nil)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        csrf (auth/issue-csrf-token auth-system {:subject "google-subject-1"})
        worker
        (reify jobs/RenderWorker
          (perform-render! [_ _ _]
            (jobs/with-durable-stage
              "source_content"
              #(throw (errors/raise!
                       "private-file.mp4 private-drive-id secret-token"
                       {:type ::private-preview-failure
                        :status 503 :retryable true})))))
        system (jobs/in-memory-system {:worker worker})
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :preview-job-service (:service system)})]
    (try
      (let [started (test-http/send-string!
                     :post (str "http://127.0.0.1:" port "/v1/preview")
                     (json/write-str (fixture/render-request))
                     {"Content-Type" "application/json"
                      "Cookie" (str "agg_session=" session)
                      "X-CSRF-Token" csrf})
            operation (json/read-str (.body started) :key-fn keyword)]
        (jobs/dispatch-job! (:service system) (:id operation))
        (try
          (jobs/run-job! (:service system) (:id operation))
          (catch clojure.lang.ExceptionInfo _))
        (let [response (test-http/send-string!
                        :get (str "http://127.0.0.1:" port
                                  (:statusUrl operation))
                        nil {"Cookie" (str "agg_session=" session)})
              body (json/read-str (.body response) :key-fn keyword)]
          (is (= 200 (.statusCode response)))
          (is (= "failed" (:state body)))
          (is (= {:code "source_content_failed"
                  :retryable true
                  :stage "source_content"
                  :status 503}
                 (:error body)))
          (is (not-any? #(str/includes? (.body response) %)
                        ["private-file" "private-drive-id" "secret-token"]))))
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
