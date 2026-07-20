(ns agg.api-tokens-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.http-test-support :as test-http]
            [agg.jobs-test :as fixture]
            [agg.jobs.lifecycle :as jobs]
            [agg.tokens.core :as tokens]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]))

(defn- available-port []
  (test-http/available-port))

(defn- request! [port method path body headers]
  (test-http/send-string! method (str "http://127.0.0.1:" port path)
                          (when (some? body) (json/write-str body)) headers))

(defn- parsed [response]
  (json/read-str (.body response) :key-fn keyword))

(defn- auth-fixture []
  (let [oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  (throw (UnsupportedOperationException.))))
        grant-store (reify auth/GrantStore
                      (load-grant [_ _]
                        {:refresh-token-ciphertext "kms:refresh"
                         :folder-id "folder-1"})
                      (save-grant! [_ _ grant] grant)
                      (revoke-grant! [_ _] nil))
        cipher (reify auth/TokenCipher
                 (encrypt-token! [_ value] (str "kms:" value))
                 (decrypt-token! [_ value] (subs value 4)))
        token-client (reify auth/DriveTokenClient
                       (refresh-drive-token! [_ _]
                         {:access-token "drive-access"}))
        system (auth/system
                {:client-id "client-id"
                 :client-secret "client-secret"
                 :base-url "https://app.example.com"
                 :allowlist #{"owner@example.com" "member@example.com"}
                 :session-key (.getBytes "01234567890123456789012345678901")
                 :oauth oauth
                 :grant-store grant-store
                 :cipher cipher
                 :drive-token-client token-client})]
    {:system system
     :owner {:subject "owner-subject" :email "owner@example.com"}
     :member {:subject "member-subject" :email "member@example.com"}}))

(deftest personal-token-create-use-list-and-revoke-is-an-authenticated-csrf-safe-flow
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        token-system (tokens/in-memory-system
                      {:pepper (.getBytes "abcdefghijklmnopqrstuvwxyz012345")})
        upload-signer (reify jobs/UploadSigner
                        (signed-upload [_ _ _ _] "https://upload.test"))
        {:keys [system owner member]} (auth-fixture)
        owner-session (auth/issue-session system owner)
        owner-csrf (auth/issue-csrf-token system owner)
        member-session (auth/issue-session system member)
        member-csrf (auth/issue-csrf-token system member)
        server (api/start! port {:job-service (:service lifecycle)
                                 :auth-system system
                                 :upload-signer upload-signer
                                 :token-service (:service token-system)})]
    (try
      (testing "every cookie-authenticated write route requires a valid CSRF token"
        (doseq [path ["/v1/preview" "/v1/jobs" "/v1/uploads"
                      "/v1/tokens" "/v1/tokens/missing/revoke"
                      "/v1/jobs/missing/cancel" "/v1/jobs/missing/retry"
                      "/ui/preview" "/ui/jobs" "/ui/tokens"
                      "/ui/tokens/missing/revoke"
                      "/ui/jobs/missing/cancel" "/ui/jobs/missing/retry"]]
          (let [missing (request! port :post path {:name "CI"}
                                  {"Content-Type" "application/json"
                                   "Cookie" (str "agg_session=" owner-session)})]
            (is (= 403 (.statusCode missing)) path)
            (is (= {:error "csrf_required"} (parsed missing)) path))))
      (let [created-response
            (request! port :post "/v1/tokens" {:name "CI"}
                      {"Content-Type" "application/json"
                       "Cookie" (str "agg_session=" owner-session)
                       "X-CSRF-Token" owner-csrf})
            created (parsed created-response)
            raw-token (:token created)]
        (is (= 201 (.statusCode created-response)))
        (is (= "no-store"
               (.orElse (.firstValue (.headers created-response)
                                     "Cache-Control")
                        nil)))
        (is (string? raw-token))
        (testing "list responses never redisclose secrets or hashes"
          (let [listed (request! port :get "/v1/tokens" nil
                                 {"Cookie" (str "agg_session=" owner-session)})]
            (is (= 200 (.statusCode listed)))
            (is (= [(dissoc created :token)] (parsed listed)))
            (is (not (.contains (.body listed) raw-token)))
            (is (not (.contains (.body listed) "hash")))))
        (testing "the bearer token can submit and read only its owner's jobs"
          (let [submission
                (request! port :post "/v1/jobs" (fixture/render-request)
                          {"Content-Type" "application/json"
                           "Idempotency-Key" "personal-token-job"
                           "Authorization" (str "Bearer " raw-token)})
                job-id (:id (parsed submission))]
            (is (= 202 (.statusCode submission)))
            (is (= "owner-subject"
                   (get-in @(:state lifecycle)
                           [:jobs job-id :request :requesterSubject])))
            (is (= 404
                   (.statusCode
                    (request! port :get (str "/v1/jobs/" job-id) nil
                              {"Cookie" (str "agg_session=" member-session)}))))))
        (testing "a personal token keeps non-Drive access but cannot submit without a Drive grant"
          (let [missing-drive-port (available-port)
                missing-drive-auth
                (assoc system :grant-store
                       (reify auth/GrantStore
                         (load-grant [_ _] nil)
                         (save-grant! [_ _ grant] grant)
                         (revoke-grant! [_ _] nil)))
                missing-drive-server
                (api/start! missing-drive-port
                            {:job-service (:service lifecycle)
                             :auth-system missing-drive-auth
                             :upload-signer upload-signer
                             :token-service (:service token-system)})]
            (try
              (let [job-response
                    (request! missing-drive-port :post "/v1/jobs"
                              (fixture/render-request)
                              {"Content-Type" "application/json"
                               "Idempotency-Key" "missing-drive-token-job"
                               "Authorization" (str "Bearer " raw-token)})
                    upload-response
                    (request! missing-drive-port :post "/v1/uploads"
                              {:contentType "application/json"
                               :contentLength 32}
                              {"Content-Type" "application/json"
                               "Authorization" (str "Bearer " raw-token)})]
                (is (= 401 (.statusCode job-response)))
                (is (= {:error "drive_grant_required"
                        :recoveryPath "/v1/auth/login/start?recovery=true"}
                       (parsed job-response)))
                (is (= 201 (.statusCode upload-response))))
              (finally
                (.close ^java.lang.AutoCloseable missing-drive-server)))))
        (testing "allowlist removal immediately disables an otherwise-valid token"
          (let [revoked-port (available-port)
                revoked-auth (assoc system :allowlist #{"member@example.com"})
                revoked-server
                (api/start! revoked-port
                            {:job-service (:service lifecycle)
                             :auth-system revoked-auth
                             :token-service (:service token-system)})]
            (try
              (let [response
                    (request! revoked-port :post "/v1/jobs"
                              (fixture/render-request)
                              {"Content-Type" "application/json"
                               "Idempotency-Key" "removed-member-token"
                               "Authorization" (str "Bearer " raw-token)})]
                (is (= 403 (.statusCode response)))
                (is (= {:error "not_allowlisted"} (parsed response))))
              (finally
                (.close ^java.lang.AutoCloseable revoked-server)))))
        (testing "another member cannot revoke the owner's token"
          (is (= 404
                 (.statusCode
                  (request! port :post (str "/v1/tokens/" (:id created) "/revoke")
                            {}
                            {"Content-Type" "application/json"
                             "Cookie" (str "agg_session=" member-session)
                             "X-CSRF-Token" member-csrf})))))
        (is (= 200
               (.statusCode
                (request! port :post (str "/v1/tokens/" (:id created) "/revoke")
                          {}
                          {"Content-Type" "application/json"
                           "Cookie" (str "agg_session=" owner-session)
                           "X-CSRF-Token" owner-csrf}))))
        (is (= 401
               (.statusCode
                (request! port :post "/v1/jobs" (fixture/render-request)
                          {"Content-Type" "application/json"
                           "Idempotency-Key" "revoked-token-job"
                           "Authorization" (str "Bearer " raw-token)})))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
