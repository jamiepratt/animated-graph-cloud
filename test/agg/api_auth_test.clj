(ns agg.api-auth-test
  (:require [agg.admin.core :as admin]
            [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.early-access.core :as early-access]
            [agg.errors :as errors]
            [agg.http-test-support :as test-http]
            [agg.jobs-test :as fixture]
            [agg.jobs.lifecycle :as jobs]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- available-port []
  (test-http/available-port))

(defn- start-api!
  ([port] (start-api! port {}))
  ([port dependencies]
   (api/start! port dependencies)))

(defn- post! [port path body headers]
  (test-http/send-string! :post (str "http://127.0.0.1:" port path)
                          (json/write-str body) headers))

(defn- get! [port path headers]
  (test-http/send-string! :get (str "http://127.0.0.1:" port path)
                          nil headers))

(defn- assert-supported-video-picker [body]
  (let [mime-types (str/join "," drive/supported-source-video-mime-types)]
    (is (str/includes? body
                       (str "const pickerMimeTypes="
                            (json/write-str mime-types))))
    (is (str/includes? body ".setMimeTypes(pickerMimeTypes)"))
    (is (str/includes? body ".setIncludeFolders(true)"))
    (is (str/includes? body ".setSelectFolderEnabled(false)"))
    (is (str/includes? body
                       ".setMode(google.picker.DocsViewMode.LIST)"))
    (is (str/includes? body ".setEnableDrives(true)"))
    (is (str/includes? body ".addView(driveView).addView(sharedDrivesView)"))
    (is (str/includes? body "new google.picker.DocsUploadView()"))
    (is (str/includes? body "pickerMimeTypeSet.has(file.mimeType)"))
    (is (not (str/includes? body "mimeType.startsWith('video/')")))))

(defn- post-form! [port path form]
  (test-http/send-string!
   :post (str "http://127.0.0.1:" port path)
   (->> form
        (map (fn [[key value]]
               (str (java.net.URLEncoder/encode (name key) "UTF-8") "="
                    (java.net.URLEncoder/encode (str value) "UTF-8"))))
        (str/join "&"))
   {"Content-Type" "application/x-www-form-urlencoded"
    "Accept" "text/html"}))

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
        system (auth/system {:client-id "client-id"
                             :client-secret "client-secret"
                             :base-url "https://app.example.com"
                             :allowlist #{"owner@example.com"}
                             :session-key (.getBytes "01234567890123456789012345678901")
                             :oauth oauth
                             :grant-store grant-store
                             :cipher cipher
                             :drive-token-client token-client})]
    {:system system
     :session (auth/issue-session system {:subject "google-subject-1"
                                          :email "owner@example.com"})}))

(defn- drive-callback-fixture
  ([oauth]
   (drive-callback-fixture oauth {}))
  ([oauth {:keys [allowlist cipher drive grant-store drive-token-client]}]
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
         drive-token-client
         (or drive-token-client
             (reify auth/DriveTokenClient
               (refresh-drive-token! [_ _]
                 {:access-token "refreshed-drive-access"})))
         system (auth/system {:client-id "client-id"
                              :client-secret "client-secret"
                              :base-url "https://app.example.com"
                              :allowlist (or allowlist #{"owner@example.com"})
                              :session-key (.getBytes "01234567890123456789012345678901")
                              :oauth oauth
                              :cipher cipher
                              :grant-store grant-store
                              :drive drive
                              :drive-token-client drive-token-client})]
     {:system system
      :session (auth/issue-session system {:subject "google-subject-1"
                                           :email "owner@example.com"})})))

(defn- valid-drive-oauth []
  (reify auth/OAuthClient
    (exchange-code! [_ flow _ _ _]
      (is (= :login flow))
      {:subject "google-subject-1"
       :email "owner@example.com"
       :email-verified? true
       :access-token "drive-access"
       :refresh-token "drive-refresh"
       :granted-scopes (set auth/approved-scopes)})))

(deftest configured-user-routes-require-an-allowlisted-session
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        {:keys [system session]} (auth-fixture)
        browser-cookie (auth/issue-browser-cookie system {:session session})
        csrf (auth/issue-csrf-token system {:subject "google-subject-1"})
        server (start-api! port {:job-service (:service lifecycle)
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
                   :email-verified? true
                   :access-token "drive-access"
                   :refresh-token "drive-refresh"
                   :granted-scopes (set auth/approved-scopes)}))
        system (assoc (:system (drive-callback-fixture oauth))
                      :base-url (str "http://127.0.0.1:" port))
        flow (auth/begin-flow! system :login nil)
        browser-cookie (auth/issue-browser-cookie
                        system {:oauth (:stateCookie flow)})
        server (start-api! port {:auth-system system})]
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

(deftest authenticated-user-can-log-out
  (let [port (available-port)
        {:keys [system session]} (auth-fixture)
        browser-cookie (auth/issue-browser-cookie system {:session session})
        cookie (str "__session=" browser-cookie "; agg_session=" session)
        server (start-api! port {:auth-system system})]
    (try
      (let [authenticated (get! port "/" {"Cookie" cookie})
            rendered-csrf (second (re-find #"name=\"csrf\" value=\"([^\"]+)\""
                                           (.body authenticated)))
            logout (test-http/send-string!
                    :post (str "http://127.0.0.1:" port "/v1/auth/logout")
                    (str "csrf=" rendered-csrf)
                    {"Cookie" cookie
                     "Content-Type" "application/x-www-form-urlencoded"})
            signed-out (get! port "/" {})
            protected (get! port "/v1/drive/picker" {})
            clear-cookies (set (.allValues (.headers logout) "Set-Cookie"))]
        (is (= 200 (.statusCode authenticated)))
        (is (re-find #"<form[^>]+method=\"post\"[^>]+action=\"/v1/auth/logout\""
                     (.body authenticated)))
        (is (re-find #">Log out</button>" (.body authenticated)))
        (is (string? rendered-csrf))
        (is (= 302 (.statusCode logout)))
        (is (= "/" (.orElse (.firstValue (.headers logout) "Location") nil)))
        (is (= #{"__session=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax"
                 "agg_session=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax"}
               clear-cookies))
        (is (re-find #"Continue with Google" (.body signed-out)))
        (is (not (re-find #"Signed in as" (.body signed-out))))
        (is (= 401 (.statusCode protected)))
        (is (= {"error" "authentication_required"}
               (json/read-str (.body protected)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest combined-login-callback-returns-safe-categorized-errors
  (doseq [{:keys [label oauth options expected-status expected-body category
                  expected-reason]
           :or {options {}}}
          [{:label "revoked grant"
            :oauth (reify auth/OAuthClient
                     (exchange-code! [_ _ _ _ _]
                       (throw (ex-info "invalid_grant"
                                       {:type ::auth/revoked-grant}))))
            :expected-status 401
            :expected-body {"error" "drive_grant_required"
                            "recoveryPath" "/v1/auth/login/start?recovery=true"}
            :category "invalid_grant"}
           {:label "missing refresh token"
            :oauth (reify auth/OAuthClient
                     (exchange-code! [_ _ _ _ _]
                       {:access-token "drive-access"
                        :subject "google-subject-1"
                        :email "owner@example.com"
                        :email-verified? true
                        :refresh-token nil
                        :granted-scopes (set auth/approved-scopes)}))
            :expected-status 401
            :expected-body {"error" "drive_grant_required"
                            "recoveryPath" "/v1/auth/login/start?recovery=true"}
            :category "missing_refresh_token"}
           {:label "missing required scopes"
            :oauth (reify auth/OAuthClient
                     (exchange-code! [_ _ _ _ _]
                       {:access-token "drive-access"
                        :subject "google-subject-1"
                        :email "owner@example.com"
                        :email-verified? true
                        :refresh-token "drive-refresh"
                        :granted-scopes #{"openid" "email" "profile"}}))
            :expected-status 400
            :expected-body {"error" "invalid_drive_scopes"}
            :category "missing_required_scopes"}
           {:label "unexpected scopes"
            :oauth (reify auth/OAuthClient
                     (exchange-code! [_ _ _ _ _]
                       {:access-token "drive-access"
                        :subject "google-subject-1"
                        :email "owner@example.com"
                        :email-verified? true
                        :refresh-token "drive-refresh"
                        :granted-scopes
                        (conj (set auth/approved-scopes)
                              "https://www.googleapis.com/auth/private.extra")}))
            :expected-status 400
            :expected-body {"error" "invalid_drive_scopes"}
            :category "unexpected_scopes"}
           {:label "not allowlisted"
            :oauth (valid-drive-oauth)
            :options {:allowlist #{"approved@example.com"}}
            :expected-status 403
            :expected-body {"error" "not_allowlisted"}
            :category "not_allowlisted"}
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
           {:label "unsafe Drive diagnostic"
            :oauth (valid-drive-oauth)
            :options {:drive (reify auth/DriveClient
                               (ensure-output-folder! [_ _ _]
                                 (throw (ex-info "Drive unavailable"
                                                 {:type ::drive-unavailable
                                                  :status 403
                                                  :reason "https://www.googleapis.com/auth/private.extra"}))))}
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
            :category "kms"
            :expected-reason "permission_denied"}
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
            {:keys [system]} (drive-callback-fixture oauth options)
            flow (auth/begin-flow! system :login nil)
            browser-cookie (auth/issue-browser-cookie
                            system {:oauth (:stateCookie flow)})
            server (start-api! port
                               {:auth-system system
                                :event-sink #(swap! events conj [%1 %2])})]
        (try
          (let [response (get! port
                               (str "/v1/auth/login/callback?code=code&state="
                                    (:state flow))
                               {"Accept" "application/json"
                                "Cookie" (str "__session=" browser-cookie)})
                event (second (first @events))]
            (is (= expected-status (.statusCode response)))
            (is (= expected-body (json/read-str (.body response))))
            (let [set-cookie (first (.allValues (.headers response) "Set-Cookie"))
                  cookie-value (some-> set-cookie
                                       (.split "=" 2) second
                                       (.split ";" 2) first)]
              (is (re-find #"^__session=" set-cookie))
              (is (nil? (:session
                         (auth/browser-cookie system cookie-value)))))
            (is (= ["oauth_callback_failed"]
                   (mapv first @events)))
            (is (= category (:category event)))
            (is (= expected-reason (:reason event)))
            (is (re-matches #"[0-9a-f-]{36}" (:requestId event)))
            (is (= expected-status (:status event)))
            (is (not-any? #(contains? event %)
                          [:code :token :email :filename :message]))
            (is (not (re-find #"private@example\.com|auth/private\.extra"
                              (pr-str event)))))
          (finally
            (.close ^java.lang.AutoCloseable server)))))))

(deftest post-allowlist-login-recovers-missing-refresh-token-with-signed-consent
  (let [port (available-port)
        events (atom [])
        grants (atom {})
        encrypted (atom [])
        {:keys [directory service]}
        (admin/in-memory-system {:owner-email "owner@example.com"})
        owner (admin/authorize-member! directory "owner@example.com"
                                       "owner-subject")
        oauth (reify auth/OAuthClient
                (exchange-code! [_ flow code _ _]
                  (is (= :login flow))
                  {:access-token "private-access-token"
                   :subject "private-google-subject"
                   :email "private@example.com"
                   :email-verified? true
                   :refresh-token (case code
                                    "pre-membership-code" "private-pre-refresh"
                                    "routine-code" nil
                                    "recovery-code" "private-recovery-refresh")
                   :granted-scopes (set auth/approved-scopes)}))
        cipher (reify auth/TokenCipher
                 (encrypt-token! [_ value]
                   (swap! encrypted conj value)
                   (str "kms:" value))
                 (decrypt-token! [_ value] (subs value 4)))
        grant-store
        (reify auth/GrantStore
          (load-grant [_ subject] (get @grants subject))
          (save-grant! [_ subject grant]
            (swap! grants assoc subject grant)
            grant)
          (revoke-grant! [_ subject]
            (swap! grants dissoc subject))
          auth/MemberGrantStore
          (save-member-grant! [_ identity grant]
            (swap! grants assoc (:subject identity) grant)
            grant))
        drive (reify auth/DriveClient
                (ensure-output-folder! [_ _ existing-folder]
                  (or existing-folder "folder-1")))
        drive-token-client
        (reify auth/DriveTokenClient
          (refresh-drive-token! [_ _]
            {:access-token "private-refreshed-access-token"}))
        system (auth/system
                {:client-id "client-id"
                 :client-secret "client-secret"
                 :base-url (str "http://127.0.0.1:" port)
                 :allowlist #{}
                 :member-directory directory
                 :session-key (.getBytes "01234567890123456789012345678901")
                 :oauth oauth
                 :cipher cipher
                 :grant-store grant-store
                 :drive drive
                 :drive-token-client drive-token-client})
        callback! (fn [flow code accept]
                    (let [browser-cookie
                          (auth/issue-browser-cookie
                           system {:oauth (:stateCookie flow)})]
                      (get! port
                            (str "/v1/auth/login/callback?code=" code
                                 "&state=" (:state flow))
                            {"Accept" accept
                             "Cookie" (str "__session=" browser-cookie)})))
        cookie-token (fn [response]
                       (some-> (first (.allValues (.headers response)
                                                  "Set-Cookie"))
                               (.split "=" 2) second
                               (.split ";" 2) first))
        server (start-api! port
                           {:auth-system system
                            :event-sink #(swap! events conj [%1 %2])})]
    (try
      (let [pre-membership-flow (auth/begin-flow! system :login nil)
            rejected (callback! pre-membership-flow
                                "pre-membership-code" "text/html")]
        (is (= 403 (.statusCode rejected)))
        (is (re-find #"^__session=.*Max-Age=0"
                     (first (.allValues (.headers rejected) "Set-Cookie"))))
        (is (empty? @grants))
        (is (empty? @encrypted))
        (is (empty? (filter #(= "private@example.com" (:email %))
                            (admin/list-member-records directory))))
        (admin/add-member! service owner "private@example.com")
        (let [routine-flow (auth/begin-flow! system :login nil)
              missing-refresh (callback! routine-flow "routine-code" "text/html")
              body (.body missing-refresh)
              recovery-cookie-token (cookie-token missing-refresh)
              recovery-browser-cookie
              (auth/browser-cookie system recovery-cookie-token)
              recovery-cookie (str "__session=" recovery-cookie-token)
              recovery-start
              (get! port "/v1/auth/login/start?recovery=true"
                    {"Cookie" recovery-cookie})
              forged-recovery-start
              (get! port "/v1/auth/login/start?recovery=true" {})
              recovery-location
              (.orElse (.firstValue (.headers recovery-start) "Location") "")
              recovery-state (second (re-find #"[?&]state=([^&]+)"
                                              recovery-location))
              recovery-flow-cookie (cookie-token recovery-start)]
          (is (= 401 (.statusCode missing-refresh)))
          (is (re-find #"Google Drive authorization needs to be renewed" body))
          (is (re-find #"no stored reusable grant" body))
          (is (str/includes? body "<header class=\"product-header\">"))
          (is (str/includes? body
                             "<a class=\"brand\" href=\"/\">Alpha Compose</a>"))
          (is (< (str/index-of body "href=\"/faq\"")
                 (str/index-of body "href=\"/privacy\"")
                 (str/index-of body "href=\"/terms\"")))
          (is (re-find #"href=\"/v1/auth/login/start\?recovery=true\"" body))
          (is (re-find #">Continue with Google<" body))
          (is (nil? (:session recovery-browser-cookie)))
          (is (auth/drive-recovery-token? system
                                          (:oauth recovery-browser-cookie)))
          (is (empty? @grants))
          (is (empty? @encrypted))
          (is (re-find #"prompt=consent" recovery-location))
          (is (not (re-find #"prompt=consent"
                            (.orElse
                             (.firstValue (.headers forged-recovery-start)
                                          "Location")
                             ""))))
          (let [recovered
                (get! port
                      (str "/v1/auth/login/callback?code=recovery-code&state="
                           recovery-state)
                      {"Accept" "text/html"
                       "Cookie" (str "__session=" recovery-flow-cookie)})
                recovered-user
                (auth/session-user system (cookie-token recovered))]
            (is (= 302 (.statusCode recovered)))
            (is (= ["private-recovery-refresh"] @encrypted))
            (is (= 1 (count @grants)))
            (is (= "private@example.com" (:email recovered-user)))
            (is (= :member (:role recovered-user)))))
        (is (= ["not_allowlisted" "missing_refresh_token"]
               (mapv (comp :category second) @events)))
        (is (not (re-find
                  #"pre-membership-code|routine-code|recovery-code|private@example.com|private-google-subject|private-access-token|private.*refresh"
                  (pr-str @events)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest browser-login-callback-renders-a-safe-actionable-drive-access-error
  (let [port (available-port)
        events (atom [])
        oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  {:access-token "private-access-token"
                   :subject "private-google-subject"
                   :email "private@example.com"
                   :email-verified? true
                   :refresh-token "private-refresh-token"
                   :granted-scopes #{"openid" "email" "profile"}}))
        {:keys [system]} (drive-callback-fixture oauth)
        flow (auth/begin-flow! system :login nil)
        browser-cookie (auth/issue-browser-cookie
                        system {:oauth (:stateCookie flow)})
        server (start-api! port
                           {:auth-system system
                            :event-sink #(swap! events conj [%1 %2])})]
    (try
      (let [response (get! port
                           (str "/v1/auth/login/callback?code=private-code&state="
                                (:state flow))
                           {"Accept" "text/html"
                            "Cookie" (str "__session=" browser-cookie)})
            body (.body response)
            event (second (first @events))
            set-cookie (first (.allValues (.headers response) "Set-Cookie"))
            cookie-value (some-> set-cookie
                                 (.split "=" 2) second
                                 (.split ";" 2) first)
            retry-cookie (auth/browser-cookie system cookie-value)
            faq-position (str/index-of body "href=\"/faq\"")
            privacy-position (str/index-of body "href=\"/privacy\"")
            terms-position (str/index-of body "href=\"/terms\"")]
        (is (= 400 (.statusCode response)))
        (is (= "text/html; charset=utf-8"
               (some-> response .headers (.firstValue "content-type")
                       (.orElse nil))))
        (is (= "no-store"
               (some-> response .headers (.firstValue "cache-control")
                       (.orElse nil))))
        (is (= "nosniff"
               (some-> response .headers
                       (.firstValue "x-content-type-options")
                       (.orElse nil))))
        (is (str/includes? body "<header class=\"product-header\">"))
        (is (str/includes? body
                           "<a class=\"brand\" href=\"/\">Alpha Compose</a>"))
        (is (str/includes? body "<nav aria-label=\"Product\">"))
        (is (every? some? [faq-position privacy-position terms-position]))
        (when (every? some? [faq-position privacy-position terms-position])
          (is (< faq-position privacy-position terms-position)))
        (is (not (re-find #"<a[^>]+aria-current=\"page\"" body)))
        (is (str/includes? body "data-theme=\"telemetry\""))
        (is (str/includes? body "--color-background:#031225"))
        (is (str/includes? body "url('/telemetry-background.webp')"))
        (is (str/includes? body ":focus-visible"))
        (is (str/includes? body
                           "<meta name=\"color-scheme\" content=\"dark\">"))
        (is (re-find #"Alpha Compose" body))
        (is (re-find #"Google Drive access could not be established" body))
        (is (re-find #"drive\.file" body))
        (is (re-find #"/v1/auth/login/start\?recovery=true" body))
        (is (nil? (:session retry-cookie)))
        (is (auth/drive-recovery-token? system (:oauth retry-cookie)))
        (is (re-find #"cannot tell.*disabled.*unavailable.*denied.*Workspace"
                     body))
        (doseq [private-value ["private-code" "private-access-token"
                               "private-refresh-token" "private-google-subject"
                               "private@example.com" "openid email profile"]]
          (is (not (re-find (re-pattern private-value) body))))
        (is (= "missing_required_scopes" (:category event)))
        (is (not-any? #(contains? event %)
                      [:code :token :email :subject :scopes :message])))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest browser-login-callback-explains-an-unexpected-permission-without-exposing-it
  (let [port (available-port)
        extra-scope "https://www.googleapis.com/auth/private.extra"
        oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  {:access-token "private-access-token"
                   :subject "private-google-subject"
                   :email "private@example.com"
                   :email-verified? true
                   :refresh-token "private-refresh-token"
                   :granted-scopes (conj (set auth/approved-scopes)
                                         extra-scope)}))
        {:keys [system]} (drive-callback-fixture oauth)
        flow (auth/begin-flow! system :login nil)
        browser-cookie (auth/issue-browser-cookie
                        system {:oauth (:stateCookie flow)})
        server (start-api! port {:auth-system system})]
    (try
      (let [response (get! port
                           (str "/v1/auth/login/callback?code=private-code&state="
                                (:state flow))
                           {"Accept" "text/html"
                            "Cookie" (str "__session=" browser-cookie)})
            body (.body response)]
        (is (= 400 (.statusCode response)))
        (is (re-find #"Google Drive access could not be established" body))
        (is (re-find #"additional permission" body))
        (is (re-find #"cannot tell.*account.*Workspace" body))
        (is (re-find #"/v1/auth/login/start\?recovery=true" body))
        (is (not (re-find (re-pattern extra-scope) body))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest browser-login-callback-offers-verified-nonmembers-early-access-contact
  (let [port (available-port)
        events (atom [])
        {:keys [system]} (drive-callback-fixture
                          (valid-drive-oauth)
                          {:allowlist #{"approved@example.com"}})
        early-access-system
        (early-access/system
         {:proof-key (.getBytes "01234567890123456789012345678901")})
        flow (auth/begin-flow! system :login nil)
        browser-cookie (auth/issue-browser-cookie
                        system {:oauth (:stateCookie flow)})
        server (start-api! port
                           {:auth-system system
                            :early-access-system early-access-system
                            :event-sink #(swap! events conj [%1 %2])})]
    (try
      (let [response (get! port
                           (str "/v1/auth/login/callback?code=private-code&state="
                                (:state flow))
                           {"Accept" "text/html"
                            "Cookie" (str "__session=" browser-cookie)})
            body (.body response)
            proof (second (re-find #"name=\"proof\" value=\"([^\"]+)\""
                                   body))
            set-cookie (first (.allValues (.headers response) "Set-Cookie"))]
        (is (= 403 (.statusCode response)))
        (is (re-find #"Alpha Compose is in early access" body))
        (is (str/includes? body "<header class=\"product-header\">"))
        (is (str/includes? body
                           "<a class=\"brand\" href=\"/\">Alpha Compose</a>"))
        (is (< (str/index-of body "href=\"/faq\"")
               (str/index-of body "href=\"/privacy\"")
               (str/index-of body "href=\"/terms\"")))
        (is (re-find #"limited to approved testers" body))
        (is (re-find #"leave your details" body))
        (is (re-find #"action=\"/v1/early-access/request\"" body))
        (is (re-find #"type=\"email\"[^>]+value=\"owner@example\.com\"[^>]+readonly"
                     body))
        (is (re-find #"name=\"instagram\"[^>]+maxlength=\"64\"" body))
        (is (re-find #"name=\"message\"[^>]+maxlength=\"2000\"" body))
        (is (re-find #"mailto:me@jamiep\.org" body))
        (is (re-find #"No session, Drive grant, membership binding, or render was created"
                     body))
        (is (re-find #"href=\"/v1/auth/login/start\"" body))
        (is (= "owner@example.com"
               (:email (early-access/verify-proof! early-access-system proof))))
        (is (not (re-find #"google-subject-1|private-code" body)))
        (is (re-find #"^__session=.*Max-Age=0" set-cookie))
        (is (= "not_allowlisted" (:category (second (first @events)))))
        (is (not (re-find #"owner@example\.com|google-subject-1|private-code|proof"
                          (pr-str @events)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest verified-early-access-request-succeeds-or-retries-without-application-access
  (let [port (available-port)
        events (atom [])
        notifications (atom [])
        provider-mode (atom :success)
        notifier
        (reify early-access/Notifier
          (send-notification! [_ notification]
            (case @provider-mode
              :success (swap! notifications conj notification)
              :failure (throw (errors/raise! "Provider unavailable"
                                             {:type ::provider-unavailable
                                              :status 503
                                              :retryable true}))
              :unexpected
              (throw (RuntimeException.
                      "owner@example.com private provider response")))))
        {:keys [system]} (drive-callback-fixture
                          (valid-drive-oauth)
                          {:allowlist #{"approved@example.com"}})
        early-access-system
        (early-access/system
         {:proof-key (.getBytes "01234567890123456789012345678901")
          :notifier notifier})
        flow (auth/begin-flow! system :login nil)
        browser-cookie (auth/issue-browser-cookie
                        system {:oauth (:stateCookie flow)})
        server (start-api! port
                           {:auth-system system
                            :early-access-system early-access-system
                            :event-sink #(swap! events conj [%1 %2])})]
    (try
      (let [denial (get! port
                         (str "/v1/auth/login/callback?code=private-code&state="
                              (:state flow))
                         {"Accept" "text/html"
                          "Cookie" (str "__session=" browser-cookie)})
            proof (second (re-find #"name=\"proof\" value=\"([^\"]+)\""
                                   (.body denial)))
            success (post-form! port "/v1/early-access/request"
                                {:proof proof
                                 :email "attacker@example.com"
                                 :instagram "  @runner  "
                                 :message "  Please let me test.  "})]
        (is (= 200 (.statusCode success)))
        (is (re-find #"Request sent" (.body success)))
        (is (re-find #"role=\"status\"[^>]+tabindex=\"-1\""
                     (.body success)))
        (is (not (re-find (re-pattern (java.util.regex.Pattern/quote proof))
                          (.body success))))
        (is (= 1 (count @notifications)))
        (is (= "owner@example.com" (:reply-to (first @notifications))))
        (is (re-find #"Instagram handle: @runner"
                     (:text (first @notifications))))
        (is (not (re-find #"attacker@example\.com"
                          (:text (first @notifications)))))

        (let [invalid (post-form! port "/v1/early-access/request"
                                  {:proof (str proof "tampered")
                                   :message "private-invalid-message"})]
          (is (= 400 (.statusCode invalid)))
          (is (re-find #"could not verify this request" (.body invalid)))
          (is (re-find #"mailto:me@jamiep\.org" (.body invalid)))
          (is (not (re-find #"private-invalid-message|name=\"proof\""
                            (.body invalid))))
          (is (= 1 (count @notifications))))

        (reset! provider-mode :failure)
        (let [failure (post-form! port "/v1/early-access/request"
                                  {:proof proof
                                   :email "attacker@example.com"
                                   :instagram " <script>alert(1)</script> "
                                   :message " retry message "})
              failure-event (second (last @events))]
          (is (= 503 (.statusCode failure)))
          (is (re-find #"could not send your request" (.body failure)))
          (is (re-find #"&lt;script&gt;alert\(1\)&lt;/script&gt;" (.body failure)))
          (is (not (re-find #"<script>alert\(1\)</script>" (.body failure))))
          (is (re-find #"name=\"proof\"" (.body failure)))
          (is (re-find #"mailto:me@jamiep\.org" (.body failure)))
          (is (= "early_access_delivery" (:category failure-event)))
          (is (= 503 (:upstreamStatus failure-event)))
          (is (true? (:retryable failure-event)))
          (is (string? (:sourceFile failure-event)))
          (is (integer? (:sourceLine failure-event)))
          (is (not (re-find #"owner@example\.com|attacker@example\.com|runner|retry message|proof"
                            (pr-str @events)))))

        (reset! provider-mode :unexpected)
        (let [failure (post-form! port "/v1/early-access/request"
                                  {:proof proof
                                   :message "private unexpected message"})
              [event-name failure-event] (last @events)]
          (is (= 502 (.statusCode failure)))
          (is (re-find #"could not send your request" (.body failure)))
          (is (re-find #"name=\"proof\"" (.body failure)))
          (is (= "early_access_notification_failed" event-name))
          (is (= "early_access_delivery" (:category failure-event)))
          (is (true? (:retryable failure-event)))
          (is (string? (:sourceFile failure-event)))
          (is (integer? (:sourceLine failure-event)))
          (is (not (re-find #"owner@example\.com|private provider|unexpected message|proof"
                            (pr-str @events))))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest login-callback-negotiates-invalid-state-for-browser-and-api-clients
  (let [port (available-port)
        events (atom [])
        {:keys [system]} (drive-callback-fixture (valid-drive-oauth))
        server (start-api! port
                           {:auth-system system
                            :event-sink #(swap! events conj [%1 %2])})]
    (try
      (let [request! (fn [accept]
                       (let [flow (auth/begin-flow! system :login nil)
                             browser-cookie
                             (auth/issue-browser-cookie
                              system {:oauth (:stateCookie flow)})]
                         (get! port
                               "/v1/auth/login/callback?code=private-code&state=private-invalid-state"
                               {"Accept" accept
                                "Cookie" (str "__session=" browser-cookie)})))
            browser-response (request! "text/html")
            api-response (request! "application/json")]
        (is (= 400 (.statusCode browser-response)))
        (is (re-find #"Google authorization did not finish"
                     (.body browser-response)))
        (is (not (re-find #"private-code|private-invalid-state"
                          (.body browser-response))))
        (is (= 400 (.statusCode api-response)))
        (is (= {"error" "invalid_oauth_state"}
               (json/read-str (.body api-response))))
        (is (= ["invalid_state" "invalid_state"]
               (mapv (comp :category second) @events))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest browser-login-callback-uses-only-bounded-google-drive-evidence
  (let [port (available-port)
        events (atom [])
        drive (reify auth/DriveClient
                (ensure-output-folder! [_ _ _]
                  (throw (ex-info "Drive unavailable"
                                  {:type ::drive-unavailable
                                   :status 403
                                   :reason "workspace_restricted"}))))
        {:keys [system]} (drive-callback-fixture (valid-drive-oauth)
                                                 {:drive drive})
        flow (auth/begin-flow! system :login nil)
        browser-cookie (auth/issue-browser-cookie
                        system {:oauth (:stateCookie flow)})
        server (start-api! port
                           {:auth-system system
                            :event-sink #(swap! events conj [%1 %2])})]
    (try
      (let [response (get! port
                           (str "/v1/auth/login/callback?code=private-code&state="
                                (:state flow))
                           {"Accept" "text/html"
                            "Cookie" (str "__session=" browser-cookie)})
            body (.body response)
            event (second (first @events))]
        (is (= 502 (.statusCode response)))
        (is (re-find #"Google Workspace restricted Drive access" body))
        (is (re-find #"try again.*administrator" body))
        (is (= "workspace_restricted" (:reason event))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest unexpected-login-callback-failures-remain-bounded-for-browser-and-api
  (let [port (available-port)
        events (atom [])
        oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  (throw (RuntimeException.
                          "private@example.com private-token private-code"))))
        {:keys [system]} (drive-callback-fixture oauth)
        server (start-api! port
                           {:auth-system system
                            :event-sink #(swap! events conj [%1 %2])})]
    (try
      (let [request! (fn [accept]
                       (let [flow (auth/begin-flow! system :login nil)
                             browser-cookie
                             (auth/issue-browser-cookie
                              system {:oauth (:stateCookie flow)})]
                         (get! port
                               (str "/v1/auth/login/callback?code=private-code&state="
                                    (:state flow))
                               {"Accept" accept
                                "Cookie" (str "__session=" browser-cookie)})))
            browser-response (request! "text/html")
            api-response (request! "application/json")]
        (is (= 500 (.statusCode browser-response)))
        (is (re-find #"Google authorization did not finish"
                     (.body browser-response)))
        (is (not (re-find #"private@example\.com|private-token|private-code"
                          (.body browser-response))))
        (is (= {"error" "oauth_callback_failed" "retryable" true}
               (json/read-str (.body api-response))))
        (is (= ["unexpected" "unexpected"]
               (mapv (comp :category second) @events)))
        (is (every? #(not-any? (fn [key] (contains? (second %) key))
                               [:message :reason :email :token :code])
                    @events)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

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
        server (start-api! port {:job-service (:service lifecycle)
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
        server (start-api! port {:job-service (:service lifecycle)
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
        server (start-api! port {:auth-system auth-system
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
        (is (str/includes? (.body response) "data-theme=\"telemetry\""))
        (is (str/includes? (.body response) "--color-background:#031225"))
        (is (str/includes? (.body response)
                           "url('/telemetry-background.webp')"))
        (is (str/includes? (.body response) ":focus-visible"))
        (is (str/includes? (.body response)
                           "<meta name=\"color-scheme\" content=\"dark\">"))
        (is (not (str/includes? (.body response)
                                "<header class=\"product-header\">")))
        (is (re-find #"google\.picker\.PickerBuilder" (.body response)))
        (is (re-find #"picker-access-token" (.body response)))
        (is (re-find #"picker-key" (.body response)))
        (is (re-find #"891643499444" (.body response)))
        (is (re-find #"id=\"picker-selection\"" (.body response)))
        (is (re-find #"selection\.textContent" (.body response)))
        (is (re-find #"google\.picker\.DocsUploadView" (.body response)))
        (is (re-find #"setSelectableMimeTypes" (.body response)))
        (assert-supported-video-picker (.body response))
        (doseq [copy ["My Drive" "shared with you" "Shared Drive"
                      "Folders are for navigation only" "Upload tab"]]
          (is (str/includes? (.body response) copy) copy))
        (is (not (re-find #"setMimeTypes\('video/\*'\)" (.body response))))
        (is (re-find #"/v1/drive/picker/diagnostic" (.body response)))
        (is (re-find #"connect-src 'self' https://www\.googleapis\.com"
                     (some-> response .headers (.firstValue "content-security-policy")
                             (.orElse nil)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest empty-picker-diagnostic-is-csrf-protected-and-privacy-safe
  (let [port (available-port)
        events (atom [])
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
                         {:access-token "diagnostic-access-token"}))
        gateway (reify drive/PickerDiagnostics
                  (picker-diagnostics! [_ access-token]
                    (is (= "diagnostic-access-token" access-token))
                    {:account-status "resolved"
                     :index-status "video-empty"}))
        auth-system (assoc system
                           :grant-store grants
                           :cipher cipher
                           :drive gateway
                           :drive-token-client token-client)
        csrf (auth/issue-csrf-token auth-system
                                    {:subject "google-subject-1"})
        server (start-api! port {:auth-system auth-system
                                 :event-sink #(swap! events conj [%1 %2])})]
    (try
      (let [body {:phase "empty" :view "drive" :listState "empty"}
            denied (post! port "/v1/drive/picker/diagnostic" body
                          {"Cookie" (str "agg_session=" session)
                           "Content-Type" "application/json"})
            response (post! port "/v1/drive/picker/diagnostic" body
                            {"Cookie" (str "agg_session=" session)
                             "Content-Type" "application/json"
                             "X-CSRF-Token" csrf})
            event (second (first @events))]
        (is (= 403 (.statusCode denied)))
        (is (= 200 (.statusCode response)))
        (is (= {"accepted" true} (json/read-str (.body response))))
        (is (= ["picker_diagnostic"] (mapv first @events)))
        (is (= "empty" (:phase event)))
        (is (= "drive" (:view event)))
        (is (= "empty" (:listState event)))
        (is (= "refreshed" (:tokenStatus event)))
        (is (= "resolved" (:accountStatus event)))
        (is (= "supported-source-video-mime-types" (:mimeFilter event)))
        (is (= "video-empty" (:indexStatus event)))
        (is (not-any? #(contains? event %)
                      [:token :accessToken :email :filename :fileId])))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest browser-entrypoint-launches-picker-in-place
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
        server (start-api! port {:auth-system auth-system
                                 :picker-api-key "picker-key"
                                 :picker-app-id "891643499444"})]
    (try
      (let [anonymous (get! port "/" {})
            authenticated (get! port "/"
                                {"Cookie" (str "agg_session=" session)})]
        (is (= 200 (.statusCode anonymous)))
        (is (re-find #"/v1/auth/login/start" (.body anonymous)))
        (is (re-find #"Continue with Google" (.body anonymous)))
        (is (= 200 (.statusCode authenticated)))
        (is (str/includes?
             (.orElse (.firstValue (.headers authenticated)
                                   "Content-Security-Policy")
                      "")
             "media-src 'self'"))
        (is (not (re-find #"/v1/auth/drive/start" (.body authenticated))))
        (is (not (re-find #"Connect Drive" (.body authenticated))))
        (is (re-find #"gapi\.load\('picker'" (.body authenticated)))
        (is (re-find #"google\.picker\.PickerBuilder" (.body authenticated)))
        (is (re-find #"setOAuthToken" (.body authenticated)))
        (is (re-find #"build\(\);picker\.setVisible\(false\)"
                     (.body authenticated)))
        (is (not (re-find #"build\(\)\.setVisible\(false\)"
                          (.body authenticated))))
        (is (re-find #"picker\.setVisible\(true\)" (.body authenticated)))
        (is (re-find #"google\.picker\.Action\.CANCEL" (.body authenticated)))
        (is (re-find #"setSelectableMimeTypes" (.body authenticated)))
        (assert-supported-video-picker (.body authenticated))
        (is (re-find #"id=\"picker-selection\"" (.body authenticated)))
        (is (re-find #"source-video-file-id.*file\.id" (.body authenticated)))
        (doseq [copy ["files shared with you"
                      "folders are only for navigation"
                      "Shared Drive"
                      "upload a source video"
                      "access to that file only"
                      "2 GiB limit"]]
          (is (str/includes? (.body authenticated) copy) copy))
        (is (not (re-find #"window\.open\('/v1/drive/picker'" (.body authenticated))))
        (is (not (re-find #"addEventListener\('message'" (.body authenticated))))
        (is (not (re-find #"Select Drive input" (.body authenticated)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest removed-separate-drive-oauth-routes-return-not-found
  (let [port (available-port)
        {:keys [system]} (auth-fixture)
        server (start-api! port {:auth-system system})]
    (try
      (doseq [path ["/v1/auth/drive/start" "/v1/auth/drive/callback"]]
        (let [response (get! port path {})]
          (is (= 404 (.statusCode response)) path)
          (is (= {"error" "not_found"}
                 (json/read-str (.body response))) path)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest revoked-drive-grant-blocks-submission-clears-the-session-and-enables-signed-recovery
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        {:keys [system session]} (auth-fixture)
        revoked-system
        (assoc system :drive-token-client
               (reify auth/DriveTokenClient
                 (refresh-drive-token! [_ _]
                   (throw (ex-info "invalid grant"
                                   {:type ::auth/revoked-grant})))))
        csrf (auth/issue-csrf-token revoked-system
                                    {:subject "google-subject-1"})
        server (start-api! port {:auth-system revoked-system
                                 :job-service (:service lifecycle)})]
    (try
      (let [response (post! port "/v1/jobs" (fixture/render-request)
                            {"Content-Type" "application/json"
                             "Idempotency-Key" "revoked-drive-preflight"
                             "Cookie" (str "agg_session=" session)
                             "X-CSRF-Token" csrf})
            set-cookie (first (.allValues (.headers response) "Set-Cookie"))
            recovery-cookie (first (.split ^String set-cookie ";" 2))
            recovery-start (get! port "/v1/auth/login/start?recovery=true"
                                 {"Cookie" recovery-cookie})
            forged-recovery (get! port "/v1/auth/login/start?recovery=true" {})]
        (is (= 401 (.statusCode response)))
        (is (= {"error" "drive_grant_required"
                "recoveryPath" "/v1/auth/login/start?recovery=true"}
               (json/read-str (.body response))))
        (is (empty? (get @(:state lifecycle) :jobs)))
        (is (re-find #"^__session=" set-cookie))
        (is (re-find #"prompt=consent"
                     (.orElse (.firstValue (.headers recovery-start) "Location")
                              "")))
        (is (not (re-find #"prompt=consent"
                          (.orElse (.firstValue (.headers forged-recovery) "Location")
                                   "")))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest crafted-unsupported-source-is-rejected-before-job-creation
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        {:keys [system session]} (auth-fixture)
        source-gateway
        (reify drive/SourceGateway
          (source-metadata! [_ _ file-id]
            {:id file-id
             :name "crafted.video"
             :mimeType "video/x-unsupported"
             :size 1024
             :trashed false})
          (stream-source! [_ _ _ _]
            (throw (AssertionError. "Rejected source must not stream"))))
        auth-system (assoc system :drive source-gateway)
        csrf (auth/issue-csrf-token auth-system
                                    {:subject "google-subject-1"})
        server (start-api! port {:auth-system auth-system
                                 :job-service (:service lifecycle)})]
    (try
      (let [response (post! port "/v1/jobs"
                            (assoc (fixture/render-request)
                                   :sourceVideo {:fileId "crafted-file-id"})
                            {"Content-Type" "application/json"
                             "Idempotency-Key" "crafted-source"
                             "Cookie" (str "agg_session=" session)
                             "X-CSRF-Token" csrf})]
        (is (= 400 (.statusCode response)))
        (is (= {"error" "invalid_source_video"}
               (json/read-str (.body response))))
        (is (empty? (:jobs @(:state lifecycle)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest selected-drive-video-opens-ranged-browser-playback
  (let [port (available-port)
        {:keys [system session]} (auth-fixture)
        source-bytes (.getBytes "0123456789abcdefghij"
                                java.nio.charset.StandardCharsets/UTF_8)
        source-gateway
        (reify
          drive/SourceGateway
          (source-metadata! [_ access-token file-id]
            (is (= "drive-access" access-token))
            {:id file-id
             :name "selected.mp4"
             :mimeType "video/mp4"
             :size (alength source-bytes)
             :trashed false})
          (stream-source! [_ _ _ _]
            (throw (AssertionError. "Playback must use ranged streaming")))
          drive/PlaybackGateway
          (open-source-range! [_ access-token file-id byte-range]
            (is (= "drive-access" access-token))
            (is (= "private-drive-file" file-id))
            (is (= {:start 6 :end 10} byte-range))
            {:status 206
             :headers {"content-range" "bytes 6-10/20"
                       "content-length" "5"}
             :body (java.io.ByteArrayInputStream.
                    (java.util.Arrays/copyOfRange source-bytes 6 11))}))
        auth-system (assoc system :drive source-gateway)
        csrf (auth/issue-csrf-token auth-system
                                    {:subject "google-subject-1"})
        server (start-api! port {:auth-system auth-system})]
    (try
      (let [created
            (post! port "/v1/drive/playback-sessions"
                   {:fileId "private-drive-file"}
                   {"Content-Type" "application/json"
                    "Cookie" (str "agg_session=" session)
                    "X-CSRF-Token" csrf})
            created-body (json/read-str (.body created))
            playback-url (get created-body "playbackUrl")
            set-cookie (.orElse
                        (.firstValue (.headers created) "Set-Cookie") "")
            playback-cookie (first (.split set-cookie ";" 2))
            streamed
            (get! port playback-url
                  {"Cookie" (str "agg_session=" session "; " playback-cookie)
                   "Range" "bytes=6-10"})]
        (is (= 201 (.statusCode created)))
        (is (re-matches #"/v1/drive/playback/[0-9a-f-]{36}" playback-url))
        (is (not (str/includes? playback-url "private-drive-file")))
        (is (str/starts-with? set-cookie "__session="))
        (is (str/includes? set-cookie "HttpOnly"))
        (is (str/includes? set-cookie "Path=/"))
        (is (= 206 (.statusCode streamed)))
        (is (= "bytes 6-10/20"
               (.orElse (.firstValue (.headers streamed) "Content-Range") "")))
        (is (= "bytes"
               (.orElse (.firstValue (.headers streamed) "Accept-Ranges") "")))
        (is (= "video/mp4"
               (.orElse (.firstValue (.headers streamed) "Content-Type") "")))
        (is (= "6789a" (.body streamed))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest drive-playback-supports-browser-ranges-and-rejects-invalid-or-unowned-sessions
  (let [port (available-port)
        {:keys [system session]} (auth-fixture)
        source-bytes (.getBytes "0123456789abcdefghij"
                                java.nio.charset.StandardCharsets/UTF_8)
        ranges (atom [])
        source-gateway
        (reify
          drive/SourceGateway
          (source-metadata! [_ _ file-id]
            {:id file-id
             :name "selected.mp4"
             :mimeType "video/mp4"
             :size (alength source-bytes)
             :trashed false})
          (stream-source! [_ _ _ _]
            (throw (AssertionError. "Playback must use ranged streaming")))
          drive/PlaybackGateway
          (open-source-range! [_ _ _ {:keys [start end] :as byte-range}]
            (swap! ranges conj byte-range)
            {:status 206
             :headers {"content-range"
                       (str "bytes " start "-" end "/"
                            (alength source-bytes))
                       "content-length" (str (inc (- end start)))}
             :body (java.io.ByteArrayInputStream.
                    (java.util.Arrays/copyOfRange source-bytes start (inc end)))}))
        auth-system (assoc system :drive source-gateway)
        csrf (auth/issue-csrf-token auth-system
                                    {:subject "google-subject-1"})
        other-session
        (auth/issue-session auth-system {:subject "google-subject-2"
                                         :email "owner@example.com"})
        server (start-api! port {:auth-system auth-system})]
    (try
      (let [created
            (post! port "/v1/drive/playback-sessions"
                   {:fileId "private-drive-file"}
                   {"Content-Type" "application/json"
                    "Cookie" (str "agg_session=" session)
                    "X-CSRF-Token" csrf})
            playback-url (get (json/read-str (.body created)) "playbackUrl")
            playback-cookie
            (-> (.firstValue (.headers created) "Set-Cookie")
                (.orElse "")
                (.split ";" 2)
                first)
            playback-browser-token
            (second (.split playback-cookie "=" 2))
            playback-token
            (:playback (auth/browser-cookie auth-system
                                            playback-browser-token))
            unowned-browser-token
            (auth/issue-browser-cookie auth-system
                                       {:session other-session
                                        :playback playback-token})
            cookies (str "agg_session=" session "; " playback-cookie)
            complete (get! port playback-url {"Cookie" cookies})
            open-ended (get! port playback-url
                             {"Cookie" cookies "Range" "bytes=15-"})
            suffix (get! port playback-url
                         {"Cookie" cookies "Range" "bytes=-4"})
            invalid (get! port playback-url
                          {"Cookie" cookies "Range" "bytes=20-"})
            multiple (get! port playback-url
                           {"Cookie" cookies
                            "Range" "bytes=0-1,4-5"})
            unowned (get! port playback-url
                          {"Cookie" (str "__session=" unowned-browser-token)
                           "Range" "bytes=0-1"})]
        (is (= 200 (.statusCode complete)))
        (is (= "0123456789abcdefghij" (.body complete)))
        (is (= 206 (.statusCode open-ended)))
        (is (= "bytes 15-19/20"
               (.orElse (.firstValue (.headers open-ended) "Content-Range")
                        "")))
        (is (= "fghij" (.body open-ended)))
        (is (= 206 (.statusCode suffix)))
        (is (= "bytes 16-19/20"
               (.orElse (.firstValue (.headers suffix) "Content-Range") "")))
        (is (= "ghij" (.body suffix)))
        (doseq [response [invalid multiple]]
          (is (= 416 (.statusCode response)))
          (is (= "bytes */20"
                 (.orElse (.firstValue (.headers response) "Content-Range")
                          ""))))
        (is (= 401 (.statusCode unowned)))
        (is (= [{:start 0 :end 19}
                {:start 15 :end 19}
                {:start 16 :end 19}]
               @ranges)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest renderable-non-mp4-source-is-kept-but-not-opened-for-browser-playback
  (let [port (available-port)
        {:keys [system session]} (auth-fixture)
        gateway
        (reify
          drive/SourceGateway
          (source-metadata! [_ _ file-id]
            {:id file-id
             :name "selected.mov"
             :mimeType "video/quicktime"
             :size 100
             :trashed false})
          (stream-source! [_ _ _ _] nil)
          drive/PlaybackGateway
          (open-source-range! [_ _ _ _]
            (throw (AssertionError. "Unsupported playback must not stream"))))
        auth-system (assoc system :drive gateway)
        csrf (auth/issue-csrf-token auth-system
                                    {:subject "google-subject-1"})
        server (start-api! port {:auth-system auth-system})]
    (try
      (let [response
            (post! port "/v1/drive/playback-sessions"
                   {:fileId "renderable-private-mov"}
                   {"Content-Type" "application/json"
                    "Cookie" (str "agg_session=" session)
                    "X-CSRF-Token" csrf})]
        (is (= 415 (.statusCode response)))
        (is (= {"error" "browser_playback_not_supported"}
               (json/read-str (.body response))))
        (is (.isEmpty (.firstValue (.headers response) "Set-Cookie"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest playback-session-rejects-unbounded-or-ambiguous-source-payloads
  (let [port (available-port)
        {:keys [system session]} (auth-fixture)
        csrf (auth/issue-csrf-token system {:subject "google-subject-1"})
        server (start-api! port {:auth-system system})
        headers {"Content-Type" "application/json"
                 "Cookie" (str "agg_session=" session)
                 "X-CSRF-Token" csrf}]
    (try
      (doseq [body [{} {:fileId ""}
                    {:fileId (apply str (repeat 257 "x"))}
                    {:fileId "video-1" :unexpected true}]]
        (let [response (post! port "/v1/drive/playback-sessions"
                              body headers)]
          (is (= 400 (.statusCode response)))
          (is (= {"error" "invalid_playback_source"}
                 (json/read-str (.body response))))))
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
        server (start-api! port {:job-service (:service lifecycle)
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
