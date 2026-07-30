(ns agg.proto-identity-test
  (:require [agg.api.main :as api]
            [agg.http-test-support :as test-http]
            [agg.proto.core :as proto]
            [agg.proto-release :as proto-release]
            [agg.release :as release]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def full-build "0123456789abcdef0123456789abcdef01234567")

(defn- request! [port path]
  (test-http/send-string! :get (str "http://127.0.0.1:" port path)
                          nil {}))

(deftest proto-version-is-semantic-and-independent
  (is (= "0.8.0" proto-release/version))
  (is (= proto-release/version
         (release/newest-released-version
          (proto-release/changelog-markdown))))
  (doseq [invalid ["{}"
                   "{:version \"v0.8.0\"}"
                   "{:version \"0.8\"}"
                   "{:version \"00.8.0\"}"
                   "{:version \"0.8.0\" :main-version \"0.6.0\"}"]]
    (testing invalid
      (is (thrown? IllegalArgumentException
                   (release/parse-version-resource invalid))))))

(deftest proto-build-identity-is-exact-and-production-safe
  (is (= {:full "dev" :short "dev"}
         (release/parse-build-identity nil false)))
  (is (= {:full full-build :short "0123456"}
         (release/parse-build-identity full-build true)))
  (is (= "v0.8.0 · build 0123456"
         (:label (release/release-identity
                  {:version "0.8.0"
                   :build-commit full-build
                   :production? true}))))
  (doseq [invalid [nil "" "dev" "0123456" (str full-build "0")
                   "0123456789ABCDEF0123456789ABCDEF01234567"]]
    (testing (pr-str invalid)
      (is (thrown? IllegalArgumentException
                   (release/parse-build-identity invalid true))))))

(deftest proto-public-changelog-is-safe-and-released-only
  (let [html
        (release/public-changelog-html
         (str "# Proto changelog\n\n"
              "Released proto behavior.\n\n"
              "## Unreleased\n\n"
              "- Private future note.\n\n"
              "## 0.8.0 - 2026-07-30\n\n"
              "- Shipped. <script>alert('unsafe')</script>\n"))]
    (is (str/includes? html "<h2>0.8.0 - 2026-07-30</h2>"))
    (is (str/includes?
         html "&lt;script&gt;alert(&#x27;unsafe&#x27;)&lt;/script&gt;"))
    (is (not (str/includes? html "<script>")))
    (is (not (str/includes? html "Unreleased")))
    (is (not (str/includes? html "Private future note")))))

(deftest every-complete-proto-page-shows-one-linked-proto-identity
  (let [pages {"signed out" proto/signed-out-page
               "signed in" (proto/page
                            {:user {:email "owner@example.com"}
                             :csrf "csrf-token"
                             :folder-id proto/fixed-folder-id})
               "changelog" (proto/changelog-page)}]
    (doseq [[surface page] pages]
      (testing surface
        (is (= 1 (count (re-seq #"class=\"release-identity\"" page))))
        (is (str/includes?
             page
             "class=\"release-identity\" href=\"/changelog\">v0.8.0 · build dev</a>"))
        (is (not (str/includes? page "v0.6.0")))
        (is (not (str/includes? page "User-visible additions, changes, and fixes for Alpha Compose.")))))))

(deftest proto-changelog-is-public-cacheable-and-profile-isolated
  (let [proto-port (test-http/available-port)
        api-port (test-http/available-port)
        overlay-port (test-http/available-port)
        proto-server (api/start! proto-port {:service-profile "proto"})
        api-server (api/start! api-port {:service-profile "api"})
        overlay-server (api/start! overlay-port {:service-profile "overlay"})]
    (try
      (let [response (request! proto-port "/changelog")]
        (is (= 200 (.statusCode response)))
        (is (= "public, max-age=300"
               (some-> response .headers
                       (.firstValue "cache-control") (.orElse nil))))
        (is (str/includes? (.body response)
                           "Released changes for the independent Alpha Compose Proto playback harness."))
        (is (str/includes? (.body response) "v0.8.0 · build dev"))
        (is (not (str/includes? (.body response) "Unreleased")))
        (is (not (str/includes? (.body response) "v0.6.0"))))
      (doseq [[profile port] [["api" api-port] ["overlay" overlay-port]]]
        (testing profile
          (let [response (request! port "/changelog")]
            (is (= 404 (.statusCode response)))
            (is (not (str/includes? (.body response) "0.8.0"))))))
      (finally
        (.close ^java.lang.AutoCloseable proto-server)
        (.close ^java.lang.AutoCloseable api-server)
        (.close ^java.lang.AutoCloseable overlay-server)))))

(deftest proto-image-workflow-and-smoke-carry-the-exact-proto-commit
  (let [dockerfile (slurp "Dockerfile")
        build (slurp "build.clj")
        workflow (slurp ".github/workflows/deploy-proto.yml")
        smoke (slurp "test/proto_container_smoke.sh")]
    (doseq [contract ["ARG BUILD_COMMIT=dev"
                      "ARG RELEASE_MODE=development"
                      "COPY docs/proto/CHANGELOG.md ./docs/proto/CHANGELOG.md"
                      "AGG_BUILD_COMMIT=$BUILD_COMMIT"
                      "AGG_RELEASE_MODE=$RELEASE_MODE"]]
      (testing contract
        (is (str/includes? dockerfile contract))))
    (is (str/includes? build
                       "{:src \"docs/proto/CHANGELOG.md\""))
    (doseq [contract ["--build-arg BUILD_COMMIT=\"$GITHUB_SHA\""
                      "--build-arg RELEASE_MODE=production"
                      "test/proto_container_smoke.sh \"$IMAGE\" \"$GITHUB_SHA\""
                      "\"$PROTO_PUBLIC_BASE_URL/changelog\""
                      "v0.8.0 · build $SHORT_RELEASE_COMMIT"
                      "Unreleased"]]
      (testing contract
        (is (str/includes? workflow contract))))
    (is (str/includes? smoke "expected_build=\"${2:-dev}\""))
    (is (str/includes? smoke
                       "\"http://127.0.0.1:$proto_host_port/changelog\""))
    (is (str/includes? smoke "v0.8.0 · build $short_build"))
    (is (not (str/includes? (slurp "docs/proto/CHANGELOG.md")
                            "User-visible additions, changes, and fixes for Alpha Compose.")))
    (is (not (str/includes? (slurp "resources/agg/proto-version.edn")
                            "0.6.0")))))
