(ns agg.proto-release-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- read-json [path]
  (json/read-str (slurp path) :key-fn keyword))

(deftest proto-runtime-wiring-is-separate-from-the-main-app
  (let [deps-edn (slurp "deps.edn")
        ci-workflow (slurp ".github/workflows/ci.yml")
        dev-workflow (slurp ".github/workflows/deploy.yml")
        prod-workflow (slurp ".github/workflows/deploy-production.yml")
        shared-infra (slurp "infra/dev/main.tf")
        proto-hosting (read-json "firebase.proto.template.json")]
    (is (str/includes? deps-edn ":proto {:main-opts [\"-m\" \"agg.proto.main\"]}"))
    (is (str/includes? deps-edn ":proto-test"))
    (is (str/includes? ci-workflow "name: Proto CI"))
    (is (str/includes? ci-workflow "branches: [codex/issue-161-proto-only]"))
    (is (str/includes? ci-workflow "clojure -M:proto-test"))
    (is (not (str/includes? ci-workflow "clojure -M:test-all")))
    (is (str/includes? shared-infra "resource \"google_cloud_run_v2_service\" \"proto\""))
    (is (str/includes? shared-infra "name                = \"agg-proto\""))
    (is (str/includes? shared-infra "value = \"proto\""))
    (testing "development workflow deploys the dev server from main and dev"
      (is (str/includes? dev-workflow
                         "branches: [main, dev]"))
      (is (str/includes? dev-workflow "ref: ${{ github.sha }}"))
      (is (str/includes? dev-workflow "SERVICE: agg-api"))
      (is (str/includes? dev-workflow "DURABLE_JOB: agg-renderer"))
      (is (str/includes? dev-workflow "Deploy private API service"))
      (is (str/includes? dev-workflow "Promote durable renderer"))
      (is (not (str/includes? dev-workflow "PROTO_SERVICE: agg-proto")))
      (is (not (str/includes? dev-workflow "AGG_SERVICE_PROFILE=proto"))))
    (testing "production workflow still deploys and configures agg-proto"
      (is (str/includes? prod-workflow "PROTO_SERVICE: agg-proto"))
      (is (str/includes? prod-workflow "gcloud run deploy \"$PROTO_SERVICE\""))
      (is (str/includes? prod-workflow "AGG_SERVICE_PROFILE=proto")))
    (is (str/includes? prod-workflow "PROTO_PUBLIC_BASE_URL: https://proto.alphacompose.com"))
    (is (str/includes? prod-workflow "PROTO_FIREBASE_SITE"))
    (is (str/includes? prod-workflow "firebase.proto.template.json"))
    (is (= ["firebase-debug.log" "firebase-debug.*.log"]
           (get-in proto-hosting [:hosting :ignore])))
    (is (= [{:source "**"
             :run {:serviceId "agg-proto"
                   :region "europe-central2"
                   :pinTag true}}]
           (get-in proto-hosting [:hosting :rewrites])))
    (is (= "__PROTO_FIREBASE_SITE__"
           (get-in proto-hosting [:hosting :site])))))
