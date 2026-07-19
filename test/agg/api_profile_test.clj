(ns agg.api-profile-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.http-test-support :as test-http]
            [agg.jobs.lifecycle :as jobs]
            [agg.tokens.core :as tokens]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- request! [port method path body headers]
  (test-http/send-string! method (str "http://127.0.0.1:" port path)
                          body headers))

(defn- auth-fixture []
  (let [system (auth/system
                {:client-id "client-id"
                 :client-secret "client-secret"
                 :base-url "https://app.example.com"
                 :allowlist #{"owner@example.com"}
                 :session-key (.getBytes "01234567890123456789012345678901")
                 :oauth (reify auth/OAuthClient
                          (exchange-code! [_ _ _ _ _]
                            (throw (UnsupportedOperationException.))))})
        token-system (tokens/in-memory-system
                      {:pepper (.getBytes
                                "abcdefghijklmnopqrstuvwxyz012345")})
        created (tokens/create-token!
                 (:service token-system)
                 {:subject "owner-subject" :email "owner@example.com"}
                 "overlay-test")
        user {:subject "owner-subject" :email "owner@example.com"}]
    {:auth-system system
     :token-service (:service token-system)
     :token (:token created)
     :session (auth/issue-session system user)
     :csrf (auth/issue-csrf-token system user)}))

(defn- render-request [section-end]
  {:telemetryFormat "polar-csv"
   :telemetry (slurp (io/resource "fixtures/polar/valid.csv"))
   :preset "1080p25"
   :telemetrySyncAt "2026-07-17T10:00:00Z"
   :cameraSyncAt "2026-07-17T09:00:00Z"
   :sectionStartAt "2026-07-17T09:00:00Z"
   :sectionEndAt section-end})

(deftest overlay-profile-exposes-only-health-and-authenticated-overlay
  (let [port (test-http/available-port)
        lifecycle (jobs/in-memory-system)
        {:keys [auth-system token-service token session csrf]} (auth-fixture)
        server (api/start! port {:service-profile "overlay"
                                 :auth-system auth-system
                                 :token-service token-service
                                 :job-service (:service lifecycle)
                                 :upload-signer (Object.)
                                 :admin-service (Object.)})]
    (try
      (is (= 200 (.statusCode (request! port :get "/health" nil {}))))
      (is (= 401 (.statusCode
                  (request! port :post "/v1/overlay" "{}"
                            {"Content-Type" "application/json"}))))
      (is (= 403 (.statusCode
                  (request! port :post "/v1/overlay" "{}"
                            {"Content-Type" "application/json"
                             "Cookie" (str "agg_session=" session)}))))
      (is (= 400 (.statusCode
                  (request! port :post "/v1/overlay" "{}"
                            {"Content-Type" "application/json"
                             "Cookie" (str "agg_session=" session)
                             "X-CSRF-Token" csrf}))))
      (is (= 400 (.statusCode
                  (request! port :post "/v1/overlay" "{}"
                            {"Content-Type" "application/json"
                             "Authorization" (str "Bearer " token)}))))
      (doseq [[method path]
              [[:get "/"]
               [:get "/privacy"]
               [:get "/openapi.yaml"]
               [:get "/v1/auth/login/start"]
               [:get "/v1/drive/picker"]
               [:post "/v1/preview"]
               [:post "/v1/jobs"]
               [:get "/v1/tokens"]
               [:get "/v1/admin/members"]
               [:post "/internal/v1/jobs/job-1/dispatch"]
               [:post "/ui/jobs"]]]
        (testing path
          (let [response (request! port method path
                                   (when (= :post method) "{}")
                                   {"Content-Type" "application/json"})]
            (is (= 404 (.statusCode response)))
            (is (= "{\"error\":\"not_found\"}" (.body response))))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest api-profile-does-not-render-overlays
  (let [port (test-http/available-port)
        server (api/start! port {:service-profile "api"})]
    (try
      (let [response (request! port :post "/v1/overlay" "{}"
                               {"Content-Type" "application/json"})]
        (is (= 404 (.statusCode response)))
        (is (= "{\"error\":\"not_found\"}" (.body response))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest overlay-profile-fails-closed-without-auth-dependencies
  (let [port (test-http/available-port)
        server (api/start! port {:service-profile "overlay"})]
    (try
      (is (= 401 (.statusCode
                  (request! port :post "/v1/overlay" "{}"
                            {"Content-Type" "application/json"}))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest overlay-profile-rejects-unproven-duration-before-rendering
  (let [port (test-http/available-port)
        {:keys [auth-system token-service token]} (auth-fixture)
        encoder-called? (atom false)
        events (atom [])
        server (api/start! port
                           {:service-profile "overlay"
                            :auth-system auth-system
                            :token-service token-service
                            :video-encoder (fn [& _]
                                             (reset! encoder-called? true))
                            :event-sink (fn [event fields]
                                          (swap! events conj [event fields]))})]
    (try
      (let [response (request! port :post "/v1/overlay"
                               (json/write-str
                                (render-request "2026-07-17T09:00:02Z"))
                               {"Content-Type" "application/json"
                                "Authorization" (str "Bearer " token)})
            request-id (some-> response .headers
                               (.firstValue "x-request-id") .orElseThrow)
            body (json/read-str (.body response) :key-fn keyword)]
        (is (= 422 (.statusCode response)))
        (is (= {:error "synchronous_overlay_duration_exceeded"
                :requestId request-id
                :maxDurationSeconds 1
                :durableJobsPath "/v1/jobs"}
               body))
        (is (false? @encoder-called?))
        (is (not (.isPresent
                  (.firstValue (.headers response)
                               "access-control-allow-origin"))))
        (is (= [["admission_rejected"
                 {:severity "WARNING"
                  :requestId request-id
                  :reason "synchronous_overlay_duration_exceeded"
                  :durationSeconds 2
                  :maxDurationSeconds 1}]]
               @events)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
