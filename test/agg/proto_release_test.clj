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
        proto-bootstrap-workflow
        (slurp ".github/workflows/bootstrap-proto-terraform.yml")
        prod-workflow (slurp ".github/workflows/deploy-production.yml")
        shared-infra (slurp "infra/dev/main.tf")
        prod-infra (slurp "infra/prod/main.tf")
        proto-infra (slurp "infra/proto/main.tf")
        proto-versions (slurp "infra/proto/versions.tf")
        proto-bootstrap (slurp "script/bootstrap_proto_terraform.sh")
        proto-hosting (read-json "firebase.proto.template.json")
        proto-smoke (slurp "test/proto_container_smoke.sh")]
    (is (str/includes? deps-edn ":proto {:main-opts [\"-m\" \"agg.proto.main\"]}"))
    (is (str/includes? deps-edn ":proto-test"))
    (doseq [main-owned [dev-workflow prod-workflow prod-infra]]
      (testing "main-owned deployment surfaces exclude proto"
        (is (not (str/includes? main-owned "PROTO_SERVICE")))
        (is (not (str/includes? main-owned "agg-proto")))
        (is (not (str/includes? main-owned "refs/heads/proto")))
        (is (not (str/includes? main-owned "firebase.proto.template.json")))))
    (is (str/includes? proto-infra
                       "resource \"google_cloud_run_v2_service\" \"proto\""))
    (is (re-find #"(?s)removed \{\s+from = google_cloud_run_v2_service\.proto.*?destroy = false"
                 shared-infra))
    (is (str/includes? proto-infra
                       "resource \"google_iam_workload_identity_pool_provider\" \"proto\""))
    (is (str/includes? proto-infra
                       "\"google.subject\"       = \"assertion.repository_id + ':' + assertion.ref\"")
        "Terraform maps tag-safe WIF subjects below Google's 127-byte limit")
    (is (str/includes? proto-bootstrap
                       "google.subject=assertion.repository_id + ':' + assertion.ref")
        "bootstrap maps tag-safe WIF subjects below Google's 127-byte limit")
    (is (not (str/includes? proto-bootstrap "google.subject=assertion.sub")))
    (is (str/includes? proto-infra
                       "resource \"google_firebase_hosting_site\" \"proto\""))
    (is (str/includes? proto-infra "name                = \"agg-proto\""))
    (is (str/includes? proto-infra "AGG_SERVICE_PROFILE                 = \"proto\""))
    (is (str/includes? proto-infra "deletion_protection = true"))
    (is (str/includes? proto-infra "prevent_destroy = true"))
    (is (str/includes? proto-versions "prefix = \"proto\""))
    (is (re-find #"(?s)on:\s+push:\s+branches: \[proto\]"
                 proto-workflow))
    (is (not (str/includes? proto-workflow "workflow_dispatch")))
    (is (str/includes? proto-workflow "clojure -M:proto-test"))
    (is (str/includes? proto-workflow "test/proto_container_smoke.sh"))
    (is (not (str/includes? proto-workflow "clojure -M:test-all")))
    (is (not (str/includes? proto-workflow "clj-kondo --lint src test build.clj")))
    (is (str/includes? proto-workflow "PROJECT_ID: animated-graph-cloud-prod-jp"))
    (is (str/includes? proto-workflow "PROJECT_NUMBER: \"488013150738\""))
    (is (str/includes? proto-workflow "PROTO_PUBLIC_BASE_URL: https://proto.alphacompose.com"))
    (is (str/includes? proto-workflow "PROTO_FIREBASE_SITE: proto-alphacompose"))
    (is (str/includes? proto-workflow "agg-proto-github-deployer"))
    (is (str/includes? proto-workflow "providers/animated-graph-cloud-proto"))
    (is (str/includes? proto-workflow "terraform -chdir=infra/proto init"))
    (is (str/includes? proto-workflow "terraform -chdir=infra/proto plan"))
    (is (str/includes? proto-workflow "terraform -chdir=infra/proto apply"))
    (is (str/includes? proto-workflow "Delete or replace actions are forbidden"))
    (is (str/includes? proto-workflow "Discover current API dispatcher audience"))
    (is (str/includes? proto-workflow "firebase.proto.template.json"))
    (is (str/includes? proto-workflow "Publish pinned proto Firebase Hosting routes"))
    (is (str/includes? proto-workflow "Verify public proto release"))
    (is (str/includes? proto-workflow "Timing workspace playback prototype"))
    (is (not (str/includes? proto-workflow "gcloud run deploy")))
    (is (not (str/includes? proto-workflow "gcloud run services update")))
    (is (not (str/includes? proto-workflow "gcloud run services add-iam-policy-binding")))
    (is (not (str/includes? proto-workflow "Deploy private API service")))
    (is (not (str/includes? proto-workflow "Promote durable renderer")))
    (is (not (str/includes? proto-workflow "Activate verified API revision")))
    (is (re-find #"(?s)on:\s+push:\s+tags:\s+-\s+\"proto-terraform-bootstrap-\*\""
                 proto-bootstrap-workflow))
    (is (str/includes? proto-bootstrap-workflow
                       "proto-terraform-bootstrap-${GITHUB_SHA}"))
    (is (str/includes? proto-bootstrap-workflow
                       "git merge-base --is-ancestor origin/proto \"$GITHUB_SHA\""))
    (is (not (str/includes? proto-bootstrap-workflow
                            "git merge-base --is-ancestor \"$GITHUB_SHA\" origin/proto")))
    (is (str/includes? proto-bootstrap-workflow
                       "providers/animated-graph-cloud-proto"))
    (is (str/includes? proto-bootstrap-workflow
                       "terraform -chdir=infra/proto apply"))
    (is (str/includes? proto-bootstrap "PROTO_BOOTSTRAP_CONFIRM"))
    (is (str/includes? proto-bootstrap "agg-proto-github-deployer"))
    (is (str/includes? proto-bootstrap "animated-graph-cloud-proto"))
    (doseq [configuration [proto-infra proto-bootstrap]]
      (is (str/includes? configuration "Alpha Compose proto GitHub"))
      (is (not (str/includes? configuration
                              "animated-graph-cloud proto branch"))))
    (is (not (str/includes? proto-bootstrap "service-account key")))
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
