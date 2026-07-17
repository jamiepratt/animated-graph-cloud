(ns agg.api-admin-test
  (:require [agg.admin.core :as admin]
            [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.http-test-support :as test-http]
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

(deftest owner-administers-members-through-session-and-csrf-protected-routes
  (let [port (available-port)
        {:keys [directory service]}
        (admin/in-memory-system {:owner-email "owner@example.com"
                                 :initial-emails #{"member@example.com"}})
        auth-system (auth-system directory)
        owner {:subject "owner-subject" :email "owner@example.com"}
        member {:subject "member-subject" :email "member@example.com"}
        owner-cookie (str "agg_session=" (auth/issue-session auth-system owner))
        member-cookie (str "agg_session=" (auth/issue-session auth-system member))
        owner-csrf (auth/issue-csrf-token auth-system owner)
        member-csrf (auth/issue-csrf-token auth-system member)
        server (api/start! port {:auth-system auth-system
                                 :admin-service service})]
    (try
      (testing "only the owner sees and reads administration"
        (let [owner-page (request! port :get "/" nil {"Cookie" owner-cookie})
              member-page (request! port :get "/" nil {"Cookie" member-cookie})
              owner-list (request! port :get "/v1/admin/members" nil
                                   {"Cookie" owner-cookie})
              member-list (request! port :get "/v1/admin/members" nil
                                    {"Cookie" member-cookie})]
          (is (str/includes? (.body owner-page) "Member administration"))
          (is (not (str/includes? (.body member-page) "Member administration")))
          (is (= 200 (.statusCode owner-list)))
          (is (= 403 (.statusCode member-list)))
          (is (= {"error" "owner_required"}
                 (json/read-str (.body member-list))))))
      (testing "member writes require the owner's CSRF token"
        (let [missing-csrf (request! port :post "/v1/admin/members"
                                     {:email "new@example.com"}
                                     {"Content-Type" "application/json"
                                      "Cookie" owner-cookie})
              member-add (request! port :post "/v1/admin/members"
                                   {:email "new@example.com"}
                                   {"Content-Type" "application/json"
                                    "Cookie" member-cookie
                                    "X-CSRF-Token" member-csrf})
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
