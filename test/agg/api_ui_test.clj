(ns agg.api-ui-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.http-test-support :as test-http]
            [agg.jobs-test :as fixture]
            [agg.jobs.lifecycle :as jobs]
            [agg.tokens.core :as tokens]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(defn- available-port []
  (test-http/available-port))

(defn- request! [port method path body headers]
  (test-http/send-string! method (str "http://127.0.0.1:" port path)
                          (when (= :post method) (or body "")) headers))

(defn- form [fields]
  (->> fields
       (map (fn [[name value]]
              (str (URLEncoder/encode (clojure.core/name name)
                                      StandardCharsets/UTF_8)
                   "="
                   (URLEncoder/encode (str value) StandardCharsets/UTF_8))))
       (str/join "&")))

(defn- fixture []
  (let [oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  (throw (UnsupportedOperationException.))))
        auth-system (auth/system
                     {:client-id "client-id"
                      :client-secret "client-secret"
                      :base-url "https://app.example.com"
                      :allowlist #{"owner@example.com" "member@example.com"}
                      :session-key (.getBytes "01234567890123456789012345678901")
                      :oauth oauth})
        owner {:subject "owner-subject" :email "owner@example.com"}
        member {:subject "member-subject" :email "member@example.com"}]
    {:auth-system auth-system
     :owner owner
     :owner-cookie (str "agg_session=" (auth/issue-session auth-system owner))
     :owner-csrf (auth/issue-csrf-token auth-system owner)
     :member-cookie (str "agg_session=" (auth/issue-session auth-system member))}))

(def form-content-type
  {"Content-Type" "application/x-www-form-urlencoded"})

(deftest public-product-and-legal-pages-identify-alpha-compose
  (let [port (available-port)
        {:keys [auth-system]} (fixture)
        server (api/start! port {:auth-system auth-system})]
    (try
      (let [homepage (request! port :get "/" nil {})
            privacy (request! port :get "/privacy" nil {})
            terms (request! port :get "/terms" nil {})]
        (is (= 200 (.statusCode homepage)))
        (is (str/includes? (.body homepage) "Alpha Compose"))
        (is (str/includes? (.body homepage) "class=\"shell\""))
        (is (str/includes? (.body homepage) "class=\"hero\""))
        (is (str/includes? (.body homepage) "class=\"hero-card\""))
        (is (str/includes? (.body homepage) "class=\"feature-grid\""))
        (is (str/includes? (.body homepage) "class=\"card trust-card\""))
        (is (str/includes? (.body homepage) "Sign in with Google"))
        (is (str/includes? (.body homepage) "href=\"/privacy\""))
        (is (str/includes? (.body homepage) "href=\"/terms\""))
        (is (str/includes? (.body homepage) "Google account information"))
        (is (str/includes? (.body homepage) "approved users"))
        (is (str/includes? (.body homepage) "drive.file"))
        (is (str/includes? (.body homepage) "files you select"))
        (is (str/includes? (.body homepage) "output delivery"))
        (is (= 200 (.statusCode privacy)))
        (is (str/includes? (.body privacy) "Privacy policy"))
        (is (str/includes? (.body privacy) "Google Drive"))
        (is (str/includes? (.body privacy) "Google API Services User Data Policy"))
        (is (str/includes? (.body privacy) "Limited Use"))
        (is (= 200 (.statusCode terms)))
        (is (str/includes? (.body terms) "Terms of service"))
        (is (str/includes? (.body terms) "me@jamiep.org")))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest htmx-preview-failure-is-a-safe-correlated-html-fragment
  (let [port (available-port)
        {:keys [auth-system owner-cookie owner-csrf]} (fixture)
        server (api/start! port {:auth-system auth-system})
        headers (merge form-content-type
                       {"Cookie" owner-cookie "X-CSRF-Token" owner-csrf})
        request (assoc (fixture/render-request)
                       :telemetry
                       (str "timestamp,heart_rate\n"
                            "2026-07-17T10:00:00Z,19\n"))]
    (try
      (let [response (request! port :post "/ui/preview"
                               (form {:request (json/write-str request)})
                               headers)
            body (.body response)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (= 200 (.statusCode response)))
        (is (= "text/html; charset=utf-8"
               (.orElse (.firstValue (.headers response) "content-type") nil)))
        (is (re-matches #"[0-9a-f-]{36}" request-id))
        (is (str/includes? body "<article id=\"preview-result\""))
        (is (str/includes? body "Preview failed"))
        (is (str/includes? body "request_contract"))
        (is (str/includes? body request-id))
        (is (str/includes? body "Source line"))
        (is (str/includes? body ">2</dd>"))
        (is (not (str/includes? body "{\"error\":\"invalid_request\"}")))
        (is (not (str/includes? body "heart_rate")))
        (is (not (str/includes? body "2026-07-17"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest site-icon-assets-are-served-and-linked
  (let [port (available-port)
        {:keys [auth-system]} (fixture)
        server (api/start! port {:auth-system auth-system})]
    (try
      (let [homepage (request! port :get "/" nil {})
            svg (test-http/send-string! :get
                                        (str "http://127.0.0.1:" port "/favicon.svg")
                                        nil
                                        {})
            png (test-http/send-bytes! :get
                                       (str "http://127.0.0.1:" port "/favicon-32.png")
                                       nil
                                       {})]
        (is (= 200 (.statusCode homepage)))
        (is (str/includes? (.body homepage) "href=\"/favicon.svg\""))
        (is (str/includes? (.body homepage) "href=\"/apple-touch-icon.png\""))
        (is (str/includes? (.orElse (.firstValue (.headers homepage)
                                                 "Content-Security-Policy")
                                    nil)
                           "img-src 'self' data:"))
        (is (= 200 (.statusCode svg)))
        (is (= "image/svg+xml; charset=utf-8"
               (.orElse (.firstValue (.headers svg) "Content-Type") nil)))
        (is (= "public, max-age=86400, immutable"
               (.orElse (.firstValue (.headers svg) "Cache-Control") nil)))
        (is (str/includes? (.body svg) "#4374C5"))
        (is (= 200 (.statusCode png)))
        (is (= "image/png"
               (.orElse (.firstValue (.headers png) "Content-Type") nil)))
        (is (= [-119 80 78 71 13 10 26 10]
               (mapv int (take 8 (.body png))))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest openapi-contract-is-served-as-a-public-read-only-asset
  (let [port (available-port)
        server (api/start! port)]
    (try
      (let [response (test-http/send-string! :get
                                             (str "http://127.0.0.1:" port
                                                  "/openapi.yaml")
                                             nil
                                             {})
            body (.body response)]
        (is (= 200 (.statusCode response)))
        (is (= "application/yaml; charset=utf-8"
               (.orElse (.firstValue (.headers response) "Content-Type") nil)))
        (is (= "public, max-age=86400, immutable"
               (.orElse (.firstValue (.headers response) "Cache-Control") nil)))
        (is (= (slurp "docs/openapi.yaml") body))
        (is (str/includes? body "openapi: 3.1.0"))
        (is (not (str/includes? body "client_secret"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest htmx-owner-workflow-previews-submits-polls-cancels-and-retries
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        token-system (tokens/in-memory-system
                      {:pepper (.getBytes "abcdefghijklmnopqrstuvwxyz012345")})
        {:keys [auth-system owner-cookie owner-csrf member-cookie]} (fixture)
        server (api/start! port {:job-service (:service lifecycle)
                                 :auth-system auth-system
                                 :token-service (:service token-system)})
        headers (merge form-content-type
                       {"Cookie" owner-cookie "X-CSRF-Token" owner-csrf})
        request-json (json/write-str (fixture/render-request))]
    (try
      (let [landing (request! port :get "/" nil {"Cookie" owner-cookie})]
        (is (= 200 (.statusCode landing)))
        (is (str/includes? (.body landing) "htmx.org@2.0.10"))
        (is (str/includes? (.body landing) "hx-post=\"/ui/preview\""))
        (is (str/includes? (.body landing) "hx-post=\"/ui/jobs\""))
        (is (str/includes? (.body landing) "id=\"telemetry-format\""))
        (is (str/includes? (.body landing) "type=\"datetime-local\""))
        (is (str/includes? (.body landing) "type=\"file\""))
        (is (str/includes? (.body landing) "Apply JSON to form"))
        (is (str/includes? (.body landing) "Accepted fields"))
        (is (str/includes? (.body landing) "sourceVideo"))
        (is (str/includes? (.body landing) "validateRequest"))
        (is (str/includes? (.body landing) "contains unknown field"))
        (is (str/includes? (.body landing) "id=\"raw-json\""))
        (is (str/includes? (.body landing) "const fileBackedValues=Object.create(null)"))
        (is (str/includes? (.body landing) "function contentValue(id)"))
        (is (str/includes? (.body landing) "event.target.type==='file'"))
        (is (str/includes? (.body landing) "setFileBackedValue(targetId"))
        (is (str/includes? (.body landing) "hx-post=\"/ui/tokens\""))
        (is (str/includes? (.body landing) "X-CSRF-Token")))
      (testing "preview is returned as an HTML fragment"
        (let [preview (request! port :post "/ui/preview"
                                (form {:request request-json}) headers)]
          (is (= 200 (.statusCode preview)))
          (is (str/includes? (.body preview) "data:image/png;base64,"))
          (is (str/includes? (.body preview) "Midpoint preview"))))
      (testing "missing CSRF is rejected before submission"
        (is (= 403
               (.statusCode
                (request! port :post "/ui/jobs"
                          (form {:request request-json})
                          (merge form-content-type {"Cookie" owner-cookie}))))))
      (let [submission (request! port :post "/ui/jobs"
                                 (form {:request request-json}) headers)
            job-id (second (re-find #"id=\"job-([^\"]+)\"" (.body submission)))
            status-path (str "/ui/jobs/" job-id)]
        (is (= 202 (.statusCode submission)))
        (is (string? job-id))
        (is (str/includes? (.body submission)
                           (str "hx-get=\"" status-path "\"")))
        (is (str/includes? (.body submission) "Queued"))
        (is (= 200 (.statusCode
                    (request! port :get status-path nil {"Cookie" owner-cookie}))))
        (is (= 404 (.statusCode
                    (request! port :get status-path nil {"Cookie" member-cookie}))))
        (let [cancelled (request! port :post (str status-path "/cancel") "" headers)]
          (is (= 200 (.statusCode cancelled)))
          (is (str/includes? (.body cancelled) "Cancelled"))
          (is (str/includes? (.body cancelled)
                             (str "hx-post=\"" status-path "/retry\""))))
        (let [retried (request! port :post (str status-path "/retry") "" headers)]
          (is (= 202 (.statusCode retried)))
          (is (str/includes? (.body retried) "Queued"))
          (is (str/includes? (.body retried) "Attempt 2"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest htmx-token-secret-is-shown-once-and-user-content-is-encoded
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        token-system (tokens/in-memory-system
                      {:pepper (.getBytes "abcdefghijklmnopqrstuvwxyz012345")})
        {:keys [auth-system owner-cookie owner-csrf]} (fixture)
        server (api/start! port {:job-service (:service lifecycle)
                                 :auth-system auth-system
                                 :token-service (:service token-system)})
        headers (merge form-content-type
                       {"Cookie" owner-cookie "X-CSRF-Token" owner-csrf})]
    (try
      (let [created (request! port :post "/ui/tokens"
                              (form {:name "<script>alert(1)</script>"})
                              headers)
            raw-token (second (re-find #"<code>(agg_pat_[^<]+)</code>"
                                       (.body created)))
            listed (request! port :get "/ui/tokens" nil
                             {"Cookie" owner-cookie})]
        (is (= 201 (.statusCode created)))
        (is (= "no-store"
               (.orElse (.firstValue (.headers created) "Cache-Control") nil)))
        (is (string? raw-token))
        (is (str/includes? (.body created) "&lt;script&gt;alert(1)&lt;/script&gt;"))
        (is (not (str/includes? (.body created) "<script>alert(1)</script>")))
        (is (= 200 (.statusCode listed)))
        (is (str/includes? (.body listed) "&lt;script&gt;alert(1)&lt;/script&gt;"))
        (is (not (str/includes? (.body listed) raw-token)))
        (is (not (str/includes? (.body listed) "hash"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
