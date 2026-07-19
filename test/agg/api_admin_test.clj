(ns agg.api-admin-test
  (:require [agg.admin.core :as admin]
            [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.http-test-support :as test-http]
            [agg.logs.core :as logs]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- available-port []
  (test-http/available-port))

(defn- request! [port method path body headers]
  (test-http/send-string! method (str "http://127.0.0.1:" port path)
                          (when (some? body) (json/write-str body)) headers))

(defn- form-post! [port path body headers]
  (test-http/send-string! :post (str "http://127.0.0.1:" port path)
                          body headers))

(defn- auth-system [directory]
  (auth/system
   {:client-id "client-id"
    :client-secret "client-secret"
    :base-url "https://app.example.com"
    :member-directory directory
    :session-key (.getBytes "01234567890123456789012345678901")
    :oauth (reify auth/OAuthClient
             (exchange-code! [_ _ _ _ _]
               (throw (UnsupportedOperationException.))))}))

(deftest owners-and-admins-administer-members-through-session-and-csrf-protected-routes
  (let [port (available-port)
        {:keys [directory service]}
        (admin/in-memory-system {:owner-email "owner@example.com"
                                 :initial-emails #{"member@example.com"
                                                   "admin@example.com"}
                                 :admin-emails #{"admin@example.com"}})
        auth-system (auth-system directory)
        owner {:subject "owner-subject" :email "owner@example.com"}
        administrator {:subject "admin-subject" :email "admin@example.com"}
        member {:subject "member-subject" :email "member@example.com"}
        owner-cookie (str "agg_session=" (auth/issue-session auth-system owner))
        admin-cookie (str "agg_session=" (auth/issue-session auth-system administrator))
        member-cookie (str "agg_session=" (auth/issue-session auth-system member))
        owner-csrf (auth/issue-csrf-token auth-system owner)
        admin-csrf (auth/issue-csrf-token auth-system administrator)
        member-csrf (auth/issue-csrf-token auth-system member)
        server (api/start! port {:auth-system auth-system
                                 :admin-service service})]
    (try
      (testing "owners and admins see and read administration"
        (let [owner-page (request! port :get "/" nil {"Cookie" owner-cookie})
              admin-page (request! port :get "/" nil {"Cookie" admin-cookie})
              member-page (request! port :get "/" nil {"Cookie" member-cookie})
              owner-list (request! port :get "/v1/admin/members" nil
                                   {"Cookie" owner-cookie})
              admin-list (request! port :get "/v1/admin/members" nil
                                   {"Cookie" admin-cookie})
              member-list (request! port :get "/v1/admin/members" nil
                                    {"Cookie" member-cookie})]
          (is (str/includes? (.body owner-page) "Member administration"))
          (is (str/includes? (.body admin-page) "Member administration"))
          (is (not (str/includes? (.body member-page) "Member administration")))
          (is (= 200 (.statusCode owner-list)))
          (is (= 200 (.statusCode admin-list)))
          (is (= 403 (.statusCode member-list)))
          (is (= {"error" "admin_required"}
                 (json/read-str (.body member-list))))))
      (testing "member writes require an administrator's CSRF token"
        (let [missing-csrf (request! port :post "/v1/admin/members"
                                     {:email "new@example.com"}
                                     {"Content-Type" "application/json"
                                      "Cookie" owner-cookie})
              member-add (request! port :post "/v1/admin/members"
                                   {:email "new@example.com"}
                                   {"Content-Type" "application/json"
                                    "Cookie" member-cookie
                                    "X-CSRF-Token" member-csrf})
              admin-add (request! port :post "/v1/admin/members"
                                  {:email "admin-added@example.com"}
                                  {"Content-Type" "application/json"
                                   "Cookie" admin-cookie
                                   "X-CSRF-Token" admin-csrf})
              owner-add (request! port :post "/v1/admin/members"
                                  {:email "new@example.com"}
                                  {"Content-Type" "application/json"
                                   "Cookie" owner-cookie
                                   "X-CSRF-Token" owner-csrf})
              owner-revoke (request! port :post "/v1/admin/members/revoke"
                                     {:email "new@example.com"}
                                     {"Content-Type" "application/json"
                                      "Cookie" owner-cookie
                                      "X-CSRF-Token" owner-csrf})]
          (is (= 403 (.statusCode missing-csrf)))
          (is (= 403 (.statusCode member-add)))
          (is (= 201 (.statusCode admin-add)))
          (is (= 201 (.statusCode owner-add)))
          (is (= {"email" "new@example.com"
                  "role" "member"
                  "status" "active"}
                 (json/read-str (.body owner-add))))
          (is (= 200 (.statusCode owner-revoke)))
          (is (= "revoked"
                 (get (json/read-str (.body owner-revoke)) "status")))))
      (testing "the owner HTMX controls return an updated encoded member panel"
        (let [headers {"Content-Type" "application/x-www-form-urlencoded"
                       "Cookie" owner-cookie
                       "X-CSRF-Token" owner-csrf}
              added (form-post! port "/ui/admin/members"
                                "email=ui%2Bmember%40example.com" headers)
              revoked (form-post! port "/ui/admin/members/revoke"
                                  "email=ui%2Bmember%40example.com" headers)]
          (is (= 200 (.statusCode added)))
          (is (str/includes? (.body added) "ui+member@example.com"))
          (is (= 200 (.statusCode revoked)))
          (is (str/includes? (.body revoked) "revoked"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest administrators-can-view-formatted-and-raw-operational-logs
  (let [port (available-port)
        log-store (logs/in-memory-store)
        _ (logs/append-log! log-store
                            (logs/entry {:severity "ERROR"
                                         :component "api"
                                         :event "job_failed"
                                         :message "Render request failed"}
                                        "{\"severity\":\"ERROR\",\"component\":\"api\",\"event\":\"job_failed\"}"))
        _ (logs/append-log! log-store
                            (logs/entry {:severity "ERROR"
                                         :component "renderer"
                                         :event "wrong_component"}
                                        "{\"severity\":\"ERROR\",\"component\":\"renderer\",\"event\":\"wrong_component\"}"))
        _ (logs/append-log! log-store
                            (logs/entry {:severity "INFO"
                                         :component "api"
                                         :event "wrong_severity"}
                                        "{\"severity\":\"INFO\",\"component\":\"api\",\"event\":\"wrong_severity\"}"))
        {:keys [directory service]}
        (admin/in-memory-system {:owner-email "owner@example.com"
                                 :initial-emails #{"member@example.com"
                                                   "admin@example.com"}
                                 :admin-emails #{"admin@example.com"}})
        auth-system (auth-system directory)
        owner {:subject "owner-subject" :email "owner@example.com"}
        administrator {:subject "admin-subject" :email "admin@example.com"}
        member {:subject "member-subject" :email "member@example.com"}
        owner-cookie (str "agg_session=" (auth/issue-session auth-system owner))
        admin-cookie (str "agg_session=" (auth/issue-session auth-system administrator))
        member-cookie (str "agg_session=" (auth/issue-session auth-system member))
        server (api/start! port {:auth-system auth-system
                                 :admin-service service
                                 :log-store log-store})]
    (try
      (let [landing (request! port :get "/" nil {"Cookie" owner-cookie})
            formatted (request! port :get
                                "/ui/admin/logs?severity=ERROR&component=api"
                                nil {"Cookie" admin-cookie})
            raw (request! port :get
                          "/ui/admin/logs?view=raw&severity=ERROR&component=api"
                          nil {"Cookie" owner-cookie})
            member-response (request! port :get "/ui/admin/logs"
                                      nil {"Cookie" member-cookie})]
        (is (str/includes? (.body landing) "View operational logs"))
        (is (= 200 (.statusCode formatted)))
        (is (str/includes? (.body formatted) "Formatted events"))
        (is (str/includes? (.body formatted) "Job failed"))
        (is (str/includes? (.body formatted) "Render request failed"))
        (is (not (str/includes? (.body formatted) "Wrong component")))
        (is (not (str/includes? (.body formatted) "Wrong severity")))
        (is (= 200 (.statusCode raw)))
        (is (str/includes? (.body raw) "Raw JSON"))
        (is (str/includes? (.body raw) "&quot;event&quot;:&quot;job_failed&quot;"))
        (is (not (str/includes? (.body raw) "wrong_component")))
        (is (not (str/includes? (.body raw) "wrong_severity")))
        (is (= 403 (.statusCode member-response))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest admin-log-store-failures-are-safe-and-correlated
  (let [port (available-port)
        events (atom [])
        log-store (reify logs/LogStore
                    (append-log! [_ _]
                      (throw (UnsupportedOperationException.)))
                    (list-logs [_ _]
                      (throw (ex-info "Firestore query failed for owner@example.com"
                                      {:token "secret"
                                       :file-id "private-file"}))))
        {:keys [directory service]}
        (admin/in-memory-system {:owner-email "owner@example.com"})
        auth-system (auth-system directory)
        owner {:subject "owner-subject" :email "owner@example.com"}
        owner-cookie (str "agg_session=" (auth/issue-session auth-system owner))
        server (api/start! port {:auth-system auth-system
                                 :admin-service service
                                 :log-store log-store
                                 :event-sink (fn [event fields]
                                               (swap! events conj
                                                      (assoc fields :event event)))})]
    (try
      (let [response (request! port :get
                               "/ui/admin/logs?view=raw&severity=ERROR&component=api"
                               nil {"Cookie" owner-cookie})
            body (json/read-str (.body response) :key-fn keyword)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (= 503 (.statusCode response)))
        (is (= #{:error :category :requestId :retryable}
               (set (keys body))))
        (is (= "admin_logs_unavailable" (:error body)))
        (is (= "admin_logs_query" (:category body)))
        (is (= request-id (:requestId body)))
        (is (= true (:retryable body)))
        (is (re-matches #"[0-9a-f-]{36}" request-id))
        (is (= [{:event "request_failed"
                 :severity "ERROR"
                 :requestId request-id
                 :category "admin_logs_query"
                 :reason "log_store_query"
                 :status 503}]
               @events)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
