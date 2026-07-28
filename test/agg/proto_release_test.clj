(ns agg.proto-release-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- read-json [path]
  (json/read-str (slurp path) :key-fn keyword))

(deftest proto-runtime-wiring-is-separate-from-the-main-app
  (let [deps-edn (slurp "deps.edn")
        dev-workflow (slurp ".github/workflows/deploy.yml")
        proto-workflow (slurp ".github/workflows/deploy-proto.yml")
        prod-workflow (slurp ".github/workflows/deploy-production.yml")
        shared-infra (slurp "infra/dev/main.tf")
        proto-hosting (read-json "firebase.proto.template.json")
        proto-smoke (slurp "test/proto_container_smoke.sh")]
    (is (str/includes? deps-edn ":proto {:main-opts [\"-m\" \"agg.proto.main\"]}"))
    (is (str/includes? deps-edn ":proto-test"))
    (is (str/includes? shared-infra "resource \"google_cloud_run_v2_service\" \"proto\""))
    (is (str/includes? shared-infra "name                = \"agg-proto\""))
    (is (str/includes? shared-infra "value = \"proto\""))
    (doseq [workflow [dev-workflow proto-workflow prod-workflow]]
      (testing "workflow deploys and configures agg-proto"
        (is (str/includes? workflow "PROTO_SERVICE: agg-proto"))
        (is (str/includes? workflow "gcloud run deploy \"$PROTO_SERVICE\""))
        (is (str/includes? workflow "AGG_SERVICE_PROFILE=proto"))))
    (is (re-find #"(?s)on:\s+push:\s+branches: \[proto\]\s+workflow_dispatch:"
                 proto-workflow))
    (is (str/includes? proto-workflow "clojure -M:proto-test"))
    (is (str/includes? proto-workflow "test/proto_container_smoke.sh"))
    (is (not (str/includes? proto-workflow "clojure -M:test-all")))
    (is (not (str/includes? proto-workflow "clj-kondo --lint src test build.clj")))
    (is (str/includes? proto-workflow "PROJECT_ID: animated-graph-cloud-prod-jp"))
    (is (str/includes? proto-workflow "PROJECT_NUMBER: \"488013150738\""))
    (is (str/includes? proto-workflow "PROTO_PUBLIC_BASE_URL: https://proto.alphacompose.com"))
    (is (str/includes? proto-workflow "PROTO_FIREBASE_SITE: proto-alphacompose"))
    (is (str/includes? proto-workflow "Discover current API dispatcher audience"))
    (is (str/includes? proto-workflow "AGG_DISPATCHER_URL=$CLOUD_RUN_SERVICE_URL"))
    (is (str/includes? proto-workflow "AGG_PUBLIC_BASE_URL=$PROTO_PUBLIC_BASE_URL"))
    (is (str/includes? proto-workflow "firebase.proto.template.json"))
    (is (str/includes? proto-workflow "Restore public proto invoker"))
    (is (str/includes? proto-workflow "Publish pinned proto Firebase Hosting routes"))
    (is (str/includes? proto-workflow "Verify public proto release"))
    (is (str/includes? proto-workflow "Timing workspace playback prototype"))
    (is (not (str/includes? proto-workflow "Deploy private API service")))
    (is (not (str/includes? proto-workflow "Promote durable renderer")))
    (is (not (str/includes? proto-workflow "Activate verified API revision")))
    (is (str/includes? prod-workflow "PROTO_PUBLIC_BASE_URL: https://proto.alphacompose.com"))
    (is (str/includes? prod-workflow "PROTO_FIREBASE_SITE"))
    (is (str/includes? prod-workflow "firebase.proto.template.json"))
    (is (str/includes? proto-smoke "clojure.main -m agg.proto.main"))
    (is (str/includes? proto-smoke "Proto API server started"))
    (is (str/includes? proto-smoke "/health"))
    (is (= ["firebase-debug.log" "firebase-debug.*.log"]
           (get-in proto-hosting [:hosting :ignore])))
    (is (= [{:source "**"
             :run {:serviceId "agg-proto"
                   :region "europe-central2"
                   :pinTag true}}]
           (get-in proto-hosting [:hosting :rewrites])))
    (is (= "__PROTO_FIREBASE_SITE__"
           (get-in proto-hosting [:hosting :site])))))
