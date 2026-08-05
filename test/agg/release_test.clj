(ns agg.release-test
  (:require [agg.api.main :as api]
            [agg.http-test-support :as test-http]
            [agg.main-release :as main-release]
            [agg.release :as release]
            [agg.ui.core :as ui]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def full-build "0123456789abcdef0123456789abcdef01234567")

(defn- request! [port path]
  (test-http/send-string! :get (str "http://127.0.0.1:" port path)
                          nil {}))

(deftest semantic-version-resource-is-strict
  (is (= "0.6.0"
         (release/parse-version-resource "{:version \"0.6.0\"}")))
  (doseq [invalid ["{}"
                   "{:version \"v0.6.0\"}"
                   "{:version \"0.6\"}"
                   "{:version \"01.6.0\"}"
                   "{:version \"0.6.0\" :proto-version \"0.8.0\"}"]]
    (testing invalid
      (is (thrown? IllegalArgumentException
                   (release/parse-version-resource invalid))))))

(deftest build-identity-is-exact-and-production-safe
  (is (= {:full "dev" :short "dev"}
         (release/parse-build-identity nil false)))
  (is (= {:full "dev" :short "dev"}
         (release/parse-build-identity "dev" false)))
  (is (= {:full full-build :short "0123456"}
         (release/parse-build-identity full-build true)))
  (doseq [invalid [nil "" "dev" "0123456" (str full-build "0")
                   "0123456789ABCDEF0123456789ABCDEF01234567"]]
    (testing (pr-str invalid)
      (is (thrown? IllegalArgumentException
                   (release/parse-build-identity invalid true))))))

(deftest main-version-matches-newest-released-changelog
  (is (= "0.7.0" main-release/version))
  (is (= main-release/version
         (release/newest-released-version
          (main-release/changelog-markdown)))))

(deftest public-changelog-removes-unreleased-and-escapes-raw-html
  (let [html
        (release/public-changelog-html
         (str "# Changelog\n\n"
              "User-visible changes.\n\n"
              "## Unreleased\n\n"
              "- Private future note.\n\n"
              "## 0.6.0 - 2026-07-30\n\n"
              "### Added\n\n"
              "- Shipped behavior. <script>alert('unsafe')</script>\n"))]
    (is (str/includes? html "<h1>Changelog</h1>"))
    (is (str/includes? html "<h2>0.6.0 - 2026-07-30</h2>"))
    (is (str/includes? html
                       "&lt;script&gt;alert(&#x27;unsafe&#x27;)&lt;/script&gt;"))
    (is (not (str/includes? html "<script>")))
    (is (not (str/includes? html "Unreleased")))
    (is (not (str/includes? html "Private future note")))))

(deftest reusable-main-pages-show-one-linked-local-release-identity
  (let [pages
        {"anonymous" (ui/anonymous-page {})
         "faq" ui/faq-page
         "privacy" ui/privacy-page
         "terms" ui/terms-page
         "Drive recovery" ui/drive-recovery-page
         "compose" (ui/page {:user {:email "owner@example.com" :role :owner}
                             :csrf "csrf-test"})
         "tokens" (ui/token-page {:user {:email "owner@example.com"}
                                  :csrf "csrf-test"
                                  :tokens []})
         "members" (ui/member-admin-page
                    {:user {:email "owner@example.com"}
                     :csrf "csrf-test"
                     :members []})
         "logs" (ui/logs-page {:user {:email "owner@example.com"}
                               :csrf "csrf-test"
                               :logs []
                               :view "formatted"})
         "Drive Picker compact identity"
         (str "<!doctype html><html><head><style>"
              (ui/theme-style)
              "</style></head><body><div class=\"shell\">"
              (ui/picker-product-header)
              "</div></body></html>")
         "changelog" ui/changelog-page}]
    (doseq [[surface page] pages]
      (testing surface
        (is (= 1 (count (re-seq #"class=\"release-identity\"" page))))
        (is (str/includes?
             page
             "class=\"release-identity\" href=\"/changelog\">v0.7.0 · build dev</a>"))))))

(deftest changelog-is-public-cacheable-and-api-profile-only
  (let [api-port (test-http/available-port)
        proto-port (test-http/available-port)
        overlay-port (test-http/available-port)
        api-server (api/start! api-port {:service-profile "api"})
        proto-server (api/start! proto-port {:service-profile "proto"})
        overlay-server (api/start! overlay-port {:service-profile "overlay"})]
    (try
      (let [response (request! api-port "/changelog")]
        (is (= 200 (.statusCode response)))
        (is (= "public, max-age=300"
               (some-> response .headers
                       (.firstValue "cache-control") (.orElse nil))))
        (is (str/includes? (.body response)
                           "User-visible additions, changes, and fixes for Alpha Compose."))
        (is (not (str/includes? (.body response) "Unreleased")))
        (is (= 1 (count (re-seq #"class=\"release-identity\""
                                (.body response))))))
      (doseq [[profile port] [["proto" proto-port] ["overlay" overlay-port]]]
        (testing profile
          (let [response (request! port "/changelog")]
            (is (= 404 (.statusCode response)))
            (is (not (str/includes? (.body response) "Alpha Compose")))
            (is (not (str/includes? (.body response) "0.6.0"))))))
      (finally
        (.close ^java.lang.AutoCloseable api-server)
        (.close ^java.lang.AutoCloseable proto-server)
        (.close ^java.lang.AutoCloseable overlay-server)))))
