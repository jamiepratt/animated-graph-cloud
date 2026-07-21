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
            [clojure.test :refer [deftest is testing]])
  (:import (java.time Clock Instant ZoneOffset)))

(defn- available-port []
  (test-http/available-port))

(defn- mutable-clock [now]
  (letfn [(clock-for [zone]
            (proxy [Clock] []
              (getZone [] zone)
              (withZone [new-zone] (clock-for new-zone))
              (instant [] @now)
              (millis [] (.toEpochMilli ^Instant @now))))]
    (clock-for ZoneOffset/UTC)))

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

(deftest durable-submit-does-not-require-preview
  (let [auth-system (auth-system nil)
        owner {:subject "google-subject-1" :email "owner@example.com"}
        owner-session (auth/issue-session auth-system owner)
        owner-csrf (auth/issue-csrf-token auth-system owner)
        system (jobs/in-memory-system)
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :job-service (:service system)})]
    (try
      (let [response
            (test-http/send-string!
             :post (str "http://127.0.0.1:" port "/v1/jobs")
             (json/write-str (fixture/render-request))
             {"Content-Type" "application/json"
              "Cookie" (str "agg_session=" owner-session)
              "X-CSRF-Token" owner-csrf
              "Idempotency-Key" "direct-submit"})
            body (json/read-str (.body response) :key-fn keyword)]
        (is (= 202 (.statusCode response)))
        (is (= "queued" (:state body)))
        (is (= 1 (count (get @(:state system) :jobs)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest durable-submit-is-not-blocked-by-failed-preview
  (let [auth-system (auth-system nil)
        owner {:subject "google-subject-1" :email "owner@example.com"}
        owner-session (auth/issue-session auth-system owner)
        owner-csrf (auth/issue-csrf-token auth-system owner)
        worker (reify jobs/RenderWorker
                 (perform-render! [_ _ _]
                   (throw (ex-info "Preview failed" {}))))
        system (jobs/in-memory-system {:worker worker})
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :job-service (:service system)})
        request (fixture/render-request)
        headers {"Content-Type" "application/json"
                 "Cookie" (str "agg_session=" owner-session)
                 "X-CSRF-Token" owner-csrf}]
    (try
      (let [preview-response
            (test-http/send-string!
             :post (str "http://127.0.0.1:" port "/v1/preview")
             (json/write-str request)
             (assoc headers "Idempotency-Key" "failed-preview"))
            operation-id (:id (json/read-str (.body preview-response)
                                             :key-fn keyword))]
        (jobs/dispatch-job! (:service system) operation-id)
        (try
          (jobs/run-job! (:service system) operation-id)
          (catch clojure.lang.ExceptionInfo _))
        (is (= "failed" (:state (jobs/get-job (:service system)
                                              operation-id))))
        (let [response
              (test-http/send-string!
               :post (str "http://127.0.0.1:" port "/v1/jobs")
               (json/write-str request)
               (assoc headers
                      "Idempotency-Key" "submit-after-failed-preview"
                      "Preview-Operation-Id" operation-id))
              body (json/read-str (.body response) :key-fn keyword)]
          (is (= 202 (.statusCode response)))
          (is (= "queued" (:state body)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest durable-submit-is-independent-of-preview-state-and-request
  (let [now (atom (Instant/now))
        clock (mutable-clock now)
        auth-system (auth-system nil)
        owner {:subject "google-subject-1" :email "owner@example.com"}
        owner-session (auth/issue-session auth-system owner)
        owner-csrf (auth/issue-csrf-token auth-system owner)
        worker (reify jobs/RenderWorker
                 (perform-render! [_ _ _]
                   {:output-bytes 0 :version 1 :mode "overlay"
                    :sections [] :assets []}))
        preview-system (jobs/in-memory-system {:clock clock :worker worker})
        durable-system (jobs/in-memory-system)
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :job-service (:service durable-system)
                                 :preview-job-service
                                 (:service preview-system)})
        request (fixture/render-request)
        headers {"Content-Type" "application/json"
                 "Cookie" (str "agg_session=" owner-session)
                 "X-CSRF-Token" owner-csrf}
        start-preview
        (fn [idempotency-key]
          (-> (test-http/send-string!
               :post (str "http://127.0.0.1:" port "/v1/preview")
               (json/write-str request)
               (assoc headers "Idempotency-Key" idempotency-key))
              .body
              (json/read-str :key-fn keyword)))
        post (fn [idempotency-key operation-id body]
               (test-http/send-string!
                :post (str "http://127.0.0.1:" port "/v1/jobs")
                (json/write-str body)
                (assoc headers
                       "Idempotency-Key" idempotency-key
                       "Preview-Operation-Id" operation-id)))]
    (try
      (let [pending-id (:id (start-preview "pending-preview"))]
        (testing "pending Preview does not block Submit"
          (is (= 202 (.statusCode (post "submit-while-pending"
                                        pending-id request)))))
        (jobs/cancel-job! (:service preview-system) pending-id)
        (testing "cancelled Preview does not block Submit"
          (is (= 202 (.statusCode (post "submit-after-cancel"
                                        pending-id request))))))
      (let [operation-id (:id (start-preview "successful-preview"))
            changed-request (assoc request :preset "2.7k25")]
        (jobs/dispatch-job! (:service preview-system) operation-id)
        (jobs/run-job! (:service preview-system) operation-id)
        (testing "changed inputs do not need another Preview"
          (let [created (post "changed-inputs" operation-id changed-request)
                duplicate (post "changed-inputs" operation-id changed-request)
                created-body (json/read-str (.body created) :key-fn keyword)
                duplicate-body (json/read-str (.body duplicate) :key-fn keyword)]
            (is (= 202 (.statusCode created)))
            (is (= 200 (.statusCode duplicate)))
            (is (= (:id created-body) (:id duplicate-body)))))
        (testing "expired Preview does not block Submit"
          (swap! now #(.plusSeconds ^Instant % (inc jobs/preview-retention-seconds)))
          (is (= 202 (.statusCode (post "submit-after-expiry"
                                        operation-id request))))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preview-starts-an-owned-asynchronous-operation
  (let [auth-system (auth-system nil)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        csrf (auth/issue-csrf-token auth-system {:subject "google-subject-1"})
        preview-system (jobs/in-memory-system)
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :job-service (:service preview-system)
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
                             nil {})
            durable-path (str "/v1/jobs/" (:id operation))
            ui-durable-path (str "/ui/jobs/" (:id operation))
            write-headers {"Cookie" (str "agg_session=" session)
                           "X-CSRF-Token" csrf
                           "Content-Type" "application/json"}
            durable-responses
            [(test-http/send-string! :get
                                     (str "http://127.0.0.1:" port durable-path)
                                     nil {"Cookie" (str "agg_session=" session)})
             (test-http/send-string! :post
                                     (str "http://127.0.0.1:" port durable-path
                                          "/cancel")
                                     "" write-headers)
             (test-http/send-string! :post
                                     (str "http://127.0.0.1:" port durable-path
                                          "/retry")
                                     "" write-headers)
             (test-http/send-string! :get
                                     (str "http://127.0.0.1:" port ui-durable-path)
                                     nil {"Cookie" (str "agg_session=" session)})
             (test-http/send-string! :post
                                     (str "http://127.0.0.1:" port ui-durable-path
                                          "/cancel")
                                     "" write-headers)
             (test-http/send-string! :post
                                     (str "http://127.0.0.1:" port ui-durable-path
                                          "/retry")
                                     "" write-headers)]]
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
        (is (= 401 (.statusCode unauthenticated)))
        (is (every? #(= 404 (.statusCode %)) durable-responses))
        (is (= "queued" (:state (jobs/get-job (:service preview-system)
                                              (:id operation)))))
        (jobs/cancel-job! (:service preview-system) (:id operation))
        (let [cancelled-response
              (test-http/send-string!
               :get (str "http://127.0.0.1:" port (:statusUrl operation))
               nil {"Cookie" (str "agg_session=" session)})
              cancelled (json/read-str (.body cancelled-response)
                                       :key-fn keyword)]
          (is (= 200 (.statusCode cancelled-response)))
          (is (= "cancelled" (:state cancelled)))
          (is (= {:code "preview_cancelled" :retryable false}
                 (:error cancelled)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest malformed-preview-operation-paths-fall-through-without-store-access
  (let [auth-system (auth-system nil)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        port (available-port)
        forbidden-store (Object.)
        server (api/start! port {:auth-system auth-system
                                 :preview-job-service forbidden-store
                                 :preview-asset-store forbidden-store})
        invalid-id (apply str (repeat 512 "a"))]
    (try
      (let [api-status (test-http/send-string!
                        :get (str "http://127.0.0.1:" port
                                  "/v1/previews/" invalid-id)
                        nil {})
            api-image (test-http/send-string!
                       :get (str "http://127.0.0.1:" port
                                 "/v1/previews/" invalid-id
                                 "/images/a000/thumbnail")
                       nil {})
            ui-status (test-http/send-string!
                       :get (str "http://127.0.0.1:" port
                                 "/ui/previews/" invalid-id)
                       nil {"Cookie" (str "agg_session=" session)})]
        (is (= [404 404 404]
               (mapv #(.statusCode %) [api-status api-image ui-status]))))
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
        decoded-frames (atom [])
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
            (reset! decoded-frames (mapv :frameIndex overlays))
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
                       :fitMode "letterbox"
                       :timer {:startAt "2026-07-17T09:00:00.500Z"
                               :endAt "2026-07-17T09:00:01.500Z"})]
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
          (is (= [0 13 38 49] @decoded-frames))
          (is (= 0 @metadata-count))
          (is (= "source-final" (:mode result)))
          (is (= [0 13 38 49]
                 (mapv :frameIndex (get-in result [:sections 0 :moments]))))
          (is (= [["Video start" "Trace start"]
                  ["Timer start"]
                  ["Timer end"]
                  ["Trace stop" "Video end"]]
                 (mapv :labels (get-in result [:sections 0 :moments]))))
          (is (= ["a000" "a001" "a002" "a003"]
                 (mapv :frameRef (get-in result [:sections 0 :moments]))
                 (mapv :id (:assets result))))
          (is (= [0 13 38 49] (mapv :frameIndex (:assets result))))
          (is (every? #(= "source-final" (:kind %)) (:assets result)))
          (is (every? false? (map :merged (:assets result))))
          (doseq [{:keys [id source final]} (:assets result)]
            (is (= (preview/image-reference (:id operation) (str id "-source"))
                   source))
            (is (= (preview/image-reference (:id operation) (str id "-final"))
                   final))
            (doseq [asset-id [(str id "-source") (str id "-final")]
                    size [:thumbnail :full]]
              (is (some? (preview/get-asset asset-store (:id operation)
                                            asset-id size)))))
          (is (not (str/includes? (pr-str result) "private-name")))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest partial-selected-source-preview-is-a-successful-bounded-api-result
  (let [auth-system (auth-system nil)
        session (auth/issue-session auth-system
                                    {:subject "google-subject-1"
                                     :email "owner@example.com"})
        csrf (auth/issue-csrf-token auth-system {:subject "google-subject-1"})
        asset-store (preview/in-memory-asset-store)
        source-count (atom 0)
        gallery-renderer
        (reify media/CompositeGalleryRenderer
          (render-composite-gallery!
            [_ _ source-stream! overlays consume-frame!]
            (with-open [output (java.io.ByteArrayOutputStream.)]
              (source-stream! output))
            (doseq [{:keys [frameIndex overlay]} (take 3 overlays)]
              (consume-frame! frameIndex overlay overlay))
            {:requested-frame-count 4
             :generated-frame-count 3
             :omitted-frame-count 1
             :reason "source_duration_too_short"
             :source-decodes 1}))
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
                       :fitMode "letterbox"
                       :timer {:startAt "2026-07-17T09:00:00.500Z"
                               :endAt "2026-07-17T09:00:01.500Z"})]
    (try
      (let [started (test-http/send-string!
                     :post (str "http://127.0.0.1:" port "/v1/preview")
                     (json/write-str request)
                     {"Content-Type" "application/json"
                      "Cookie" (str "agg_session=" session)
                      "X-CSRF-Token" csrf})
            operation (json/read-str (.body started) :key-fn keyword)]
        (jobs/dispatch-job! (:service system) (:id operation))
        (jobs/run-job! (:service system) (:id operation))
        (let [completed (test-http/send-string!
                         :get (str "http://127.0.0.1:" port
                                   (:statusUrl operation))
                         nil {"Cookie" (str "agg_session=" session)})
              body (json/read-str (.body completed) :key-fn keyword)]
          (is (= 202 (.statusCode started)))
          (is (= 200 (.statusCode completed)))
          (is (= "succeeded" (:state body)))
          (is (= 1 @source-count))
          (is (= [0 13 38]
                 (mapv :frameIndex (get-in body [:result :assets]))))
          (is (= [{:reason "source_duration_too_short"
                   :requestId (:id operation)
                   :requestedMomentCount 4
                   :generatedMomentCount 3
                   :omittedMomentCount 1
                   :requestedDurationSeconds 2
                   :retryable false}]
                 (get-in body [:result :warnings])))))
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
                        :reason "source_stream_failed"
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
                  :category "drive_source_content"
                  :requestId (:id operation)
                  :retryable true
                  :stage "source_content"
                  :reason "source_stream_failed"
                  :status 503}
                 (dissoc (:error body) :elapsedMs)))
          (is (nat-int? (get-in body [:error :elapsedMs])))
          (is (not-any? #(str/includes? (.body response) %)
                        ["private-file" "private-drive-id" "secret-token"]))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preview-application-timeout-is-bounded-correlated-and-logged-once
  (let [events (atom [])
        auth-system (auth-system nil)
        user {:subject "google-subject-1" :email "owner@example.com"}
        session (auth/issue-session auth-system user)
        csrf (auth/issue-csrf-token auth-system user)
        timeout-service
        (reify jobs/JobService
          (submit-job! [_ _ _]
            (throw (errors/raise!
                    "private-file.mp4 secret-token"
                    {:type ::preview-timeout
                     :failure-code "preview_timeout"
                     :stage "source_metadata"
                     :status 504
                     :elapsed-ms 45004
                     :timeout-ms 45000
                     :retryable true})))
          (get-job [_ _] nil)
          (dispatch-job! [_ _] nil)
          (cancel-job! [_ _] nil)
          (retry-job! [_ _] nil)
          (run-job! [_ _] nil))
        port (available-port)
        server (api/start! port {:auth-system auth-system
                                 :preview-job-service timeout-service
                                 :event-sink (fn [event fields]
                                               (swap! events conj
                                                      (assoc fields
                                                             :event event)))})]
    (try
      (let [response (test-http/send-string!
                      :post (str "http://127.0.0.1:" port "/v1/preview")
                      (json/write-str (fixture/render-request))
                      {"Content-Type" "application/json"
                       "Cookie" (str "agg_session=" session)
                       "X-CSRF-Token" csrf})
            body (json/read-str (.body response) :key-fn keyword)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (= 504 (.statusCode response)))
        (is (= {:error "preview_failed"
                :category "preview_rendering"
                :requestId request-id
                :retryable true
                :failureCode "preview_timeout"
                :stage "source_metadata"
                :status 504
                :elapsedMs 45004
                :timeoutMs 45000}
               body))
        (is (= 1 (count @events)))
        (is (= (assoc (dissoc body :error :timeoutMs :elapsedMs)
                      :severity "ERROR"
                      :event "request_failed")
               (dissoc (first @events) :elapsedMs :timeoutMs)))
        (is (not-any? #(str/includes? (pr-str @events) %)
                      ["private-file" "secret-token"])))
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
                      "{"
                      {"Content-Type" "application/json"})
            body (json/read-str (.body response) :key-fn keyword)
            event (first @events)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (= 400 (.statusCode response)))
        (is (= "application/json; charset=utf-8"
               (.orElse (.firstValue (.headers response) "content-type") nil)))
        (is (= #{:error :category :requestId :retryable}
               (set (keys body))))
        (is (= "invalid_request" (:error body)))
        (is (= "request_contract" (:category body)))
        (is (= request-id (:requestId body)))
        (is (false? (:retryable body)))
        (is (= "request_failed" (:event event)))
        (is (= "request_contract" (:category event)))
        (is (= request-id (:requestId event)))
        (is (false? (:retryable event))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest polar-summary-export-explains-the-timestamped-csv-contract
  (let [events (atom [])
        port (available-port)
        server (api/start! port
                           {:event-sink (fn [event fields]
                                          (swap! events conj
                                                 (assoc fields :event event)))})
        request (assoc (fixture/render-request)
                       :telemetry
                       (str "Date,Start time,Duration,Maximum heart rate\n"
                            "2026-07-17,10:00:00,00:30:00,180\n"))]
    (try
      (let [response (test-http/send-string!
                      :post
                      (str "http://127.0.0.1:" port "/v1/preview")
                      (json/write-str request)
                      {"Content-Type" "application/json"})
            body (json/read-str (.body response) :key-fn keyword)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (= 400 (.statusCode response)))
        (is (= {:error "invalid_request"
                :category "request_contract"
                :failureCode "unsupported_telemetry_columns"
                :requestId request-id
                :retryable false
                :field "telemetry"
                :expectedSchema
                {:timestampColumns ["timestamp" "date/time" "datetime"]
                 :valueColumns ["heart_rate" "heart rate"
                                "heart rate (bpm)" "HR" "HR (bpm)"]}
                :documentationPath
                "/openapi.yaml#/components/schemas/RenderRequest"}
               body))
        (is (= "unsupported_telemetry_columns"
               (:failureCode (first @events))))
        (is (= "telemetry" (:field (first @events))))
        (is (not-any? #(str/includes? (str body @events) %)
                      ["Maximum heart rate" "2026-07-17" "180"])))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest telemetry-range-failure-is-stable-bounded-and-nonretryable
  (let [events (atom [])
        port (available-port)
        server (api/start! port
                           {:event-sink (fn [event fields]
                                          (swap! events conj
                                                 (assoc fields :event event)))})
        request (assoc (fixture/render-request)
                       :telemetry
                       (str "timestamp,heart_rate\n"
                            "2026-07-17T10:00:00Z,261\n"))]
    (try
      (let [response (test-http/send-string!
                      :post
                      (str "http://127.0.0.1:" port "/v1/preview")
                      (json/write-str request)
                      {"Content-Type" "application/json"})
            body (json/read-str (.body response) :key-fn keyword)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))
            diagnostic-fields [(dissoc body :requestId)
                               (dissoc (first @events) :requestId)]]
        (is (= {:error "invalid_request"
                :category "request_contract"
                :failureCode "heart_rate_out_of_range"
                :requestId request-id
                :retryable false
                :field "telemetry"
                :line 2
                :documentationPath
                "/openapi.yaml#/components/schemas/RenderRequest"}
               body))
        (is (= {:event "request_failed"
                :severity "WARNING"
                :category "request_contract"
                :status 400
                :requestId request-id
                :retryable false
                :failureCode "heart_rate_out_of_range"
                :field "telemetry"
                :line 2}
               (first @events)))
        (is (not-any? #(str/includes? (str diagnostic-fields) %)
                      ["261" "2026-07-17"])))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest telemetry-contract-vocabulary-is-stable-across-api-failures
  (let [events (atom [])
        port (available-port)
        server (api/start! port
                           {:event-sink (fn [event fields]
                                          (swap! events conj
                                                 (assoc fields :event event)))})
        base (fixture/render-request)
        cases
        [{:request (assoc base :telemetry
                          "timestamp,heart_rate\n2026-07-17T10:00:00Z,\n")
          :code "malformed_telemetry_row" :field "telemetry" :line 2}
         {:request (assoc base :telemetry
                          (str "timestamp,heart_rate\n"
                               "2026-07-17T10:00:00Z,120\n"
                               "2026-07-17T10:00:02Z,124\n"
                               "2026-07-17T10:00:01Z,128\n"))
          :code "unordered_telemetry" :field "telemetry"}
         {:request (assoc base :telemetry
                          "timestamp,heart_rate\n2026-07-17T10:00:00Z,120\n")
          :code "insufficient_telemetry_coverage" :field "telemetry"}
         {:request (assoc base :sectionEndAt "2026-07-17T09:00:03Z")
          :code "insufficient_telemetry_coverage" :field "telemetry"}
         {:request (assoc base :telemetryFormat "unknown")
          :code "unsupported_telemetry_format" :field "telemetryFormat"}
         {:request (assoc base :telemetryFormat "garmin-fit"
                          :telemetry "bm90LWZpdA==")
          :code "unsupported_telemetry_format" :field "telemetry"}
         {:request (assoc base :spo2
                          {:format "oxiwear-spo2-csv"
                           :telemetry
                           "reading_time,spo2\n2026-07-17T10:00:00Z,\n"})
          :code "malformed_telemetry_row"
          :field "spo2.telemetry" :line 2}]]
    (try
      (doseq [{:keys [request code field line]} cases]
        (let [response (test-http/send-string!
                        :post
                        (str "http://127.0.0.1:" port "/v1/preview")
                        (json/write-str request)
                        {"Content-Type" "application/json"})
              body (json/read-str (.body response) :key-fn keyword)
              request-id (some-> response .headers
                                 (.firstValue "x-request-id") (.orElse nil))]
          (is (= 400 (.statusCode response)) code)
          (is (= {:error "invalid_request"
                  :category "request_contract"
                  :failureCode code
                  :requestId request-id
                  :retryable false
                  :field field
                  :documentationPath
                  "/openapi.yaml#/components/schemas/RenderRequest"}
                 (dissoc body :line))
              code)
          (is (= line (:line body)) code)))
      (is (= (mapv :code cases) (mapv :failureCode @events)))
      (is (every? #(= "request_contract" (:category %)) @events))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest telemetry-size-failure-is-distinct-and-does-not-echo-content
  (let [events (atom [])
        port (available-port)
        server (api/start! port
                           {:event-sink (fn [event fields]
                                          (swap! events conj
                                                 (assoc fields :event event)))})
        oversized (.repeat "x" (inc contract/max-telemetry-bytes))
        request (assoc (fixture/render-request) :telemetry oversized)]
    (try
      (let [response (test-http/send-string!
                      :post
                      (str "http://127.0.0.1:" port "/v1/preview")
                      (json/write-str request)
                      {"Content-Type" "application/json"})
            body (json/read-str (.body response) :key-fn keyword)]
        (is (= 400 (.statusCode response)))
        (is (= "telemetry_too_large" (:failureCode body)))
        (is (= "telemetry" (:field body)))
        (is (false? (:retryable body)))
        (is (= "telemetry_too_large" (:failureCode (first @events))))
        (is (< (count (.body response)) 1024)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
