(ns agg.api-auth-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.http-test-support :as test-http]
            [agg.jobs-test :as fixture]
            [agg.jobs.lifecycle :as jobs]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]))

(defn- available-port []
  (test-http/available-port))

(defn- post! [port path body headers]
  (test-http/send-string! :post (str "http://127.0.0.1:" port path)
                          (json/write-str body) headers))

(defn- get! [port path headers]
  (test-http/send-string! :get (str "http://127.0.0.1:" port path)
                          nil headers))

(defn- auth-fixture []
  (let [oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  (throw (UnsupportedOperationException.))))
        system (auth/system {:client-id "client-id"
                             :client-secret "client-secret"
                             :base-url "https://app.example.com"
                             :allowlist #{"owner@example.com"}
                             :session-key (.getBytes "01234567890123456789012345678901")
                             :oauth oauth})]
    {:system system
     :session (auth/issue-session system {:subject "google-subject-1"
                                          :email "owner@example.com"})}))

(defn- drive-callback-fixture
  ([oauth]
   (drive-callback-fixture oauth {}))
  ([oauth {:keys [cipher drive grant-store]}]
   (let [cipher (or cipher
                    (reify auth/TokenCipher
                      (encrypt-token! [_ value] (str "kms:" value))
                      (decrypt-token! [_ value] (subs value 4))))
         drive (or drive
                   (reify auth/DriveClient
                     (ensure-output-folder! [_ _ existing-folder]
                       (or existing-folder "folder-1"))))
         grant-store (or grant-store
                         (reify auth/GrantStore
                           (load-grant [_ _] nil)
                           (save-grant! [_ _ grant] grant)
                           (revoke-grant! [_ _] nil)))
         system (auth/system {:client-id "client-id"
                              :client-secret "client-secret"
                              :base-url "https://app.example.com"
                              :allowlist #{"owner@example.com"}
                              :session-key (.getBytes "01234567890123456789012345678901")
                              :oauth oauth
                              :cipher cipher
                              :grant-store grant-store
                              :drive drive})]
     {:system system
      :session (auth/issue-session system {:subject "google-subject-1"
                                           :email "owner@example.com"})})))

(defn- valid-drive-oauth []
  (reify auth/OAuthClient
    (exchange-code! [_ flow _ _ _]
      (is (= :drive flow))
      {:access-token "drive-access"
       :refresh-token "drive-refresh"
       :granted-scopes #{"https://www.googleapis.com/auth/drive.file"}})))

(deftest configured-user-routes-require-an-allowlisted-session
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        {:keys [system session]} (auth-fixture)
        browser-cookie (auth/issue-browser-cookie system {:session session})
        csrf (auth/issue-csrf-token system {:subject "google-subject-1"})
        server (api/start! port {:job-service (:service lifecycle)
                                 :auth-system system
                                 :task-audience "https://app.example.com"
                                 :tasks-service-account "tasks@example.com"})]
    (try
      (let [headers {"Content-Type" "application/json"
                     "Idempotency-Key" "secure-job"}
            denied (post! port "/v1/jobs" (fixture/render-request) headers)
            admitted (post! port "/v1/jobs" (fixture/render-request)
                            (assoc headers
                                   "Cookie" (str "__session=" browser-cookie)
                                   "X-CSRF-Token" csrf))]
        (is (= 401 (.statusCode denied)))
        (is (= {"error" "authentication_required"}
               (json/read-str (.body denied))))
        (is (= 202 (.statusCode admitted)))
        (is (= "google-subject-1"
               (get-in @(:state lifecycle)
                       [:jobs (get (json/read-str (.body admitted)) "id")
                        :request :requesterSubject]))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest login-sets-a-bounded-secure-http-only-session-cookie
  (let [port (available-port)
        oauth (reify auth/OAuthClient
                (exchange-code! [_ flow _ _ _]
                  (is (= :login flow))
                  {:subject "google-subject-1"
                   :email "owner@example.com"
                   :email-verified? true}))
        system (auth/system
                {:client-id "client-id"
                 :client-secret "client-secret"
                 :base-url (str "http://127.0.0.1:" port)
                 :allowlist #{"owner@example.com"}
                 :session-key (.getBytes "01234567890123456789012345678901")
                 :oauth oauth})
        flow (auth/begin-flow! system :login nil)
        browser-cookie (auth/issue-browser-cookie
                        system {:oauth (:stateCookie flow)})
        server (api/start! port {:auth-system system})]
    (try
      (let [response (get! port
                           (str "/v1/auth/login/callback?code=code&state="
                                (:state flow))
                           {"Cookie" (str "__session=" browser-cookie)})
            session-cookie
            (first (filter #(.startsWith ^String % "__session=")
                           (.allValues (.headers response) "Set-Cookie")))
            session-name (first (.split session-cookie "=" 2))
            session-value (first (.split (second (.split session-cookie "=" 2))
                                         ";" 2))
            authenticated (get! port "/"
                                {"Cookie" (str "__session=" session-value)})]
        (is (= 302 (.statusCode response)))
        (is (re-find #"; Max-Age=43200; Path=/; Secure; HttpOnly; SameSite=Lax$"
                     session-cookie))
        (is (= "__session" session-name))
        (is (re-find #"Signed in as owner@example.com" (.body authenticated))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest drive-callback-returns-safe-categorized-errors
  (doseq [{:keys [label oauth options expected-status expected-body category]
           :or {options {}}}
          [{:label "revoked grant"
            :oauth (reify auth/OAuthClient
                     (exchange-code! [_ _ _ _ _]
                       (throw (ex-info "invalid_grant"
                                       {:type ::auth/revoked-grant}))))
            :expected-status 401
            :expected-body {"error" "drive_grant_required"}
            :category "invalid_grant"}
           {:label "missing refresh token"
            :oauth (reify auth/OAuthClient
                     (exchange-code! [_ _ _ _ _]
                       {:access-token "drive-access"
                        :refresh-token nil
                        :granted-scopes
                        #{"https://www.googleapis.com/auth/drive.file"}}))
            :expected-status 401
            :expected-body {"error" "drive_grant_required"}
            :category "missing_refresh_token"}
           {:label "unexpected scopes"
            :oauth (reify auth/OAuthClient
                     (exchange-code! [_ _ _ _ _]
                       {:access-token "drive-access"
                        :refresh-token "drive-refresh"
                        :granted-scopes #{"https://www.googleapis.com/auth/drive"}}))
            :expected-status 400
            :expected-body {"error" "invalid_drive_scopes"}
            :category "unexpected_scopes"}
           {:label "OAuth service"
            :oauth (reify auth/OAuthClient
                     (exchange-code! [_ _ _ _ _]
                       (throw (ex-info "OAuth unavailable"
                                       {:type ::auth/oauth-exchange-failed
                                        :status 503}))))
            :expected-status 502
            :expected-body {"error" "oauth_exchange_failed"
                            "retryable" true}
            :category "oauth_exchange"}
           {:label "Drive service"
            :oauth (valid-drive-oauth)
            :options {:drive (reify auth/DriveClient
                               (ensure-output-folder! [_ _ _]
                                 (throw (ex-info "Drive unavailable"
                                                 {:type ::drive-unavailable
                                                  :status 503}))))}
            :expected-status 502
            :expected-body {"error" "drive_unavailable"
                            "retryable" true}
            :category "drive"}
           {:label "KMS service"
            :oauth (valid-drive-oauth)
            :options {:cipher (reify auth/TokenCipher
                                (encrypt-token! [_ _]
                                  (throw (ex-info "KMS unavailable"
                                                  {:type ::kms-unavailable
                                                   :status 503
                                                   :reason "permission_denied"})))
                                (decrypt-token! [_ _] "unused"))}
            :expected-status 503
            :expected-body {"error" "kms_unavailable"
                            "retryable" true}
            :category "kms"}
           {:label "grant persistence"
            :oauth (valid-drive-oauth)
            :options {:grant-store (reify auth/GrantStore
                                     (load-grant [_ _] nil)
                                     (save-grant! [_ _ _]
                                       (throw (ex-info "Firestore unavailable"
                                                       {:type ::persistence-unavailable
                                                        :status 503})))
                                     (revoke-grant! [_ _] nil))}
            :expected-status 503
            :expected-body {"error" "grant_persistence_failed"
                            "retryable" true}
            :category "grant_persistence"}]]
    (testing label
      (let [port (available-port)
            events (atom [])
            {:keys [system session]} (drive-callback-fixture oauth options)
            flow (auth/begin-flow! system :drive session)
            browser-cookie (auth/issue-browser-cookie
                            system {:session session
                                    :oauth (:stateCookie flow)})
            server (api/start! port
                               {:auth-system system
                                :event-sink #(swap! events conj [%1 %2])})]
        (try
          (let [response (get! port
                               (str "/v1/auth/drive/callback?code=code&state="
                                    (:state flow))
                               {"Cookie" (str "__session=" browser-cookie)})
                event (second (first @events))]
            (is (= expected-status (.statusCode response)))
            (is (= expected-body (json/read-str (.body response))))
            (is (= ["oauth_callback_failed"]
                   (mapv first @events)))
            (is (= category (:category event)))
            (when (= "kms" category)
              (is (= "permission_denied" (:reason event))))
            (is (re-matches #"[0-9a-f-]{36}" (:requestId event)))
            (is (= expected-status (:status event)))
            (is (not-any? #(contains? event %)
                          [:code :token :email :filename :message])))
          (finally
            (.close ^java.lang.AutoCloseable server)))))))

(deftest forged-task-header-is-rejected-before-dispatch
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        {:keys [system session]} (auth-fixture)
        csrf (auth/issue-csrf-token system {:subject "google-subject-1"})
        verifier (reify auth/TaskTokenVerifier
                   (verify-task-token! [_ token]
                     (when (= "signed-task-token" token)
                       {:issuer "https://accounts.google.com"
                        :audience "https://app.example.com"
                        :email "tasks@example.com"
                        :email-verified? true})))
        server (api/start! port {:job-service (:service lifecycle)
                                 :auth-system system
                                 :task-token-verifier verifier
                                 :task-audience "https://app.example.com"
                                 :tasks-service-account "tasks@example.com"})]
    (try
      (let [submission (post! port "/v1/jobs" (fixture/render-request)
                              {"Content-Type" "application/json"
                               "Idempotency-Key" "task-auth-job"
                               "Cookie" (str "agg_session=" session)
                               "X-CSRF-Token" csrf})
            job-id (get (json/read-str (.body submission)) "id")
            path (str "/internal/v1/jobs/" job-id "/dispatch")
            forged (post! port path {}
                          {"X-CloudTasks-TaskName" "tasks/forged"})
            wrong-claims (post! port path {}
                                {"X-CloudTasks-TaskName" "tasks/wrong"
                                 "Authorization" "Bearer wrong-token"})
            valid (post! port path {}
                         {"X-CloudTasks-TaskName" "tasks/valid"
                          "Authorization" "Bearer signed-task-token"})]
        (is (= 401 (.statusCode forged)))
        (is (= 401 (.statusCode wrong-claims)))
        (is (= 202 (.statusCode valid)))
        (is (= 1 (count @(:launched lifecycle)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest internal-routes-accept-only-their-dedicated-runtime-identity
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        {:keys [system session]} (auth-fixture)
        csrf (auth/issue-csrf-token system {:subject "google-subject-1"})
        verifier (reify auth/TaskTokenVerifier
                   (verify-task-token! [_ token]
                     (when-let [email ({"signed-task-token" "tasks@example.com"
                                        "signed-scheduler-token"
                                        "scheduler@example.com"}
                                       token)]
                       {:issuer "https://accounts.google.com"
                        :audience "https://app.example.com"
                        :email email
                        :email-verified? true})))
        server (api/start! port {:job-service (:service lifecycle)
                                 :auth-system system
                                 :task-token-verifier verifier
                                 :task-audience "https://app.example.com"
                                 :tasks-service-account "tasks@example.com"
                                 :scheduler-service-account
                                 "scheduler@example.com"})]
    (try
      (let [submission (post! port "/v1/jobs" (fixture/render-request)
                              {"Content-Type" "application/json"
                               "Idempotency-Key" "internal-identities"
                               "Cookie" (str "agg_session=" session)
                               "X-CSRF-Token" csrf})
            job-id (get (json/read-str (.body submission)) "id")
            dispatch-path (str "/internal/v1/jobs/" job-id "/dispatch")
            task-spoofs-scheduler
            (post! port "/internal/v1/jobs/reconcile" {}
                   {"X-CloudScheduler" "true"
                    "Authorization" "Bearer signed-task-token"})
            scheduler-spoofs-task
            (post! port dispatch-path {}
                   {"X-CloudTasks-TaskName" "tasks/scheduler-spoof"
                    "Authorization" "Bearer signed-scheduler-token"})
            scheduler-reconciles
            (post! port "/internal/v1/jobs/reconcile" {}
                   {"X-CloudScheduler" "true"
                    "Authorization" "Bearer signed-scheduler-token"})
            task-dispatches
            (post! port dispatch-path {}
                   {"X-CloudTasks-TaskName" "tasks/valid"
                    "Authorization" "Bearer signed-task-token"})]
        (is (= 401 (.statusCode task-spoofs-scheduler)))
        (is (= 401 (.statusCode scheduler-spoofs-task)))
        (is (= 200 (.statusCode scheduler-reconciles)))
        (is (= 202 (.statusCode task-dispatches))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest picker-bridge-requires-session-and-uses-the-isolated-drive-grant
  (let [port (available-port)
        {:keys [system session]} (auth-fixture)
        grants (reify auth/GrantStore
                 (load-grant [_ _]
                   {:refresh-token-ciphertext "kms:refresh"
                    :folder-id "folder-1"})
                 (save-grant! [_ _ grant] grant)
                 (revoke-grant! [_ _]))
        cipher (reify auth/TokenCipher
                 (encrypt-token! [_ value] (str "kms:" value))
                 (decrypt-token! [_ value] (subs value 4)))
        token-client (reify auth/DriveTokenClient
                       (refresh-drive-token! [_ token]
                         (is (= "refresh" token))
                         {:access-token "picker-access-token"}))
        auth-system (assoc system
                           :grant-store grants
                           :cipher cipher
                           :drive-token-client token-client)
        server (api/start! port {:auth-system auth-system
                                 :picker-api-key "picker-key"
                                 :picker-app-id "891643499444"})]
    (try
      (let [denied (get! port "/v1/drive/picker" {})
            response (get! port "/v1/drive/picker"
                           {"Cookie" (str "agg_session=" session)})]
        (is (= 401 (.statusCode denied)))
        (is (= 200 (.statusCode response)))
        (is (= "text/html; charset=utf-8"
               (some-> response .headers (.firstValue "content-type")
                       (.orElse nil))))
        (is (re-find #"google\.picker\.PickerBuilder" (.body response)))
        (is (re-find #"picker-access-token" (.body response)))
        (is (re-find #"picker-key" (.body response)))
        (is (re-find #"891643499444" (.body response)))
        (is (re-find #"id=\"picker-selection\"" (.body response)))
        (is (re-find #"selection\.textContent" (.body response))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest browser-entrypoint-receives-picker-selections
  (let [port (available-port)
        {:keys [system session]} (auth-fixture)
        server (api/start! port {:auth-system system})]
    (try
      (let [anonymous (get! port "/" {})
            authenticated (get! port "/"
                                {"Cookie" (str "agg_session=" session)})]
        (is (= 200 (.statusCode anonymous)))
        (is (re-find #"/v1/auth/login/start" (.body anonymous)))
        (is (= 200 (.statusCode authenticated)))
        (is (re-find #"/v1/auth/drive/start" (.body authenticated)))
        (is (re-find #"window\.open\('/v1/drive/picker'" (.body authenticated)))
        (is (re-find #"addEventListener\('message'" (.body authenticated)))
        (is (re-find #"id=\"picker-selection\"" (.body authenticated))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest allowlisted-members-cannot-read-another-users-job
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        {:keys [system]} (auth-fixture)
        auth-system (assoc system :allowlist #{"owner@example.com"
                                               "member@example.com"})
        owner-session (auth/issue-session auth-system
                                          {:subject "owner-subject"
                                           :email "owner@example.com"})
        owner-csrf (auth/issue-csrf-token auth-system
                                          {:subject "owner-subject"})
        member-session (auth/issue-session auth-system
                                           {:subject "member-subject"
                                            :email "member@example.com"})
        server (api/start! port {:job-service (:service lifecycle)
                                 :auth-system auth-system})]
    (try
      (let [submission (post! port "/v1/jobs" (fixture/render-request)
                              {"Content-Type" "application/json"
                               "Idempotency-Key" "owner-job"
                               "Cookie" (str "agg_session=" owner-session)
                               "X-CSRF-Token" owner-csrf})
            job-id (get (json/read-str (.body submission)) "id")
            owner-poll (get! port (str "/v1/jobs/" job-id)
                             {"Cookie" (str "agg_session=" owner-session)})
            member-poll (get! port (str "/v1/jobs/" job-id)
                              {"Cookie" (str "agg_session=" member-session)})]
        (is (= 200 (.statusCode owner-poll)))
        (is (= 404 (.statusCode member-poll)))
        (is (= {"error" "job_not_found"}
               (json/read-str (.body member-poll)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
