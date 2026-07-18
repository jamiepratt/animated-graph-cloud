(ns agg.api.main
  (:require [agg.errors :as errors]
            [agg.admin.core :as admin]
            [agg.contracts.render :as contract]
            [agg.auth.core :as auth]
            [agg.jobs.gcp :as gcp]
            [agg.jobs.lifecycle :as jobs]
            [agg.logs.core :as logs]
            [agg.observability :as observability]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.renderer.main :as renderer]
            [agg.tokens.core :as tokens]
            [agg.ui.core :as ui]
            [clojure.data.json :as json])
  (:gen-class)
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io ByteArrayOutputStream)
           (java.net InetSocketAddress URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path)
           (java.time Instant)
           (java.util Base64 UUID)))

(def ^:private health-body "{\"status\":\"ok\"}")

(def max-request-bytes contract/max-render-request-bytes)

(defn- log-event! [event fields]
  (observability/emit-event! "api" event fields))

(defn- emit-event! [dependencies event fields]
  ((or (:event-sink dependencies) log-event!) event fields))

(defn- oauth-callback-failure [type]
  (case type
    ::auth/revoked-grant
    {:category "invalid_grant"
     :status 401
     :body {:error "drive_grant_required"}}

    ::auth/missing-refresh-token
    {:category "missing_refresh_token"
     :status 401
     :body {:error "drive_grant_required"}}

    ::auth/invalid-drive-scopes
    {:category "unexpected_scopes"
     :status 400
     :body {:error "invalid_drive_scopes"}}

    ::auth/invalid-code
    {:category "invalid_code"
     :status 400
     :body {:error "invalid_oauth_code"}}

    ::auth/oauth-exchange-failed
    {:category "oauth_exchange"
     :status 502
     :body {:error "oauth_exchange_failed" :retryable true}}

    ::auth/missing-access-token
    {:category "oauth_exchange"
     :status 502
     :body {:error "oauth_exchange_failed" :retryable true}}

    ::auth/drive-unavailable
    {:category "drive"
     :status 502
     :body {:error "drive_unavailable" :retryable true}}

    ::auth/kms-unavailable
    {:category "kms"
     :status 503
     :body {:error "kms_unavailable" :retryable true}}

    ::auth/grant-persistence-failed
    {:category "grant_persistence"
     :status 503
     :body {:error "grant_persistence_failed" :retryable true}}

    nil))

(defn- log-oauth-callback-failure! [dependencies request-id failure]
  (emit-event! dependencies "oauth_callback_failed"
               {:severity (if (>= (:status failure) 500) "ERROR" "WARNING")
                :requestId request-id
                :category (:category failure)
                :status (:status failure)}))

(defn- respond-bytes! [^HttpExchange exchange status content-type bytes]
  (doto (.getResponseHeaders exchange)
    (.set "Content-Type" content-type)
    (.set "Cache-Control" "no-store")
    (.set "X-Content-Type-Options" "nosniff"))
  (.sendResponseHeaders exchange status (alength ^bytes bytes))
  (with-open [response-body (.getResponseBody exchange)]
    (.write response-body ^bytes bytes)))

(defn- respond! [exchange status content-type body]
  (respond-bytes! exchange
                  status
                  content-type
                  (.getBytes ^String body StandardCharsets/UTF_8)))

(defn- respond-json! [exchange status body]
  (respond! exchange status "application/json; charset=utf-8"
            (json/write-str body)))

(defn- respond-redirect! [^HttpExchange exchange location cookies]
  (doto (.getResponseHeaders exchange)
    (.set "Location" location)
    (.set "Cache-Control" "no-store")
    (.set "X-Content-Type-Options" "nosniff"))
  (doseq [cookie cookies]
    (.add (.getResponseHeaders exchange) "Set-Cookie" cookie))
  (.sendResponseHeaders exchange 302 -1)
  (.close (.getResponseBody exchange)))

(defn- cookies [^HttpExchange exchange]
  (->> (some-> exchange .getRequestHeaders (.getFirst "Cookie"))
       (#(or % ""))
       (#(.split ^String % ";"))
       (keep (fn [part]
               (let [[key value] (.split (.trim ^String part) "=" 2)]
                 (when (and key value) [key value]))))
       (into {})))

(defn- query-params [^HttpExchange exchange]
  (->> (some-> exchange .getRequestURI .getRawQuery)
       (#(or % ""))
       (#(.split ^String % "&"))
       (keep (fn [part]
               (when-not (.isBlank ^String part)
                 (let [[key value] (.split ^String part "=" 2)]
                   [(URLDecoder/decode key StandardCharsets/UTF_8)
                    (URLDecoder/decode (or value "")
                                       StandardCharsets/UTF_8)]))))
       (into {})))

(defn- browser-cookie [exchange auth-system]
  (let [cookie (get (cookies exchange) "__session")]
    (when auth-system
      (auth/browser-cookie auth-system cookie))))

(defn- session-token [exchange auth-system]
  (let [cookie (cookies exchange)
        browser (browser-cookie exchange auth-system)]
    (if (some? browser)
      (:session browser)
      (or (get cookie "__session")
          (get cookie "agg_session")))))

(defn- oauth-state-token [exchange auth-system]
  (let [cookie (cookies exchange)
        browser (browser-cookie exchange auth-system)]
    (if (some? browser)
      (:oauth browser)
      (get cookie "agg_oauth_state"))))

(defn- require-user! [exchange auth-system]
  (when auth-system
    (auth/session-user auth-system (session-token exchange auth-system))))

(defn- browser-cookie-header [value]
  (str "__session=" value
       "; Max-Age=43200; Path=/; Secure; HttpOnly; SameSite=Lax"))

(defn- session-cookie [value]
  (str "__session=" value
       "; Max-Age=43200; Path=/; Secure; HttpOnly; SameSite=Lax"))

(defn- clear-legacy-oauth-cookie []
  "agg_oauth_state=; Max-Age=0; Path=/v1/auth; Secure; HttpOnly; SameSite=Lax")

(defn- respond-path! [^HttpExchange exchange content-type ^Path path]
  (doto (.getResponseHeaders exchange)
    (.set "Content-Type" content-type)
    (.set "Cache-Control" "no-store")
    (.set "X-Content-Type-Options" "nosniff"))
  (.sendResponseHeaders exchange 200 (Files/size path))
  (with-open [input (Files/newInputStream path (make-array OpenOption 0))
              response-body (.getResponseBody exchange)]
    (.transferTo input response-body)))

(defn- request-json [^HttpExchange exchange]
  (let [bytes (with-open [input (.getRequestBody exchange)]
                (.readNBytes input (inc max-request-bytes)))]
    (when (> (alength bytes) max-request-bytes)
      (throw (errors/raise! "Request exceeds the size limit"
                      {:type ::request-too-large})))
    (json/read-str (String. bytes StandardCharsets/UTF_8) :key-fn keyword)))

(defn- request-form [^HttpExchange exchange]
  (let [bytes (with-open [input (.getRequestBody exchange)]
                (.readNBytes input (inc max-request-bytes)))]
    (when (> (alength bytes) max-request-bytes)
      (throw (errors/raise! "Request exceeds the size limit"
                      {:type ::request-too-large})))
    (->> (.split (String. bytes StandardCharsets/UTF_8) "&")
         (keep (fn [part]
                 (when-not (.isBlank ^String part)
                   (let [[key value] (.split ^String part "=" 2)]
                     [(URLDecoder/decode key StandardCharsets/UTF_8)
                      (URLDecoder/decode (or value "")
                                         StandardCharsets/UTF_8)]))))
         (into {}))))

(defn- request-render-request [exchange]
  (try
    (let [request (request-json exchange)]
      {:request request
       :render-spec (contract/prepare request)})
    (catch clojure.lang.ExceptionInfo error
      (if (= ::request-too-large (:type (ex-data error)))
        (throw error)
        (throw (errors/raise! "Invalid render request"
                        {:type ::invalid-request}
                        error))))
    (catch Throwable error
      (throw (errors/raise! "Invalid render request"
                      {:type ::invalid-request}
                      error)))))

(defn- request-render-spec [exchange]
  (:render-spec (request-render-request exchange)))

(defn- ui-render-request [exchange]
  (try
    (let [request (json/read-str (get (request-form exchange) "request")
                                 :key-fn keyword)]
      {:request request :render-spec (contract/prepare request)})
    (catch clojure.lang.ExceptionInfo error
      (if (= ::request-too-large (:type (ex-data error)))
        (throw error)
        (throw (errors/raise! "Invalid render request"
                        {:type ::invalid-request}
                        error))))
    (catch Throwable error
      (throw (errors/raise! "Invalid render request"
                      {:type ::invalid-request}
                      error)))))

(defn- preview! [exchange frame-renderer]
  (let [render-spec (request-render-spec exchange)
        output (ByteArrayOutputStream.)]
    (frames/render-preview! frame-renderer render-spec output)
    (respond-bytes! exchange 200 "image/png" (.toByteArray output))))

(defn- overlay! [exchange frame-renderer video-encoder]
  (let [render-spec (request-render-spec exchange)
        output-path (Files/createTempFile
                     "agg-overlay-"
                     ".mov"
                     (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (renderer/render! (assoc render-spec
                               :output-path output-path
                               :profile? false)
                        {:frame-renderer frame-renderer
                         :video-encoder video-encoder})
      (respond-path! exchange "video/quicktime" output-path)
      (finally
        (Files/deleteIfExists output-path)))))

(defn- submit-job! [^HttpExchange exchange job-service user]
  (let [idempotency-key (some-> exchange .getRequestHeaders
                                (.getFirst "Idempotency-Key"))
        request (cond-> (:request (request-render-request exchange))
                  user (assoc :requesterSubject (:subject user)
                              :requesterEmail (:email user)
                              :requesterMembershipVersion
                              (:membership-version user)))
        {:keys [created? job]}
        (jobs/submit-job! job-service idempotency-key request)]
    (respond-json! exchange (if created? 202 200) job)))

(defn- poll-job! [exchange job-service job-id]
  (if-let [job (jobs/get-job job-service job-id)]
    (respond-json! exchange 200 job)
    (respond-json! exchange 404 {:error "job_not_found"})))

(defn- require-job-owner! [job-service user job-id]
  (when (and user
             (not (jobs/owns-job? job-service job-id (:subject user))))
    (throw (errors/raise! "Job does not exist" {:type ::jobs/job-not-found}))))

(defn- bearer-token [^HttpExchange exchange]
  (some-> exchange .getRequestHeaders (.getFirst "Authorization")
          (#(when (and % (.startsWith ^String % "Bearer "))
              (subs % 7)))
          not-empty))

(defn- authenticated-user! [exchange auth-system token-service]
  (when auth-system
    (if-let [session (session-token exchange auth-system)]
      (assoc (auth/session-user auth-system session) :auth-kind :session)
      (if-let [token (and token-service (bearer-token exchange))]
        (->> (tokens/authenticate token-service token)
             (auth/require-allowlisted! auth-system)
             (#(assoc % :auth-kind :token)))
        (throw (errors/raise! "Authentication is required"
                        {:type ::auth/invalid-session}))))))

(defn- require-csrf! [^HttpExchange exchange auth-system user]
  (when (= :session (:auth-kind user))
    (auth/verify-csrf!
     auth-system user
     (some-> exchange .getRequestHeaders (.getFirst "X-CSRF-Token"))))
  user)

(defn- require-session-user! [exchange auth-system]
  (assoc (require-user! exchange auth-system) :auth-kind :session))

(defn- verified-internal-caller?
  [exchange {:keys [auth-system task-token-verifier task-audience]}
   marker-header expected-service-account]
  (and (some-> exchange .getRequestHeaders
               (.getFirst marker-header) not-empty)
       (if-not auth-system
         true
         (when-let [token (bearer-token exchange)]
           (try
             (let [{:keys [issuer audience email email-verified?]}
                   (auth/verify-task-token! task-token-verifier token)]
               (and (contains? #{"accounts.google.com"
                                 "https://accounts.google.com"}
                               issuer)
                    (= task-audience audience)
                    (= expected-service-account email)
                    email-verified?))
             (catch Throwable _ false))))))

(defn- verified-task? [exchange {:keys [tasks-service-account] :as dependencies}]
  (verified-internal-caller? exchange dependencies "X-CloudTasks-TaskName"
                             tasks-service-account))

(defn- dispatch-job! [exchange dependencies job-id]
  (if-not (verified-task? exchange dependencies)
    (respond-json! exchange 401 {:error "authenticated_task_required"})
    (let [{:keys [started? job]}
          (jobs/dispatch-job! (:job-service dependencies) job-id)]
      (when started?
        (let [created-at (Instant/parse (:createdAt job))
              queue-age-ms (max 0 (.toMillis
                                   (java.time.Duration/between
                                    created-at (Instant/now))))]
          (emit-event! dependencies "render_dispatched"
                       {:queueAgeMs queue-age-ms})))
      (respond-json! exchange (if started? 202 200) job))))

(defn- reconcile-jobs! [exchange dependencies]
  (if-not (verified-internal-caller?
           exchange dependencies "X-CloudScheduler"
           (:scheduler-service-account dependencies))
    (respond-json! exchange 401 {:error "authenticated_scheduler_required"})
    (let [{:keys [repaired-jobs released-leases]}
          (jobs/reconcile-jobs! (:job-service dependencies))]
      (emit-event! dependencies "reconciliation_complete"
                   {:severity (if (pos? (+ repaired-jobs released-leases))
                                "WARNING"
                                "INFO")
                    :repairedJobs repaired-jobs
                    :releasedLeases released-leases})
      (respond-json! exchange 200 {:repairedJobs repaired-jobs
                                   :releasedLeases released-leases}))))

(defn- begin-auth! [exchange auth-system flow]
  (let [session (session-token exchange auth-system)
        started (auth/begin-flow! auth-system flow
                                  (when (= :drive flow) session))
        cookie (auth/issue-browser-cookie
                auth-system
                {:session session
                 :oauth (:stateCookie started)})]
    (respond-redirect! exchange (:authorizationUrl started)
                       [(browser-cookie-header cookie)])))

(defn- finish-login! [exchange auth-system]
  (let [params (query-params exchange)
        result (auth/finish-login! auth-system
                                   {:code (get params "code")
                                    :state (get params "state")
                                    :state-cookie (oauth-state-token
                                                   exchange auth-system)})]
    (respond-redirect! exchange "/"
                       [(session-cookie (:session result))
                        (clear-legacy-oauth-cookie)])))

(defn- finish-drive! [exchange auth-system]
  (let [params (query-params exchange)
        session (session-token exchange auth-system)
        _ (auth/finish-drive! auth-system
                              {:code (get params "code")
                               :state (get params "state")
                               :state-cookie (oauth-state-token
                                              exchange auth-system)})]
    (respond-redirect! exchange "/?drive=connected"
                       [(session-cookie session)
                        (clear-legacy-oauth-cookie)])))

(defn- log-options [^HttpExchange exchange]
  (let [params (query-params exchange)
        options (logs/normalize-options
                 {:severity (get params "severity")
                  :component (get params "component")})]
    (assoc options :view (if (= "raw" (get params "view"))
                           "raw"
                           "formatted"))))

(defn- require-administrator-session-user! [exchange auth-system admin-service]
  (let [user (require-session-user! exchange auth-system)]
    (admin/list-members admin-service user)
    user))

(defn- landing! [^HttpExchange exchange auth-system token-service admin-service
                log-store]
  (let [user (when-let [session (session-token exchange auth-system)]
               (auth/session-user auth-system session))
        body
        (if user
          (ui/page {:user user
                    :csrf (auth/issue-csrf-token auth-system user)
                    :tokens (when token-service
                              (tokens/list-tokens token-service
                                                  (:subject user)))
                    :members (when (and admin-service
                                        (admin/administrator? (:role user)))
                               (admin/list-members admin-service user))
                    :logs-enabled? (boolean log-store)})
          ui/anonymous-page)]
    (doto (.getResponseHeaders exchange)
      (.set "Cache-Control" "no-store")
      (.set "Referrer-Policy" "no-referrer")
      (.set "Content-Security-Policy"
            "default-src 'none'; script-src 'unsafe-inline' https://cdn.jsdelivr.net; style-src 'unsafe-inline'; img-src data:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"))
    (respond! exchange 200 "text/html; charset=utf-8" body)))

(defn- picker! [^HttpExchange exchange auth-system picker-api-key picker-app-id]
  (let [user (require-user! exchange auth-system)
        {:keys [access-token]} (auth/drive-access! auth-system (:subject user))]
    (when-not (and (not-empty picker-api-key) (not-empty picker-app-id))
      (throw (errors/raise! "Google Picker is not configured"
                      {:type ::picker-not-configured})))
    (let [token (json/write-str access-token)
          api-key (json/write-str picker-api-key)
          app-id (json/write-str picker-app-id)
          html
          (str "<!doctype html><html><head><meta charset=\"utf-8\">"
               "<title>Select Drive input</title>"
               "<script src=\"https://apis.google.com/js/api.js\"></script>"
               "</head><body><p>Opening Google Drive Picker…</p>"
               "<p>Selected: <output id=\"picker-selection\">None</output></p><script>"
               "const selection=document.getElementById('picker-selection');"
               "function pickerCallback(data){"
               "if(data.action===google.picker.Action.PICKED){"
               "const files=data.docs.map(d=>({id:d.id,name:d.name,mimeType:d.mimeType}));"
               "selection.textContent=files.map(file=>file.name).join(', ')||'None';"
               "if(window.opener){window.opener.postMessage({type:'agg-picker',files},location.origin);}}}"
               "function openPicker(){gapi.load('picker',()=>{"
               "const view=new google.picker.DocsView().setIncludeFolders(false)"
               ".setMimeTypes('text/csv,image/png,application/octet-stream');"
               "new google.picker.PickerBuilder().addView(view)"
               ".setOAuthToken(" token ").setDeveloperKey(" api-key ")"
               ".setAppId(" app-id ").setOrigin(location.origin)"
               ".setCallback(pickerCallback).build().setVisible(true);});}"
               "openPicker();</script></body></html>")]
      (doto (.getResponseHeaders exchange)
        (.set "Cache-Control" "no-store")
        (.set "Referrer-Policy" "no-referrer")
        (.set "Content-Security-Policy"
              "default-src 'none'; script-src 'unsafe-inline' https://apis.google.com https://www.gstatic.com; frame-src https://docs.google.com https://accounts.google.com; style-src 'unsafe-inline'; connect-src https://www.googleapis.com; base-uri 'none'; frame-ancestors 'none'"))
      (respond! exchange 200 "text/html; charset=utf-8" html))))

(defn- cancel-job! [exchange job-service job-id]
  (respond-json! exchange 200 (jobs/cancel-job! job-service job-id)))

(defn- retry-job! [exchange job-service job-id]
  (respond-json! exchange 202 (jobs/retry-job! job-service job-id)))

(defn- issue-upload! [exchange upload-signer]
  (let [{:keys [contentType contentLength]} (request-json exchange)]
    (when-not (and (= "application/json" contentType)
                   (integer? contentLength)
                   (<= 1 contentLength max-request-bytes))
      (throw (errors/raise! "Invalid upload request"
                      {:type ::invalid-upload-request})))
    (let [upload-id (str (UUID/randomUUID))
          object-name (str "uploads/" upload-id "/request.json")
          expires-at (.plusSeconds (Instant/now) 900)]
      (respond-json!
       exchange 201
       {:id upload-id
        :method "PUT"
        :contentType contentType
        :contentLength contentLength
        :expiresAt (str expires-at)
        :uploadUrl (jobs/signed-upload upload-signer object-name
                                       contentType 900)}))))

(defn- create-personal-token! [exchange token-service user]
  (let [{:keys [name]} (request-json exchange)]
    (respond-json! exchange 201 (tokens/create-token! token-service user name))))

(defn- list-personal-tokens! [exchange token-service user]
  (respond-json! exchange 200
                 (tokens/list-tokens token-service (:subject user))))

(defn- revoke-personal-token! [exchange token-service user token-id]
  (respond-json! exchange 200
                 (tokens/revoke-token! token-service (:subject user) token-id)))

(defn- list-members! [exchange admin-service user]
  (respond-json! exchange 200 (admin/list-members admin-service user)))

(defn- add-member! [exchange admin-service user]
  (let [{:keys [email]} (request-json exchange)]
    (respond-json! exchange 201 (admin/add-member! admin-service user email))))

(defn- revoke-member! [exchange admin-service user]
  (let [{:keys [email]} (request-json exchange)]
    (respond-json! exchange 200 (admin/revoke-member! admin-service user email))))

(defn- preview-ui! [exchange frame-renderer]
  (let [render-spec (:render-spec (ui-render-request exchange))
        output (ByteArrayOutputStream.)]
    (frames/render-preview! frame-renderer render-spec output)
    (respond! exchange 200 "text/html; charset=utf-8"
              (ui/preview-fragment
               (.encodeToString (Base64/getEncoder) (.toByteArray output))))))

(defn- submit-job-ui! [exchange job-service user]
  (let [request (assoc (:request (ui-render-request exchange))
                       :requesterSubject (:subject user)
                       :requesterEmail (:email user)
                       :requesterMembershipVersion
                       (:membership-version user))
        {:keys [created? job]}
        (jobs/submit-job! job-service (str "ui-" (UUID/randomUUID)) request)]
    (respond! exchange (if created? 202 200) "text/html; charset=utf-8"
              (ui/job-fragment job))))

(defn- poll-job-ui! [exchange job-service job-id]
  (if-let [job (jobs/get-job job-service job-id)]
    (respond! exchange 200 "text/html; charset=utf-8" (ui/job-fragment job))
    (respond! exchange 404 "text/html; charset=utf-8" "Job not found")))

(defn- create-token-ui! [exchange token-service user]
  (let [created (tokens/create-token! token-service user
                                      (get (request-form exchange) "name"))]
    (respond! exchange 201 "text/html; charset=utf-8"
              (ui/token-panel
               (tokens/list-tokens token-service (:subject user))
               created))))

(defn- list-tokens-ui! [exchange token-service user]
  (respond! exchange 200 "text/html; charset=utf-8"
            (ui/token-panel
             (tokens/list-tokens token-service (:subject user)))))

(defn- revoke-token-ui! [exchange token-service user token-id]
  (tokens/revoke-token! token-service (:subject user) token-id)
  (list-tokens-ui! exchange token-service user))

(defn- members-ui! [exchange admin-service user]
  (respond! exchange 200 "text/html; charset=utf-8"
            (ui/member-panel (admin/list-members admin-service user))))

(defn- logs-ui! [exchange admin-service log-store auth-system]
  (let [user (require-administrator-session-user! exchange auth-system
                                                   admin-service)
        options (log-options exchange)
        entries (mapv logs/public-entry
                      (logs/list-logs log-store options))]
    (doto (.getResponseHeaders exchange)
      (.set "Referrer-Policy" "no-referrer")
      (.set "Content-Security-Policy"
            "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"))
    (respond! exchange 200 "text/html; charset=utf-8"
              (ui/logs-page {:user user
                             :logs entries
                             :view (:view options)
                             :severity (:severity options)
                             :component (:component options)}))))

(defn- add-member-ui! [exchange admin-service user]
  (admin/add-member! admin-service user (get (request-form exchange) "email"))
  (members-ui! exchange admin-service user))

(defn- revoke-member-ui! [exchange admin-service user]
  (admin/revoke-member! admin-service user (get (request-form exchange) "email"))
  (members-ui! exchange admin-service user))

(defn- route-handler [{:keys [frame-renderer video-encoder job-service
                              upload-signer auth-system picker-api-key
                              picker-app-id token-service admin-service log-store]
                       :as dependencies}]
  (reify HttpHandler
    (handle [_ exchange]
      (let [method (.getRequestMethod exchange)
            path (some-> exchange .getRequestURI .getPath)
            request-id (str (UUID/randomUUID))]
        (.set (.getResponseHeaders exchange) "X-Request-Id" request-id)
        (try
          (observability/trace! ::api-request
            (cond
            (and (= "GET" method) (= "/health" path))
            (respond! exchange 200 "application/json; charset=utf-8" health-body)

            (and auth-system (= "GET" method) (= "/" path))
            (landing! exchange auth-system token-service admin-service log-store)

            (and (= "GET" method) (= "/privacy" path))
            (respond! exchange 200 "text/html; charset=utf-8" ui/privacy-page)

            (and (= "GET" method) (= "/terms" path))
            (respond! exchange 200 "text/html; charset=utf-8" ui/terms-page)

            (and auth-system (= "GET" method)
                 (= "/v1/auth/login/start" path))
            (begin-auth! exchange auth-system :login)

            (and auth-system (= "GET" method)
                 (= "/v1/auth/login/callback" path))
            (finish-login! exchange auth-system)

            (and auth-system (= "GET" method)
                 (= "/v1/auth/drive/start" path))
            (do (require-user! exchange auth-system)
                (begin-auth! exchange auth-system :drive))

            (and auth-system (= "GET" method)
                 (= "/v1/auth/drive/callback" path))
            (finish-drive! exchange auth-system)

            (and auth-system (= "GET" method)
                 (= "/v1/drive/picker" path))
            (picker! exchange auth-system picker-api-key picker-app-id)

            (and auth-system (= "POST" method) (= "/ui/preview" path))
            (let [user (require-session-user! exchange auth-system)]
              (require-csrf! exchange auth-system user)
              (preview-ui! exchange frame-renderer))

            (and auth-system job-service (= "POST" method)
                 (= "/ui/jobs" path))
            (let [user (require-session-user! exchange auth-system)]
              (require-csrf! exchange auth-system user)
              (submit-job-ui! exchange job-service user))

            (and auth-system job-service (= "GET" method)
                 (re-matches #"/ui/jobs/[^/]+" path))
            (let [user (require-session-user! exchange auth-system)
                  job-id (last (.split path "/"))]
              (require-job-owner! job-service user job-id)
              (poll-job-ui! exchange job-service job-id))

            (and auth-system job-service (= "POST" method)
                 (re-matches #"/ui/jobs/[^/]+/cancel" path))
            (let [user (require-session-user! exchange auth-system)
                  job-id (nth (.split path "/") 3)]
              (require-csrf! exchange auth-system user)
              (require-job-owner! job-service user job-id)
              (respond! exchange 200 "text/html; charset=utf-8"
                        (ui/job-fragment (jobs/cancel-job! job-service job-id))))

            (and auth-system job-service (= "POST" method)
                 (re-matches #"/ui/jobs/[^/]+/retry" path))
            (let [user (require-session-user! exchange auth-system)
                  job-id (nth (.split path "/") 3)]
              (require-csrf! exchange auth-system user)
              (require-job-owner! job-service user job-id)
              (respond! exchange 202 "text/html; charset=utf-8"
                        (ui/job-fragment (jobs/retry-job! job-service job-id))))

            (and auth-system token-service (= "POST" method)
                 (= "/ui/tokens" path))
            (let [user (require-session-user! exchange auth-system)]
              (require-csrf! exchange auth-system user)
              (create-token-ui! exchange token-service user))

            (and auth-system token-service (= "GET" method)
                 (= "/ui/tokens" path))
            (list-tokens-ui! exchange token-service
                             (require-session-user! exchange auth-system))

            (and auth-system token-service (= "POST" method)
                 (re-matches #"/ui/tokens/[^/]+/revoke" path))
            (let [user (require-session-user! exchange auth-system)
                  token-id (nth (.split path "/") 3)]
              (require-csrf! exchange auth-system user)
              (revoke-token-ui! exchange token-service user token-id))

            (and auth-system admin-service (= "GET" method)
                 (= "/ui/admin/members" path))
            (members-ui! exchange admin-service
                         (require-session-user! exchange auth-system))

            (and auth-system admin-service log-store (= "GET" method)
                 (= "/ui/admin/logs" path))
            (logs-ui! exchange admin-service log-store auth-system)

            (and auth-system admin-service (= "POST" method)
                 (= "/ui/admin/members" path))
            (let [user (require-session-user! exchange auth-system)]
              (require-csrf! exchange auth-system user)
              (add-member-ui! exchange admin-service user))

            (and auth-system admin-service (= "POST" method)
                 (= "/ui/admin/members/revoke" path))
            (let [user (require-session-user! exchange auth-system)]
              (require-csrf! exchange auth-system user)
              (revoke-member-ui! exchange admin-service user))

            (and (= "POST" method) (= "/v1/preview" path))
            (do (->> (authenticated-user! exchange auth-system token-service)
                     (require-csrf! exchange auth-system))
                (preview! exchange frame-renderer))

            (and (= "POST" method) (= "/v1/overlay" path))
            (do (->> (authenticated-user! exchange auth-system token-service)
                     (require-csrf! exchange auth-system))
                (overlay! exchange frame-renderer video-encoder))

            (and auth-system token-service (= "POST" method)
                 (= "/v1/tokens" path))
            (let [user (require-session-user! exchange auth-system)]
              (require-csrf! exchange auth-system user)
              (create-personal-token! exchange token-service user))

            (and auth-system token-service (= "GET" method)
                 (= "/v1/tokens" path))
            (list-personal-tokens! exchange token-service
                                   (require-session-user! exchange auth-system))

            (and auth-system token-service (= "POST" method)
                 (re-matches #"/v1/tokens/[^/]+/revoke" path))
            (let [user (require-session-user! exchange auth-system)
                  token-id (nth (.split path "/") 3)]
              (require-csrf! exchange auth-system user)
              (revoke-personal-token! exchange token-service user token-id))

            (and auth-system admin-service (= "GET" method)
                 (= "/v1/admin/members" path))
            (list-members! exchange admin-service
                           (require-session-user! exchange auth-system))

            (and auth-system admin-service (= "POST" method)
                 (= "/v1/admin/members" path))
            (let [user (require-session-user! exchange auth-system)]
              (require-csrf! exchange auth-system user)
              (add-member! exchange admin-service user))

            (and auth-system admin-service (= "POST" method)
                 (= "/v1/admin/members/revoke" path))
            (let [user (require-session-user! exchange auth-system)]
              (require-csrf! exchange auth-system user)
              (revoke-member! exchange admin-service user))

            (and job-service (= "POST" method) (= "/v1/jobs" path))
            (let [user (authenticated-user! exchange auth-system token-service)]
              (require-csrf! exchange auth-system user)
              (submit-job! exchange job-service user))

            (and upload-signer (= "POST" method) (= "/v1/uploads" path))
            (do (->> (authenticated-user! exchange auth-system token-service)
                     (require-csrf! exchange auth-system))
                (issue-upload! exchange upload-signer))

            (and job-service (= "GET" method)
                 (re-matches #"/v1/jobs/[^/]+" path))
            (let [user (authenticated-user! exchange auth-system token-service)
                  job-id (last (.split path "/"))]
              (require-job-owner! job-service user job-id)
              (poll-job! exchange job-service (last (.split path "/"))))

            (and job-service (= "POST" method)
                 (re-matches #"/internal/v1/jobs/[^/]+/dispatch" path))
            (dispatch-job! exchange dependencies (nth (.split path "/") 4))

            (and job-service (= "POST" method)
                 (= "/internal/v1/jobs/reconcile" path))
            (reconcile-jobs! exchange dependencies)

            (and job-service (= "POST" method)
                 (re-matches #"/v1/jobs/[^/]+/cancel" path))
            (let [user (authenticated-user! exchange auth-system token-service)
                  job-id (nth (.split path "/") 3)]
              (require-csrf! exchange auth-system user)
              (require-job-owner! job-service user job-id)
              (cancel-job! exchange job-service job-id))

            (and job-service (= "POST" method)
                 (re-matches #"/v1/jobs/[^/]+/retry" path))
            (let [user (authenticated-user! exchange auth-system token-service)
                  job-id (nth (.split path "/") 3)]
              (require-csrf! exchange auth-system user)
              (require-job-owner! job-service user job-id)
              (retry-job! exchange job-service job-id))

            :else
            (respond! exchange 404 "application/json; charset=utf-8"
                      "{\"error\":\"not_found\"}")))
          (catch clojure.lang.ExceptionInfo error
            (let [failure (oauth-callback-failure (:type (ex-data error)))]
              (if failure
                (do
                  (log-oauth-callback-failure! dependencies request-id failure)
                  (respond-json! exchange (:status failure) (:body failure)))
                (case (:type (ex-data error))
              ::request-too-large
              (respond! exchange 413 "application/json; charset=utf-8"
                        "{\"error\":\"payload_too_large\"}")

              ::invalid-request
              (respond! exchange 400 "application/json; charset=utf-8"
                        "{\"error\":\"invalid_request\"}")

              ::invalid-upload-request
              (respond-json! exchange 400 {:error "invalid_upload_request"})

              ::auth/invalid-session
              (respond-json! exchange 401 {:error "authentication_required"})

              ::auth/not-allowlisted
              (respond-json! exchange 403 {:error "not_allowlisted"})

              ::auth/invalid-state
              (respond-json! exchange 400 {:error "invalid_oauth_state"})

              ::auth/invalid-csrf
              (respond-json! exchange 403 {:error "csrf_required"})

              ::auth/drive-grant-required
              (respond-json! exchange 401 {:error "drive_grant_required"})

              ::picker-not-configured
              (respond-json! exchange 503 {:error "picker_not_configured"})

              ::jobs/invalid-idempotency-key
              (respond-json! exchange 400 {:error "invalid_idempotency_key"})

              ::jobs/idempotency-conflict
              (respond-json! exchange 409 {:error "idempotency_conflict"})

              ::jobs/daily-submission-limit-exhausted
              (do
                (emit-event! dependencies "admission_rejected"
                             {:severity "WARNING"
                              :reason "daily_submission_limit_exhausted"})
                (respond-json! exchange 429
                               {:error "daily_submission_limit_exhausted"}))

              ::jobs/monthly-budget-exhausted
              (do
                (emit-event! dependencies "admission_rejected"
                             {:severity "WARNING"
                              :reason "monthly_budget_exhausted"})
                (respond-json! exchange 429
                               {:error "monthly_budget_exhausted"}))

              ::jobs/transaction-contention
              (do
                (emit-event! dependencies "job_failed"
                             {:severity "WARNING"
                              :reason "transaction_contention"
                              :failureCode "transaction_contention"})
                (.set (.getResponseHeaders exchange) "Retry-After" "1")
                (respond-json! exchange 503
                               {:error "transaction_contention"
                                :retryable true}))

              ::jobs/job-not-found
              (respond-json! exchange 404 {:error "job_not_found"})

              ::jobs/capacity-exhausted
              (do
                (emit-event! dependencies "admission_rejected"
                             {:severity "WARNING"
                              :reason "capacity_exhausted"})
                (respond-json! exchange 503 {:error "capacity_exhausted"}))

              ::jobs/launch-failed
              (do
                (emit-event! dependencies "job_failed"
                             {:severity "ERROR" :reason "launch_failed"})
                (respond-json! exchange 502 {:error "launch_failed"}))

              ::jobs/invalid-transition
              (respond-json! exchange 409 {:error "invalid_job_transition"})

              ::tokens/invalid-token
              (respond-json! exchange 401 {:error "authentication_required"})

              ::tokens/invalid-token-name
              (respond-json! exchange 400 {:error "invalid_token_name"})

              ::tokens/token-not-found
              (respond-json! exchange 404 {:error "token_not_found"})

              ::admin/admin-required
              (respond-json! exchange 403 {:error "admin_required"})

              ::admin/invalid-email
              (respond-json! exchange 400 {:error "invalid_member_email"})

              ::admin/member-not-found
              (respond-json! exchange 404 {:error "member_not_found"})

              ::admin/owner-cannot-be-revoked
              (respond-json! exchange 409 {:error "owner_cannot_be_revoked"})

              ::jobs/member-not-allowlisted
              (respond-json! exchange 403 {:error "not_allowlisted"})

              (do
                (emit-event! dependencies "request_failed"
                             {:severity "ERROR"
                              :reason "unexpected_application_error"
                              :errorType (some-> error ex-data :type str)})
                (respond! exchange 500 "application/json; charset=utf-8"
                          "{\"error\":\"render_failed\"}"))))))
          (catch Throwable error
            (emit-event! dependencies "request_failed"
                         {:severity "ERROR"
                          :reason "unexpected_error"
                          :errorType (some-> error ex-data :type str)})
            (respond! exchange 500 "application/json; charset=utf-8"
                      "{\"error\":\"render_failed\"}")))))))

(defn start!
  ([port]
   (start! port {}))
  ([port dependencies]
   (let [dependencies (merge {:frame-renderer frames/java2d-frame-renderer
                              :video-encoder (media/ffmpeg-video-encoder)}
                             dependencies)
         server (HttpServer/create (InetSocketAddress. (int port)) 0)]
     (.createContext server "/" (route-handler dependencies))
     (.start server)
     (reify java.lang.AutoCloseable
       (close [_]
         (.stop server 0))))))

(defn -main [& _]
  (let [port (parse-long (get (System/getenv) "PORT" "8080"))
        dependencies (if (= "true" (get (System/getenv)
                                        "AGG_JOB_LIFECYCLE_ENABLED"))
                       (gcp/api-system)
                       {})]
    (start! port dependencies)
    (observability/emit-event! "api" "server_started"
                               {:message "API server started"
                                :port port})))
