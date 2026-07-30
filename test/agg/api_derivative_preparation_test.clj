(ns agg.api-derivative-preparation-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.derivative.lifecycle :as derivative]
            [agg.drive.core :as drive]
            [agg.http-test-support :as test-http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.time Clock Instant ZoneOffset)))

(defn- auth-fixture [drive-gateway]
  (let [oauth
        (reify auth/OAuthClient
          (exchange-code! [_ _ _ _ _]
            (throw (UnsupportedOperationException.))))
        grant-store
        (reify auth/GrantStore
          (load-grant [_ _]
            {:refresh-token-ciphertext "kms:refresh"
             :folder-id "folder"})
          (save-grant! [_ _ grant] grant)
          (revoke-grant! [_ _]))
        cipher
        (reify auth/TokenCipher
          (encrypt-token! [_ value] (str "kms:" value))
          (decrypt-token! [_ value] (subs value 4)))
        token-client
        (reify auth/DriveTokenClient
          (refresh-drive-token! [_ _]
            {:access-token "private-access"}))
        system
        (auth/system
         {:client-id "client-id"
          :client-secret "client-secret"
          :base-url "https://app.example.com"
          :allowlist #{"owner@example.com" "other@example.com"}
          :session-key
          (.getBytes "01234567890123456789012345678901")
          :grant-store grant-store
          :oauth oauth
          :cipher cipher
          :drive drive-gateway
          :drive-token-client token-client})
        session
        (auth/issue-session
         system {:subject "private-owner" :email "owner@example.com"})
        other-session
        (auth/issue-session
         system {:subject "other-owner" :email "other@example.com"})]
    {:system system
     :cookie
     (auth/issue-browser-cookie system {:session session})
     :csrf
     (auth/issue-csrf-token system {:subject "private-owner"})
     :other-cookie
     (auth/issue-browser-cookie system {:session other-session})}))

(defn- request!
  [port method path body headers]
  (test-http/send-string!
   method (str "http://127.0.0.1:" port path)
   (when body (json/write-str body))
   (merge {"Content-Type" "application/json"} headers)))

(deftest preparation-routes-revalidate-drive-and-fail-closed-by-owner
  (let [metadata-requests (atom [])
        drive-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ access-token file-id]
            (swap! metadata-requests conj [access-token file-id])
            {:id file-id
             :name "private-name.mov"
             :mimeType "video/quicktime"
             :size "4096"
             :version "17"
             :trashed false
             :videoMediaMetadata {:durationMillis "120000"}})
          (stream-source! [_ _ _ _]))
        {:keys [system cookie csrf other-cookie]}
        (auth-fixture drive-gateway)
        clock (Clock/fixed
               (Instant/parse "2026-07-30T10:00:00Z")
               ZoneOffset/UTC)
        preparation
        (derivative/in-memory-preparation-system
         {:clock clock :fingerprint-secret "fixture-secret"})
        port (test-http/available-port)
        server
        (api/start! port
                    {:auth-system system
                     :derivative-preparation-service (:service preparation)
                     :clock clock})]
    (try
      (let [path "/v1/derivative-preparations"
            headers {"Idempotency-Key" "prepare-one"}
            unauthenticated (request! port :post path {:fileId "private-id"}
                                      headers)
            no-csrf
            (request! port :post path {:fileId "private-id"}
                      (assoc headers "Cookie" (str "__session=" cookie)))
            admitted
            (request! port :post path {:fileId "private-id"}
                      (assoc headers
                             "Cookie" (str "__session=" cookie)
                             "X-CSRF-Token" csrf))
            resource (json/read-str (.body admitted) :key-fn keyword)
            status-path (:statusUrl resource)
            owner-poll
            (request! port :get status-path nil
                      {"Cookie" (str "__session=" cookie)})
            other-poll
            (request! port :get status-path nil
                      {"Cookie" (str "__session=" other-cookie)})]
        (is (= 401 (.statusCode unauthenticated)))
        (is (= 403 (.statusCode no-csrf)))
        (is (= 202 (.statusCode admitted)) (.body admitted))
        (is (= [["private-access" "private-id"]] @metadata-requests))
        (is (= 200 (.statusCode owner-poll)))
        (is (= 404 (.statusCode other-poll)))
        (is (not-any? #(str/includes? (.body admitted) %)
                      ["private-id" "private-name.mov" "private-owner"
                       "owner@example.com" "private-access"
                       "fixture-secret"])))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preparation-errors-include-request-id-and-retryability
  (let [drive-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _ _]
            {:id "private-id"
             :mimeType "video/quicktime"
             :size 4096
             :version "17"
             :trashed false
             :videoMediaMetadata {:durationMillis "999999999"}})
          (stream-source! [_ _ _ _]))
        {:keys [system cookie csrf]} (auth-fixture drive-gateway)
        preparation
        (derivative/in-memory-preparation-system
         {:fingerprint-secret "fixture-secret"})
        port (test-http/available-port)
        server
        (api/start! port
                    {:auth-system system
                     :derivative-preparation-service (:service preparation)})]
    (try
      (let [response
            (request! port :post "/v1/derivative-preparations"
                      {:fileId "private-id"}
                      {"Idempotency-Key" "too-long"
                       "Cookie" (str "__session=" cookie)
                       "X-CSRF-Token" csrf})
            body (json/read-str (.body response) :key-fn keyword)
            request-id
            (.orElse (.firstValue (.headers response) "X-Request-Id") nil)]
        (is (= 422 (.statusCode response)))
        (is (= "source_duration_exceeded" (:error body)))
        (is (= request-id (:requestId body)))
        (is (false? (:retryable body)))
        (is (not (str/includes? (.body response) "private-id"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest directly-playable-source-never-submits-derivative-work
  (let [drive-gateway
        (reify
          drive/SourceGateway
          (source-metadata! [_ _ file-id]
            {:id file-id
             :name "already-playable.mp4"
             :mimeType "video/mp4"
             :size 4096
             :version "17"
             :trashed false
             :videoMediaMetadata {:durationMillis "120000"}})
          (stream-source! [_ _ _ _])
          drive/PlaybackAnalysisGateway
          (inspect-playback! [_ _ _ _]
            {:container {:format "mov" :majorBrand "isom"}
             :video {:codec "h264" :codecTag "avc1.640028"}
             :audio {:codec "aac"}}))
        {:keys [system cookie csrf]} (auth-fixture drive-gateway)
        {:keys [service queued]}
        (derivative/in-memory-preparation-system
         {:fingerprint-secret "fixture-secret"})
        port (test-http/available-port)
        server
        (api/start! port {:auth-system system
                          :derivative-preparation-service service})]
    (try
      (let [response
            (request! port :post "/v1/derivative-preparations"
                      {:fileId "private-id"}
                      {"Idempotency-Key" "already-playable"
                       "Cookie" (str "__session=" cookie)
                       "X-CSRF-Token" csrf})]
        (is (= 409 (.statusCode response)))
        (is (empty? @queued)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest internal-preparation-dispatch-and-reconciliation-require-exact-callers
  (let [drive-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _ _])
          (stream-source! [_ _ _ _]))
        {:keys [system]} (auth-fixture drive-gateway)
        calls (atom [])
        service
        (reify derivative/PreparationService
          (submit-preparation! [_ _ _])
          (get-preparation [_ _])
          (dispatch-preparation! [_ _])
          (dispatch-preparation! [_ job-id attempt]
            (swap! calls conj [:dispatch job-id attempt])
            {:started? true
             :job {:id job-id :state "running" :attempt attempt}})
          (cancel-preparation! [_ _])
          (retry-preparation! [_ _])
          (reconcile-preparations! [_]
            (swap! calls conj [:reconcile])
            {:repairedJobs 2}))
        verifier
        (reify auth/TaskTokenVerifier
          (verify-task-token! [_ token]
            (when-let [email
                       ({"derivative-task-token" "derivative-tasks@example.com"
                         "scheduler-token" "scheduler@example.com"}
                        token)]
              {:issuer "https://accounts.google.com"
               :audience "https://app.example.com"
               :email email
               :email-verified? true})))
        port (test-http/available-port)
        server
        (api/start!
         port
         {:auth-system system
          :derivative-preparation-service service
          :task-token-verifier verifier
          :task-audience "https://app.example.com"
          :derivative-tasks-service-account
          "derivative-tasks@example.com"
          :scheduler-service-account "scheduler@example.com"})]
    (try
      (let [job-id "00000000-0000-0000-0000-000000000193"
            dispatch-path
            (str "/internal/v1/derivative-preparations/" job-id
                 "/attempts/3/dispatch")
            unauthenticated
            (request! port :post dispatch-path {} {})
            wrong-caller
            (request! port :post dispatch-path {}
                      {"X-CloudTasks-TaskName" "tasks/wrong"
                       "Authorization" "Bearer scheduler-token"})
            dispatched
            (request! port :post dispatch-path {}
                      {"X-CloudTasks-TaskName" "tasks/exact"
                       "Authorization" "Bearer derivative-task-token"})
            reconcile-path
            "/internal/v1/derivative-preparations/reconcile"
            task-spoofs-scheduler
            (request! port :post reconcile-path {}
                      {"X-CloudScheduler" "true"
                       "Authorization" "Bearer derivative-task-token"})
            reconciled
            (request! port :post reconcile-path {}
                      {"X-CloudScheduler" "true"
                       "Authorization" "Bearer scheduler-token"})]
        (is (= 401 (.statusCode unauthenticated)))
        (is (= 401 (.statusCode wrong-caller)))
        (is (= 202 (.statusCode dispatched)))
        (is (= 401 (.statusCode task-spoofs-scheduler)))
        (is (= 200 (.statusCode reconciled)))
        (is (= [[:dispatch job-id 3] [:reconcile]] @calls)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
