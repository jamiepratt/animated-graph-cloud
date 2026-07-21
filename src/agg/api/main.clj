(ns agg.api.main
  (:require [agg.errors :as errors]
            [agg.admin.core :as admin]
            [agg.contracts.render :as contract]
            [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.jobs.gcp :as gcp]
            [agg.jobs.lifecycle :as jobs]
            [agg.logs.core :as logs]
            [agg.observability :as observability]
            [agg.preview.core :as preview]
            [agg.render.frames :as frames]
            [agg.render.media :as media]
            [agg.renderer.main :as renderer]
            [agg.tokens.core :as tokens]
            [agg.ui.core :as ui]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:gen-class)
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path)
           (java.time Instant)
           (java.util UUID)))

(def ^:private health-body "{\"status\":\"ok\"}")

(def ^:private service-profiles #{"api" "overlay"})

(def ^:private picker-selectable-mime-types
  "video/mp4,video/quicktime,video/webm,video/mpeg,video/ogg,video/x-msvideo,video/x-matroska")

(def ^:private picker-diagnostic-phases
  #{"opened" "loaded" "empty" "selected" "cancelled" "error"})

(def ^:private picker-diagnostic-views
  #{"drive" "upload" "unknown"})

(def ^:private picker-diagnostic-list-states
  #{"unknown" "empty" "selected"})

(def max-request-bytes contract/max-render-request-bytes)

(def ^:private max-synchronous-overlay-duration-seconds 1)

(def ^:private preview-stages
  #{"source_metadata"})

(def ^:private uuid-path-component
  "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

(def ^:private preview-api-path-pattern
  (re-pattern (str "/v1/previews/" uuid-path-component)))

(def ^:private preview-image-path-pattern
  (re-pattern (str "/v1/previews/" uuid-path-component
                   "/images/[A-Za-z0-9_-]{1,64}/(?:thumbnail|full)")))

(def ^:private preview-ui-path-pattern
  (re-pattern (str "/ui/previews/" uuid-path-component)))

(def ^:private public-assets
  {"/openapi.yaml" ["openapi.yaml" "application/yaml; charset=utf-8"]
   "/alpha-compose-mark.svg" ["public/alpha-compose-mark.svg" "image/svg+xml; charset=utf-8"]
   "/favicon.svg" ["public/favicon.svg" "image/svg+xml; charset=utf-8"]
   "/favicon-16.png" ["public/favicon-16.png" "image/png"]
   "/favicon-32.png" ["public/favicon-32.png" "image/png"]
   "/apple-touch-icon.png" ["public/apple-touch-icon.png" "image/png"]
   "/icon-192.png" ["public/icon-192.png" "image/png"]
   "/icon-512.png" ["public/icon-512.png" "image/png"]})

(defn- log-event! [event fields]
  (observability/emit-event! "api" event fields))

(defn- emit-event! [dependencies event fields]
  ((or (:event-sink dependencies) log-event!) event fields))

(defn- error-types [error]
  (loop [current error
         types #{}]
    (if current
      (recur (.getCause ^Throwable current)
             (if-let [type (:type (ex-data current))]
               (conj types type)
               types))
      types)))

(defn- preview-path? [path]
  (contains? #{"/v1/preview" "/ui/preview"} path))

(defn- telemetry-api-path? [path]
  (contains? #{"/v1/preview" "/v1/overlay" "/v1/jobs"} path))

(declare respond! respond-json!)

(defn- error-data [error]
  (loop [current error
         data []]
    (if current
      (recur (.getCause ^Throwable current)
             (conj data (ex-data current)))
      data)))

(defn- safe-failure-code? [value]
  (and (string? value)
       (<= 1 (count value) 128)
       (re-matches #"[A-Za-z0-9_.:/-]+" value)))

(defn- safe-source-line? [value]
  (and (integer? value)
       (<= 1 value 1000000000000)))

(defn- safe-preview-timing? [value]
  (and (integer? value)
       (<= 0 value 1000000000000)))

(defn- safe-preview-status? [value]
  (and (integer? value) (<= 400 value 599)))

(defn- public-telemetry-diagnostics
  [{:keys [failure-code field line expected-schema documentation-path]}]
  (cond-> {:failureCode failure-code
           :field field
           :documentationPath documentation-path}
    line (assoc :line line)
    expected-schema
    (assoc :expectedSchema
           {:timestampColumns (:timestamp-columns expected-schema)
            :valueColumns (:value-columns expected-schema)})))

(defn- public-request-field [error]
  (some (fn [entry]
          (let [field (:field entry)]
            (when (contains? #{"futureTraceOpacityPercent"} field)
              field)))
        (error-data error)))

(defn- preview-diagnostics [error]
  (let [data (error-data error)
        telemetry-failure (contract/telemetry-failure error)
        field (public-request-field error)
        failure-code (some (fn [entry]
                             (let [value (:failure-code entry)]
                               (when (safe-failure-code? value) value)))
                           data)
        line (some (fn [entry]
                     (let [value (:line entry)]
                       (when (safe-source-line? value) value)))
                   data)
        stage (some (fn [entry]
                      (let [value (:stage entry)]
                        (when (contains? preview-stages value) value)))
                    data)
        status (some (fn [entry]
                       (let [value (:status entry)]
                         (when (safe-preview-status? value) value)))
                     data)
        retryable (some (fn [entry]
                          (let [value (:retryable entry)]
                            (when (boolean? value) value)))
                        data)
        elapsed-ms (some (fn [entry]
                           (let [value (:elapsed-ms entry)]
                             (when (safe-preview-timing? value) value)))
                         data)
        timeout-ms (some (fn [entry]
                           (let [value (:timeout-ms entry)]
                             (when (safe-preview-timing? value) value)))
                         data)
        diagnostics (cond-> {}
                      field (assoc :field field)
                      failure-code (assoc :failureCode failure-code)
                      line (assoc :line line)
                      stage (assoc :stage stage)
                      status (assoc :status status)
                      (some? retryable) (assoc :retryable retryable)
                      elapsed-ms (assoc :elapsedMs elapsed-ms)
                      timeout-ms (assoc :timeoutMs timeout-ms))]
    (if telemetry-failure
      (merge diagnostics (public-telemetry-diagnostics telemetry-failure))
      diagnostics)))

(defn- preview-failure [error request-id]
  (let [types (error-types error)
        contract? (contains? types ::invalid-request)
        invalid-source? (contains? types ::contract/invalid-source-metadata)
        source-metadata? (or invalid-source?
                             (contains? types
                                        :agg.drive.gcp/source-metadata-failed))
        source-content? (contains? types
                                   :agg.drive.gcp/source-download-failed)
        category (cond
                   contract? "request_contract"
                   source-metadata? "drive_source_metadata"
                   source-content? "drive_source_content"
                   :else "preview_rendering")
        error-code (if contract? "invalid_request" "preview_failed")
        diagnostics (preview-diagnostics error)
        status (cond
                 contract? 400
                 invalid-source? 400
                 (safe-preview-status? (:status diagnostics))
                 (:status diagnostics)
                 :else 502)
        retryable (if (contains? diagnostics :retryable)
                    (:retryable diagnostics)
                    (not (or contract? invalid-source?)))]
    {:status status
     :category category
     :diagnostics diagnostics
     :retryable retryable
     :body (merge {:error error-code
                   :category category
                   :requestId request-id
                   :retryable retryable}
                  diagnostics)
     :log? true}))

(defn- respond-preview-failure! [dependencies exchange request-id error]
  (let [{:keys [status category diagnostics retryable body log?]}
        (preview-failure error request-id)]
    (when log?
      (emit-event! dependencies "request_failed"
                   (merge
                    {:severity (if (= 400 status) "WARNING" "ERROR")
                     :requestId request-id
                     :category category
                     :status status
                     :retryable retryable}
                    (select-keys diagnostics
                                 [:failureCode :field :line :stage :status
                                  :retryable :elapsedMs :timeoutMs]))))
    (if (= "/ui/preview" (some-> exchange .getRequestURI .getPath))
      (respond! exchange 200 "text/html; charset=utf-8"
                (ui/preview-failure-fragment
                 (merge {:category category
                         :request-id request-id
                         :status status
                         :retryable retryable}
                        diagnostics)))
      (respond-json! exchange status body))))

(defn- respond-admin-logs-failure! [dependencies exchange request-id]
  (emit-event! dependencies "request_failed"
               {:severity "ERROR"
                :requestId request-id
                :category "admin_logs_query"
                :reason "log_store_query"
                :status 503})
  (respond-json! exchange 503 {:error "admin_logs_unavailable"
                               :category "admin_logs_query"
                               :requestId request-id
                               :retryable true}))

(defn- oauth-callback-failure [type]
  (case type
    ::auth/drive-grant-required
    {:category "invalid_grant"
     :status 401
     :body {:error "drive_grant_required"
            :recoveryPath "/v1/auth/login/start?recovery=true"}}

    ::auth/revoked-grant
    {:category "invalid_grant"
     :status 401
     :body {:error "drive_grant_required"
            :recoveryPath "/v1/auth/login/start?recovery=true"}}

    ::auth/missing-refresh-token
    {:category "missing_refresh_token"
     :status 401
     :retry-path "/v1/auth/login/start?recovery=true"
     :body {:error "drive_grant_required"
            :recoveryPath "/v1/auth/login/start?recovery=true"}}

    ::auth/missing-required-scopes
    {:category "missing_required_scopes"
     :status 400
     :retry-path "/v1/auth/login/start?recovery=true"
     :body {:error "invalid_drive_scopes"}}

    ::auth/unexpected-scopes
    {:category "unexpected_scopes"
     :status 400
     :retry-path "/v1/auth/login/start?recovery=true"
     :body {:error "invalid_drive_scopes"}}

    ::auth/invalid-code
    {:category "invalid_code"
     :status 400
     :body {:error "invalid_oauth_code"}}

    ::auth/not-allowlisted
    {:category "not_allowlisted"
     :status 403
     :body {:error "not_allowlisted"}}

    ::auth/invalid-state
    {:category "invalid_state"
     :status 400
     :body {:error "invalid_oauth_state"}}

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

(def ^:private unexpected-oauth-callback-failure
  {:category "unexpected"
   :status 500
   :body {:error "oauth_callback_failed" :retryable true}})

(def ^:private safe-oauth-callback-reasons
  #{"access_denied" "deadline_exceeded" "drive_disabled"
    "failed_precondition" "internal" "permission_denied"
    "resource_exhausted" "service_disabled" "temporarily_unavailable"
    "unauthenticated" "unavailable" "workspace_restricted"})

(defn- safe-oauth-callback-reason [error]
  (let [reason (:reason (ex-data error))]
    (when (contains? safe-oauth-callback-reasons reason)
      reason)))

(defn- log-oauth-callback-failure! [dependencies request-id failure]
  (emit-event! dependencies "oauth_callback_failed"
               (cond-> {:severity (if (>= (:status failure) 500) "ERROR" "WARNING")
                        :requestId request-id
                        :category (:category failure)
                        :status (:status failure)}
                 (:reason failure) (assoc :reason (:reason failure)))))

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

(defn- respond-asset! [^HttpExchange exchange resource content-type]
  (if-let [url (io/resource resource)]
    (with-open [input (io/input-stream url)]
      (let [bytes (.readAllBytes input)]
        (doto (.getResponseHeaders exchange)
          (.set "Content-Type" content-type)
          (.set "Cache-Control" "public, max-age=86400, immutable")
          (.set "X-Content-Type-Options" "nosniff"))
        (.sendResponseHeaders exchange 200 (alength ^bytes bytes))
        (with-open [response-body (.getResponseBody exchange)]
          (.write response-body ^bytes bytes))))
    (respond! exchange 404 "application/json; charset=utf-8"
              "{\"error\":\"not_found\"}")))

(defn- respond-json! [exchange status body]
  (respond! exchange status "application/json; charset=utf-8"
            (json/write-str body)))

(defn- browser-html-request? [^HttpExchange exchange]
  (some-> exchange .getRequestHeaders (.getFirst "Accept")
          (.toLowerCase java.util.Locale/ROOT)
          (.contains "text/html")))

(defn- oauth-callback-error-page [{:keys [category reason retry-path]} request-id]
  (let [{:keys [title explanation next-step action-label no-effects]}
        (case category
          "missing_refresh_token"
          {:title "Google Drive authorization needs to be renewed"
           :explanation
           (str "Google did not return a refresh token, and Alpha Compose has "
                "no stored reusable grant for this account.")
           :next-step
           "Continue with Google and approve Drive access again."
           :action-label "Continue with Google"
           :no-effects "No session, Drive grant, or render was created."}
          "missing_required_scopes"
          {:title "Google Drive access could not be established"
           :explanation
           (str "Google did not return the restricted <code>drive.file</code> "
                "permission required to select inputs and deliver renders. "
                "Alpha Compose cannot tell from this response whether Drive is "
                "disabled, unavailable, denied, or restricted by a Workspace administrator.")
           :next-step
           "Check that Drive is available for the intended account, then try again and approve the requested access."}
          "unexpected_scopes"
          {:title "Google Drive access could not be established"
           :explanation
           (str "Google returned an additional permission outside Alpha Compose's "
                "restricted set, so the callback was rejected. Alpha Compose cannot "
                "tell whether it came from the account, consent history, browser, or "
                "Workspace policy.")
           :next-step
           "Try again with the intended account and approve only the access Alpha Compose requests."}
          "not_allowlisted"
          {:title "This Google account does not have Alpha Compose access"
           :explanation
           "The account was authenticated, but it is not on the administrator-managed access list."
           :next-step
           "Try again with an approved Google account, or ask an Alpha Compose administrator for access."}
          "drive"
          (cond
            (= "workspace_restricted" reason)
            {:title "Google Workspace restricted Drive access"
             :explanation
             "Google reported that a Workspace policy blocked the required Drive operation."
             :next-step
             "You can try again. If it continues, ask your Workspace administrator whether Alpha Compose is allowed."}

            (contains? #{"drive_disabled" "service_disabled"} reason)
            {:title "Google Drive is disabled for this account"
             :explanation
             "Google reported that the Drive service required by Alpha Compose is disabled."
             :next-step
             "Enable Drive or use an account where it is available, then try again."}

            (contains? #{"access_denied" "permission_denied"} reason)
            {:title "Google denied the required Drive operation"
             :explanation
             "Google reported a permission denial while Alpha Compose established restricted Drive access."
             :next-step
             "Try again with the intended account. If it continues, ask the Workspace administrator about Drive policy."}

            (contains? #{"temporarily_unavailable" "unavailable"} reason)
            {:title "Google Drive is temporarily unavailable"
             :explanation
             "Google reported that the required Drive operation is currently unavailable."
             :next-step "Wait briefly, then try again."}

            :else
            {:title "Google Drive access could not be established"
             :explanation
             "The Drive operation failed, but Google did not provide bounded evidence of the account or Workspace cause."
             :next-step
             "Try again. If it continues, check Drive for the intended account and contact the Workspace administrator."})
          {:title "Google authorization did not finish"
           :explanation
           "Alpha Compose could not safely establish the Google identity and Drive access required to continue."
           :next-step "Try again. If the problem continues, contact the Alpha Compose administrator."})
        retry-path (or retry-path "/v1/auth/login/start")
        action-label (or action-label "Try Google again")
        no-effects (or no-effects
                       "No session, Drive grant, membership binding, or render was created.")]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>Authorization error · Alpha Compose</title>"
         "<style>:root{font-family:Inter,ui-sans-serif,system-ui,sans-serif;color:#152238;background:#f5f7fb;line-height:1.5}"
         "*{box-sizing:border-box}body{margin:0}.shell{max-width:48rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
         ".brand{color:#152238;font-weight:800;text-decoration:none}.card{margin-top:3rem;background:#fff;border:1px solid #e1e7f0;border-radius:1.1rem;padding:clamp(1.25rem,4vw,2.5rem);box-shadow:0 1rem 3rem #243b5a0d}"
         ".eyebrow{color:#a13e3e;font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}"
         "h1{font-size:clamp(2rem,6vw,3.5rem);line-height:1.05;letter-spacing:-.04em;margin:.6rem 0 1rem}"
         ".muted{color:#5c6b82}.button{display:inline-block;margin-top:.75rem;border-radius:.65rem;padding:.75rem 1rem;background:#4374c5;color:#fff;font-weight:800;text-decoration:none}"
         "small{display:block;margin-top:1.5rem;color:#6c7a90}</style></head>"
         "<body><div class=\"shell\"><a class=\"brand\" href=\"/\">Alpha Compose</a>"
         "<main class=\"card\" role=\"alert\"><div class=\"eyebrow\">Authorization incomplete</div>"
         "<h1>" title "</h1><p>" explanation "</p><p class=\"muted\">"
         next-step " " no-effects "</p>"
         "<a class=\"button\" href=\"" retry-path "\">" action-label "</a>"
         "<small>Request ID: " request-id "</small></main></div></body></html>")))

(defn- respond-oauth-callback-failure! [exchange failure request-id]
  (if (browser-html-request? exchange)
    (respond! exchange (:status failure) "text/html; charset=utf-8"
              (oauth-callback-error-page failure request-id))
    (respond-json! exchange (:status failure) (:body failure))))

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

(declare session-cookie)

(defn- require-user! [exchange auth-system]
  (when auth-system
    (let [user (auth/session-user auth-system
                                  (session-token exchange auth-system))]
      (when-let [upgrade (:session-upgrade user)]
        (.add (.getResponseHeaders ^HttpExchange exchange)
              "Set-Cookie" (session-cookie upgrade)))
      (dissoc user :session-upgrade))))

(defn- browser-cookie-header [value]
  (str "__session=" value
       "; Max-Age=43200; Path=/; Secure; HttpOnly; SameSite=Lax"))

(defn- session-cookie [value]
  (str "__session=" value
       "; Max-Age=43200; Path=/; Secure; HttpOnly; SameSite=Lax"))

(defn- clear-session-cookie []
  "__session=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax")

(defn- clear-legacy-session-cookie []
  "agg_session=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax")

(def ^:private drive-recovery-path
  "/v1/auth/login/start?recovery=true")

(defn- clear-browser-session! [^HttpExchange exchange]
  (.add (.getResponseHeaders exchange) "Set-Cookie" (clear-session-cookie)))

(defn- set-drive-recovery-cookie! [^HttpExchange exchange auth-system]
  (let [recovery (auth/issue-drive-recovery-token auth-system)
        browser (auth/issue-browser-cookie auth-system {:oauth recovery})]
    (.add (.getResponseHeaders exchange) "Set-Cookie"
          (browser-cookie-header browser))))

(defn- respond-drive-recovery! [exchange path]
  (cond
    (= "/" path)
    (respond! exchange 401 "text/html; charset=utf-8" ui/drive-recovery-page)

    (.startsWith ^String path "/ui/")
    (respond! exchange 200 "text/html; charset=utf-8"
              ui/drive-recovery-fragment)

    (= "/v1/drive/picker" path)
    (respond! exchange 401 "text/html; charset=utf-8" ui/drive-recovery-page)

    :else
    (respond-json! exchange 401 {:error "drive_grant_required"
                                 :recoveryPath drive-recovery-path})))

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

(defn- attach-source!
  ([auth-system user prepared]
   (attach-source! auth-system user prepared nil))
  ([auth-system user {:keys [request render-spec]} drive-access]
   (if-not (:source-video render-spec)
     {:request request :render-spec render-spec}
     (do
       (when-not (and auth-system user)
         (throw (errors/raise! "Drive authorization is required"
                               {:type ::auth/drive-grant-required})))
       (let [{:keys [access-token]}
             (or drive-access
                 (auth/drive-access! auth-system (:subject user)))
             gateway (:drive auth-system)]
         (when-not (satisfies? drive/SourceGateway gateway)
           (throw (errors/raise! "Drive source dependencies are incomplete"
                                 {:type ::drive/source-unavailable})))
         (let [file-id (get-in render-spec [:source-video :file-id])
               metadata (drive/source-metadata! gateway access-token file-id)
               request (assoc request :sourceVideoServerMetadata metadata)]
           {:request request
            :render-spec (contract/attach-source-metadata render-spec metadata)
            :source-stream!
            (fn [output]
              (drive/stream-source! gateway access-token file-id output))}))))))

(defn- require-drive-access! [auth-system user]
  (when auth-system
    (when-not user
      (throw (errors/raise! "Drive authorization is required"
                            {:type ::auth/drive-grant-required})))
    (auth/drive-access! auth-system (:subject user))))

(defn- durable-request! [auth-system user prepared]
  (let [drive-access (require-drive-access! auth-system user)]
    (attach-source! auth-system user prepared drive-access)))

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

(defn- submit-preview-operation!
  [preview-job-service auth-system user prepared idempotency-key]
  (let [{:keys [request render-spec]} prepared
        _ (when (and (:source-video render-spec)
                     (not (and auth-system user)))
            (throw (errors/raise! "Drive authorization is required"
                                  {:type ::auth/drive-grant-required})))
        request (cond-> (assoc (dissoc request
                                       :previewOperation
                                       :sourceVideoServerMetadata
                                       :requesterSubject
                                       :requesterEmail
                                       :requesterMembershipVersion)
                               :previewOperation jobs/preview-operation-version)
                  user (assoc :requesterSubject (:subject user)
                              :requesterEmail (:email user)
                              :requesterMembershipVersion
                              (:membership-version user)))
        {:keys [job]}
        (jobs/submit-job! preview-job-service
                          (if (seq idempotency-key)
                            (str "preview-"
                                 (jobs/request-digest
                                  (str (:subject user) ":" idempotency-key)))
                            (str "preview-" (UUID/randomUUID)))
                          request)]
    (preview/operation-resource job)))

(defn- preview! [exchange preview-job-service auth-system user]
  (respond-json! exchange 202
                 (submit-preview-operation! preview-job-service auth-system user
                                            (request-render-request exchange)
                                            (some-> exchange .getRequestHeaders
                                                    (.getFirst "Idempotency-Key")))))

(defn- poll-preview! [exchange preview-job-service operation-id]
  (if-let [operation
           (some-> (jobs/get-job preview-job-service operation-id)
                   preview/operation-resource)]
    (respond-json! exchange 200 operation)
    (respond-json! exchange 404 {:error "preview_not_found"})))

(defn- respond-preview-image!
  [^HttpExchange exchange preview-job-service asset-store operation-id asset-id size]
  (let [operation (some-> (jobs/get-job preview-job-service operation-id)
                          preview/operation-resource)
        asset (when (= "succeeded" (:state operation))
                (preview/get-asset asset-store operation-id asset-id size))]
    (if-not asset
      (respond-json! exchange 404 {:error "preview_image_not_found"})
      (let [bytes (:bytes asset)]
        (doto (.getResponseHeaders exchange)
          (.set "Content-Type" "image/png")
          (.set "Cache-Control" "no-store")
          (.set "X-Content-Type-Options" "nosniff"))
        (.sendResponseHeaders exchange 200 (alength ^bytes bytes))
        (with-open [response (.getResponseBody exchange)]
          (.write response ^bytes bytes))))))

(defn- overlay! [exchange frame-renderer video-encoder]
  (let [{:keys [render-spec]} (request-render-request exchange)
        _ (when (:source-video render-spec)
            (throw (errors/raise!
                    "Video compositing is available only through durable jobs"
                    {:type ::compositing-not-supported})))
        _ (when (> (:duration-seconds render-spec)
                   max-synchronous-overlay-duration-seconds)
            (throw (errors/raise!
                    "Synchronous overlays are limited to the production-proven diagnostic duration"
                    {:type ::synchronous-overlay-duration-exceeded
                     :reported (:duration-seconds render-spec)})))
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

(defn- submit-job!
  [^HttpExchange exchange job-service auth-system user]
  (let [idempotency-key (some-> exchange .getRequestHeaders
                                (.getFirst "Idempotency-Key"))
        prepared (request-render-request exchange)
        {:keys [request]} (durable-request! auth-system user prepared)
        request (cond-> (dissoc request :previewOperation
                                :requesterSubject
                                :requesterEmail
                                :requesterMembershipVersion)
                  user (assoc :requesterSubject (:subject user)
                              :requesterEmail (:email user)
                              :requesterMembershipVersion
                              (:membership-version user)))
        {:keys [created? job]}
        (jobs/submit-job! job-service idempotency-key request)]
    (respond-json! exchange (if created? 202 200) job)))

(defn- poll-job! [exchange job]
  (respond-json! exchange 200 job))

(defn- require-job-owner! [job-service user job-id]
  (when (and user
             (not (jobs/owns-job? job-service job-id (:subject user))))
    (throw (errors/raise! "Job does not exist" {:type ::jobs/job-not-found}))))

(defn- require-durable-job! [job-service job-id]
  (let [job (jobs/get-job job-service job-id)]
    (when (or (nil? job) (= "preview" (:operationKind job)))
      (throw (errors/raise! "Job does not exist"
                            {:type ::jobs/job-not-found})))
    job))

(defn- bearer-token [^HttpExchange exchange]
  (some-> exchange .getRequestHeaders (.getFirst "Authorization")
          (#(when (and % (.startsWith ^String % "Bearer "))
              (subs % 7)))
          not-empty))

(defn- authenticated-user! [exchange auth-system token-service]
  (when auth-system
    (if (session-token exchange auth-system)
      (assoc (require-user! exchange auth-system) :auth-kind :session)
      (if-let [token (and token-service (bearer-token exchange))]
        (->> (tokens/authenticate token-service token)
             (auth/require-allowlisted! auth-system)
             (#(assoc % :auth-kind :token)))
        (throw (errors/raise! "Authentication is required"
                              {:type ::auth/invalid-session}))))))

(defn- authenticated-overlay-user! [exchange auth-system token-service]
  (when-not auth-system
    (throw (errors/raise! "Authentication is required"
                          {:type ::auth/invalid-session})))
  (authenticated-user! exchange auth-system token-service))

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

(defn- begin-auth! [exchange auth-system]
  (let [recovery-requested? (contains? #{"1" "true"}
                                       (get (query-params exchange) "recovery"))
        recovery? (and recovery-requested?
                       (auth/drive-recovery-token?
                        auth-system (oauth-state-token exchange auth-system)))
        started (auth/begin-flow! auth-system :login nil recovery?)
        cookie (auth/issue-browser-cookie
                auth-system
                {:oauth (:stateCookie started)})]
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

(defn- logout! [exchange auth-system]
  (let [user (require-session-user! exchange auth-system)]
    (auth/verify-csrf! auth-system user (get (request-form exchange) "csrf"))
    (respond-redirect! exchange "/" [(clear-session-cookie)
                                     (clear-legacy-session-cookie)])))

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

(defn- landing-picker-config [auth-system user picker-api-key picker-app-id csrf]
  (when (and user (not-empty picker-api-key) (not-empty picker-app-id))
    (try
      (let [{:keys [access-token]} (auth/drive-access! auth-system
                                                       (:subject user))]
        {:access-token access-token
         :api-key picker-api-key
         :app-id picker-app-id
         :csrf csrf})
      (catch clojure.lang.ExceptionInfo error
        (if (= ::auth/drive-grant-required (:type (ex-data error)))
          (throw error)
          nil))
      (catch Throwable _ nil))))

(defn- landing! [^HttpExchange exchange auth-system token-service admin-service
                 log-store picker-api-key picker-app-id]
  (let [user (when-let [_session (session-token exchange auth-system)]
               (require-user! exchange auth-system))
        csrf (when user (auth/issue-csrf-token auth-system user))
        picker-config (landing-picker-config auth-system user picker-api-key
                                             picker-app-id csrf)
        body
        (if user
          (ui/page {:user user
                    :csrf csrf
                    :picker-config picker-config
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
            (if picker-config
              "default-src 'none'; script-src 'unsafe-inline' https://cdn.jsdelivr.net https://apis.google.com https://www.gstatic.com; frame-src https://docs.google.com https://accounts.google.com; style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self' https://www.googleapis.com; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
              "default-src 'none'; script-src 'unsafe-inline' https://cdn.jsdelivr.net; style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")))
    (respond! exchange 200 "text/html; charset=utf-8" body)))

(defn- picker! [^HttpExchange exchange auth-system picker-api-key picker-app-id]
  (let [user (require-session-user! exchange auth-system)
        {:keys [access-token]} (auth/drive-access! auth-system (:subject user))]
    (when-not (and (not-empty picker-api-key) (not-empty picker-app-id))
      (throw (errors/raise! "Google Picker is not configured"
                            {:type ::picker-not-configured})))
    (let [token (json/write-str access-token)
          api-key (json/write-str picker-api-key)
          app-id (json/write-str picker-app-id)
          csrf (json/write-str (auth/issue-csrf-token auth-system user))
          mime-types (json/write-str picker-selectable-mime-types)
          html
          (str "<!doctype html><html><head><meta charset=\"utf-8\">"
               "<title>Select Drive input</title>"
               "<script src=\"https://apis.google.com/js/api.js\"></script>"
               "</head><body><p>Opening Google Drive Picker…</p>"
               "<p>Choose a video from Drive, or use the Picker's Upload tab.</p>"
               "<p>Selected: <output id=\"picker-selection\">None</output></p>"
               "<button id=\"report-empty\" type=\"button\">Report an empty Drive list</button>"
               "<script>"
               "const diagnosticPath='/v1/drive/picker/diagnostic';"
               "const diagnosticCsrf=" csrf ";"
               "const pickerMimeTypes=" mime-types ";"
               "const selection=document.getElementById('picker-selection');"
               "function reportDiagnostic(phase,view='unknown',listState='unknown'){"
               "fetch(diagnosticPath,{method:'POST',credentials:'same-origin',keepalive:true,"
               "headers:{'Content-Type':'application/json','X-CSRF-Token':diagnosticCsrf},"
               "body:JSON.stringify({phase,view,listState})}).catch(()=>{});}"
               "function pickerCallback(data){"
               "if(data.action===google.picker.Action.LOADED){reportDiagnostic('loaded','drive','unknown');}"
               "if(data.action===google.picker.Action.PICKED){"
               "const d=data.docs&&data.docs[0];const file=d?{id:d.id,name:d.name,mimeType:d.mimeType}:null;"
               "if(!file||!file.mimeType||!file.mimeType.startsWith('video/')){"
               "selection.textContent='Choose a video file';reportDiagnostic('error','drive','unknown');return;}"
               "selection.textContent=file.name||'Selected video';"
               "reportDiagnostic('selected','drive','selected');"
               "if(window.opener){window.opener.postMessage({type:'agg-picker',file},location.origin);}}"
               "if(data.action===google.picker.Action.CANCEL){reportDiagnostic('cancelled','drive','unknown');}}"
               "function openPicker(){gapi.load('picker',()=>{"
               "const view=new google.picker.DocsView().setIncludeFolders(false)"
               ".setSelectFolderEnabled(false);"
               "const upload=new google.picker.DocsUploadView().setIncludeFolders(false);"
               "new google.picker.PickerBuilder().addView(view).addView(upload)"
               ".setSelectableMimeTypes(pickerMimeTypes)"
               ".setOAuthToken(" token ").setDeveloperKey(" api-key ")"
               ".setAppId(" app-id ").setOrigin(location.origin)"
               ".setCallback(pickerCallback).build().setVisible(true);"
               "reportDiagnostic('opened','drive','unknown');});}"
               "document.getElementById('report-empty').addEventListener('click',()=>{"
               "reportDiagnostic('empty','drive','empty');});"
               "openPicker();</script></body></html>")]
      (doto (.getResponseHeaders exchange)
        (.set "Cache-Control" "no-store")
        (.set "Referrer-Policy" "no-referrer")
        (.set "Content-Security-Policy"
              "default-src 'none'; script-src 'unsafe-inline' https://apis.google.com https://www.gstatic.com; frame-src https://docs.google.com https://accounts.google.com; style-src 'unsafe-inline'; connect-src 'self' https://www.googleapis.com; base-uri 'none'; frame-ancestors 'none'"))
      (respond! exchange 200 "text/html; charset=utf-8" html))))

(defn- picker-diagnostic! [^HttpExchange exchange auth-system dependencies]
  (let [user (require-session-user! exchange auth-system)
        {:keys [phase view listState]} (request-json exchange)]
    (require-csrf! exchange auth-system user)
    (when-not (and (contains? picker-diagnostic-phases phase)
                   (contains? picker-diagnostic-views view)
                   (contains? picker-diagnostic-list-states listState))
      (throw (errors/raise! "Invalid Picker diagnostic"
                            {:type ::invalid-picker-diagnostic})))
    (let [{:keys [token-status account-status index-status]}
          (try
            (let [{:keys [access-token]} (auth/drive-access!
                                          auth-system (:subject user))
                  gateway (:drive auth-system)]
              (if (satisfies? drive/PickerDiagnostics gateway)
                (merge {:token-status "refreshed"
                        :account-status "grant-bound"}
                       (drive/picker-diagnostics! gateway access-token))
                {:token-status "refreshed"
                 :account-status "grant-bound"
                 :index-status "not-probed"}))
            (catch clojure.lang.ExceptionInfo error
              (if (= ::auth/drive-grant-required (:type (ex-data error)))
                (throw error)
                {:token-status "refresh-failed"
                 :account-status "unavailable"
                 :index-status "not-probed"}))
            (catch Throwable _
              {:token-status "refresh-failed"
               :account-status "unavailable"
               :index-status "not-probed"}))]
      (emit-event! dependencies "picker_diagnostic"
                   {:severity (if (= "empty" phase) "WARNING" "INFO")
                    :phase phase
                    :view view
                    :listState listState
                    :tokenStatus token-status
                    :accountStatus account-status
                    :mimeFilter "selectable-video-mime-types"
                    :indexStatus index-status})
      (respond-json! exchange 200 {:accepted true}))))

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

(defn- preview-generation [value]
  (if (and (string? value)
           (<= 1 (count value) 80)
           (re-matches #"[A-Za-z0-9._-]+" value))
    value
    (str (UUID/randomUUID))))

(defn- preview-ui!
  [^HttpExchange exchange preview-job-service auth-system user]
  (let [generation (preview-generation
                    (some-> exchange .getRequestHeaders
                            (.getFirst "X-Preview-Generation")))
        operation (submit-preview-operation!
                   preview-job-service auth-system user
                   (ui-render-request exchange) generation)]
    (.set (.getResponseHeaders exchange) "X-Preview-Generation" generation)
    (respond! exchange 202 "text/html; charset=utf-8"
              (ui/preview-operation-fragment operation generation))))

(defn- poll-preview-ui!
  [^HttpExchange exchange preview-job-service operation-id]
  (let [generation (preview-generation (get (query-params exchange) "generation"))
        operation (some-> (jobs/get-job preview-job-service operation-id)
                          preview/operation-resource)]
    (.set (.getResponseHeaders exchange) "X-Preview-Generation" generation)
    (respond! exchange 200 "text/html; charset=utf-8"
              (if operation
                (ui/preview-operation-fragment operation generation)
                (ui/preview-stale-fragment generation)))))

(defn- submit-job-ui!
  [exchange job-service auth-system user]
  (let [form (request-form exchange)
        prepared (try
                   (let [request (json/read-str (get form "request")
                                                :key-fn keyword)]
                     {:request request :render-spec (contract/prepare request)})
                   (catch clojure.lang.ExceptionInfo error
                     (throw (errors/raise! "Invalid render request"
                                           {:type ::invalid-request}
                                           error)))
                   (catch Throwable error
                     (throw (errors/raise! "Invalid render request"
                                           {:type ::invalid-request}
                                           error))))
        {:keys [request]} (durable-request! auth-system user prepared)
        request (assoc (dissoc request
                               :previewOperation
                               :requesterSubject
                               :requesterEmail
                               :requesterMembershipVersion)
                       :requesterSubject (:subject user)
                       :requesterEmail (:email user)
                       :requesterMembershipVersion
                       (:membership-version user))
        idempotency-key
        (or (some-> exchange .getRequestHeaders (.getFirst "Idempotency-Key"))
            (str "ui-" (UUID/randomUUID)))
        {:keys [created? job]}
        (jobs/submit-job! job-service idempotency-key request)]
    (respond! exchange (if created? 202 200) "text/html; charset=utf-8"
              (ui/job-fragment job))))

(defn- poll-job-ui! [exchange job]
  (respond! exchange 200 "text/html; charset=utf-8" (ui/job-fragment job)))

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
        entries (try
                  (mapv logs/public-entry
                        (logs/list-logs log-store options))
                  (catch Throwable error
                    (errors/raise! "Operational log query failed"
                                   {:type ::log-store-query-failed
                                    :status 503
                                    :retryable true
                                    :reason "log_store_query"}
                                   error)))]
    (doto (.getResponseHeaders exchange)
      (.set "Referrer-Policy" "no-referrer")
      (.set "Content-Security-Policy"
            "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'"))
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
                              preview-job-service preview-asset-store
                              upload-signer auth-system picker-api-key
                              picker-app-id token-service admin-service log-store
                              service-profile]
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

                                  (and (= "overlay" service-profile)
                                       (= "POST" method)
                                       (= "/v1/overlay" path))
                                  (do (->> (authenticated-overlay-user!
                                            exchange auth-system token-service)
                                           (require-csrf! exchange auth-system))
                                      (overlay! exchange frame-renderer video-encoder))

                                  (= "overlay" service-profile)
                                  (respond! exchange 404 "application/json; charset=utf-8"
                                            "{\"error\":\"not_found\"}")

                                  (and (= "GET" method) (contains? public-assets path))
                                  (let [[resource content-type] (get public-assets path)]
                                    (respond-asset! exchange resource content-type))

                                  (and auth-system (= "GET" method) (= "/" path))
                                  (landing! exchange auth-system token-service admin-service
                                            log-store picker-api-key picker-app-id)

                                  (and (= "GET" method) (= "/privacy" path))
                                  (respond! exchange 200 "text/html; charset=utf-8" ui/privacy-page)

                                  (and (= "GET" method) (= "/terms" path))
                                  (respond! exchange 200 "text/html; charset=utf-8" ui/terms-page)

                                  (and auth-system (= "GET" method)
                                       (= "/v1/auth/login/start" path))
                                  (begin-auth! exchange auth-system)

                                  (and auth-system (= "GET" method)
                                       (= "/v1/auth/login/callback" path))
                                  (finish-login! exchange auth-system)

                                  (and auth-system (= "POST" method)
                                       (= "/v1/auth/logout" path))
                                  (logout! exchange auth-system)

                                  (and auth-system (= "GET" method)
                                       (= "/v1/drive/picker" path))
                                  (picker! exchange auth-system picker-api-key picker-app-id)

                                  (and auth-system (= "POST" method)
                                       (= "/v1/drive/picker/diagnostic" path))
                                  (picker-diagnostic! exchange auth-system dependencies)

                                  (and auth-system (= "POST" method) (= "/ui/preview" path))
                                  (let [user (require-session-user! exchange auth-system)]
                                    (require-csrf! exchange auth-system user)
                                    (preview-ui! exchange preview-job-service
                                                 auth-system user))

                                  (and auth-system preview-job-service
                                       (= "GET" method)
                                       (re-matches preview-ui-path-pattern path))
                                  (let [user (require-session-user! exchange auth-system)
                                        operation-id (last (.split path "/"))]
                                    (require-job-owner! preview-job-service user
                                                        operation-id)
                                    (poll-preview-ui! exchange preview-job-service
                                                      operation-id))

                                  (and auth-system job-service (= "POST" method)
                                       (= "/ui/jobs" path))
                                  (let [user (require-session-user! exchange auth-system)]
                                    (require-csrf! exchange auth-system user)
                                    (submit-job-ui! exchange job-service
                                                    auth-system user))

                                  (and auth-system job-service (= "GET" method)
                                       (re-matches #"/ui/jobs/[^/]+" path))
                                  (let [user (require-session-user! exchange auth-system)
                                        job-id (last (.split path "/"))]
                                    (require-job-owner! job-service user job-id)
                                    (poll-job-ui! exchange
                                                  (require-durable-job!
                                                   job-service job-id)))

                                  (and auth-system job-service (= "POST" method)
                                       (re-matches #"/ui/jobs/[^/]+/cancel" path))
                                  (let [user (require-session-user! exchange auth-system)
                                        job-id (nth (.split path "/") 3)]
                                    (require-csrf! exchange auth-system user)
                                    (require-job-owner! job-service user job-id)
                                    (require-durable-job! job-service job-id)
                                    (respond! exchange 200 "text/html; charset=utf-8"
                                              (ui/job-fragment (jobs/cancel-job! job-service job-id))))

                                  (and auth-system job-service (= "POST" method)
                                       (re-matches #"/ui/jobs/[^/]+/retry" path))
                                  (let [user (require-session-user! exchange auth-system)
                                        job-id (nth (.split path "/") 3)]
                                    (require-csrf! exchange auth-system user)
                                    (require-job-owner! job-service user job-id)
                                    (require-durable-job! job-service job-id)
                                    (require-drive-access! auth-system user)
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
                                  (let [user (authenticated-user! exchange auth-system token-service)]
                                    (require-csrf! exchange auth-system user)
                                    (preview! exchange preview-job-service
                                              auth-system user))

                                  (and preview-job-service (= "GET" method)
                                       (re-matches preview-api-path-pattern path))
                                  (let [user (authenticated-user! exchange auth-system token-service)
                                        operation-id (last (.split path "/"))]
                                    (require-job-owner! preview-job-service user operation-id)
                                    (poll-preview! exchange preview-job-service operation-id))

                                  (and preview-job-service preview-asset-store
                                       (= "GET" method)
                                       (re-matches preview-image-path-pattern path))
                                  (let [user (authenticated-user! exchange auth-system token-service)
                                        parts (.split path "/")
                                        operation-id (nth parts 3)
                                        asset-id (nth parts 5)
                                        size (keyword (nth parts 6))]
                                    (require-job-owner! preview-job-service user operation-id)
                                    (respond-preview-image!
                                     exchange preview-job-service preview-asset-store
                                     operation-id asset-id size))

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
                                    (submit-job! exchange job-service
                                                 auth-system user))

                                  (and upload-signer (= "POST" method) (= "/v1/uploads" path))
                                  (do (->> (authenticated-user! exchange auth-system token-service)
                                           (require-csrf! exchange auth-system))
                                      (issue-upload! exchange upload-signer))

                                  (and job-service (= "GET" method)
                                       (re-matches #"/v1/jobs/[^/]+" path))
                                  (let [user (authenticated-user! exchange auth-system token-service)
                                        job-id (last (.split path "/"))]
                                    (require-job-owner! job-service user job-id)
                                    (poll-job! exchange
                                               (require-durable-job!
                                                job-service job-id)))

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
                                    (require-durable-job! job-service job-id)
                                    (cancel-job! exchange job-service job-id))

                                  (and job-service (= "POST" method)
                                       (re-matches #"/v1/jobs/[^/]+/retry" path))
                                  (let [user (authenticated-user! exchange auth-system token-service)
                                        job-id (nth (.split path "/") 3)]
                                    (require-csrf! exchange auth-system user)
                                    (require-job-owner! job-service user job-id)
                                    (require-durable-job! job-service job-id)
                                    (require-drive-access! auth-system user)
                                    (retry-job! exchange job-service job-id))

                                  :else
                                  (respond! exchange 404 "application/json; charset=utf-8"
                                            "{\"error\":\"not_found\"}")))
          (catch clojure.lang.ExceptionInfo error
            (let [error-type (:type (ex-data error))
                  callback? (= "/v1/auth/login/callback" path)
                  recovery-error? (contains? #{::auth/drive-grant-required
                                               ::auth/revoked-grant
                                               ::auth/missing-refresh-token
                                               ::auth/missing-required-scopes
                                               ::auth/unexpected-scopes}
                                             error-type)
                  _ (when callback?
                      (if recovery-error?
                        (set-drive-recovery-cookie! exchange auth-system)
                        (clear-browser-session! exchange)))
                  callback-reason (when callback?
                                    (safe-oauth-callback-reason error))
                  failure (when callback?
                            (cond-> (or (oauth-callback-failure error-type)
                                        unexpected-oauth-callback-failure)
                              callback-reason
                              (assoc :reason callback-reason)))]
              (if failure
                (do
                  (log-oauth-callback-failure! dependencies request-id failure)
                  (respond-oauth-callback-failure! exchange failure request-id))
                (case (:type (ex-data error))
                  ::request-too-large
                  (if (= "/ui/preview" path)
                    (respond! exchange 200 "text/html; charset=utf-8"
                              (ui/preview-failure-fragment
                               {:category "request_contract"
                                :request-id request-id}))
                    (respond! exchange 413 "application/json; charset=utf-8"
                              "{\"error\":\"payload_too_large\"}"))

                  ::invalid-request
                  (if (or (preview-path? path)
                          (and (telemetry-api-path? path)
                               (contract/telemetry-failure error)))
                    (respond-preview-failure! dependencies exchange request-id error)
                    (if-let [field (public-request-field error)]
                      (respond-json! exchange 400
                                     {:error "invalid_request" :field field})
                      (respond! exchange 400 "application/json; charset=utf-8"
                                "{\"error\":\"invalid_request\"}")))

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
                  (do
                    (when (session-token exchange auth-system)
                      (set-drive-recovery-cookie! exchange auth-system))
                    (respond-drive-recovery! exchange path))

                  ::picker-not-configured
                  (respond-json! exchange 503 {:error "picker_not_configured"})

                  ::invalid-picker-diagnostic
                  (respond-json! exchange 400 {:error "invalid_picker_diagnostic"})

                  ::compositing-not-supported
                  (respond-json! exchange 400 {:error "compositing_requires_durable_job"})

                  ::synchronous-overlay-duration-exceeded
                  (let [duration-seconds (:reported (ex-data error))]
                    (emit-event! dependencies "admission_rejected"
                                 {:severity "WARNING"
                                  :requestId request-id
                                  :reason "synchronous_overlay_duration_exceeded"
                                  :durationSeconds duration-seconds
                                  :maxDurationSeconds max-synchronous-overlay-duration-seconds})
                    (respond-json!
                     exchange 422
                     {:error "synchronous_overlay_duration_exceeded"
                      :requestId request-id
                      :maxDurationSeconds max-synchronous-overlay-duration-seconds
                      :durableJobsPath "/v1/jobs"}))

                  ::contract/source-too-large
                  (respond-json! exchange 413 {:error "source_video_too_large"
                                               :limit contract/max-source-bytes})

                  ::contract/invalid-source-metadata
                  (if (preview-path? path)
                    (respond-preview-failure! dependencies exchange request-id error)
                    (respond-json! exchange 400 {:error "invalid_source_video"}))

                  ::drive/source-unavailable
                  (if (preview-path? path)
                    (respond-preview-failure! dependencies exchange request-id error)
                    (respond-json! exchange 503 {:error "drive_source_unavailable"
                                                 :retryable true}))

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

                  ::log-store-query-failed
                  (respond-admin-logs-failure! dependencies exchange request-id)

                  ::jobs/member-not-allowlisted
                  (respond-json! exchange 403 {:error "not_allowlisted"})

                  (if (preview-path? path)
                    (respond-preview-failure! dependencies exchange request-id error)
                    (do
                      (emit-event! dependencies "request_failed"
                                   {:severity "ERROR"
                                    :reason "unexpected_application_error"
                                    :errorType (some-> error ex-data :type str)})
                      (respond! exchange 500 "application/json; charset=utf-8"
                                "{\"error\":\"render_failed\"}")))))))
          (catch Throwable error
            (if (= "/v1/auth/login/callback" path)
              (do
                (clear-browser-session! exchange)
                (log-oauth-callback-failure!
                 dependencies request-id unexpected-oauth-callback-failure)
                (respond-oauth-callback-failure!
                 exchange unexpected-oauth-callback-failure request-id))
              (if (preview-path? path)
                (respond-preview-failure! dependencies exchange request-id error)
                (do
                  (emit-event! dependencies "request_failed"
                               {:severity "ERROR"
                                :reason "unexpected_error"
                                :errorType (some-> error ex-data :type str)})
                  (respond! exchange 500 "application/json; charset=utf-8"
                            "{\"error\":\"render_failed\"}"))))))))))

(defn start!
  ([port]
   (start! port {}))
  ([port dependencies]
   (let [local-preview-system (jobs/in-memory-system)
         preview-job-service (or (:preview-job-service dependencies)
                                 (:job-service dependencies)
                                 (:service local-preview-system))
         dependencies (merge {:frame-renderer frames/java2d-frame-renderer
                              :video-encoder (media/ffmpeg-video-encoder)
                              :preview-job-service preview-job-service
                              :preview-asset-store (preview/in-memory-asset-store)
                              :service-profile "api"}
                             dependencies
                             {:preview-job-service preview-job-service})
         _ (when-not (contains? service-profiles (:service-profile dependencies))
             (throw (IllegalArgumentException.
                     "service-profile must be api or overlay")))
         server (HttpServer/create (InetSocketAddress. (int port)) 0)]
     (.createContext server "/" (route-handler dependencies))
     (.start server)
     (reify java.lang.AutoCloseable
       (close [_]
         (.stop server 0))))))

(defn -main [& _]
  (let [port (parse-long (get (System/getenv) "PORT" "8080"))
        service-profile (get (System/getenv) "AGG_SERVICE_PROFILE" "api")
        dependencies (if (= "true" (get (System/getenv)
                                        "AGG_JOB_LIFECYCLE_ENABLED"))
                       (gcp/api-system)
                       {})]
    (start! port (assoc dependencies :service-profile service-profile))
    (observability/emit-event! "api" "server_started"
                               {:message "API server started"
                                :port port})))
