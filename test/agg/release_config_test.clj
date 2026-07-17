(ns agg.release-config-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- read-json [path]
  (json/read-str (slurp path) :key-fn keyword))

(deftest production-infrastructure-is-isolated-and-owner-only
  (let [production (slurp "infra/prod/main.tf")
        backend (slurp "infra/prod/versions.tf")
        variables (slurp "infra/prod/variables.tf")
        shared (slurp "infra/dev/main.tf")]
    (is (str/includes? production "source = \"../dev\""))
    (is (re-find #"project_id\s*=\s*\"animated-graph-cloud-prod-jp\"" production))
    (is (re-find #"environment_name\s*=\s*\"production\"" production))
    (is (re-find #"import_default_firestore\s*=\s*false" production))
    (is (re-find #"github_subject\s*=\s*\"repo:jamiepratt/animated-graph-cloud:environment:production\""
                 production))
    (is (str/includes? backend
                       "bucket = \"animated-graph-cloud-prod-jp-tfstate\""))
    (is (str/includes? backend "prefix = \"prod\""))
    (is (str/includes? variables "variable \"renderer_image\""))
    (is (not (str/includes? variables "client_secret")))
    (is (str/includes? shared
                       "for_each = var.import_default_firestore ? toset([var.project_id]) : toset([])"))
    (is (str/includes? shared "id = \"projects/${each.value}/databases/(default)\""))
    (is (str/includes? shared "count = var.api_service_url == \"\" ? 0 : 1"))))

(deftest firebase-hosting-routes-the-public-domain-to-warsaw-cloud-run
  (let [{:keys [hosting]} (read-json "firebase.json")
        production (slurp "infra/prod/main.tf")]
    (is (= "animated-graph-cloud-prod-jp"
           (get-in (read-json ".firebaserc") [:projects :default])))
    (is (= [{:source "**"
             :run {:serviceId "agg-api"
                   :region "europe-central2"
                   :pinTag true}}]
           (:rewrites hosting)))
    (is (= ["firebase-debug.log" "firebase-debug.*.log"] (:ignore hosting)))
    (is (re-find #"enable_firebase_hosting\s*=\s*true" production))))

(deftest production-release-is-manual-environment-gated-and-domain-aware
  (let [workflow (slurp ".github/workflows/deploy-production.yml")]
    (is (str/includes? workflow "workflow_dispatch:"))
    (is (not (re-find #"(?m)^\s*push:" workflow)))
    (is (str/includes? workflow "environment: production"))
    (is (str/includes? workflow
                       "confirmation == 'RELEASE ALPHA COMPOSE TO OWNER ONLY'"))
    (is (str/includes? workflow "PROJECT_ID: animated-graph-cloud-prod-jp"))
    (is (str/includes? workflow "PROJECT_NUMBER: \"488013150738\""))
    (is (str/includes? workflow "PUBLIC_BASE_URL: https://alphacompose.com"))
    (is (str/includes? workflow "echo \"commit=$commit\""))
    (is (str/includes? workflow "${{ steps.source.outputs.commit }}"))
    (is (not (str/includes? workflow "animated-graph-cloud:${{ github.sha }}")))
    (is (str/includes? workflow "AGG_OWNER_EMAIL=$OWNER_EMAIL"))
    (is (str/includes? workflow "AGG_PUBLIC_BASE_URL=$PUBLIC_BASE_URL"))
    (is (str/includes? workflow "npx --yes firebase-tools@15.24.0 deploy"))
    (is (str/includes? workflow "--only hosting"))
    (is (str/includes? workflow "--update-secrets"))
    (is (not (str/includes? workflow "client_secret")))
    (is (not (str/includes? workflow "service-account-key")))))

(deftest continuous-integration-validates-both-environments-and-api-contract
  (let [workflow (slurp ".github/workflows/ci.yml")]
    (is (str/includes? workflow
                       "terraform -chdir=infra/dev init -backend=false -input=false"))
    (is (str/includes? workflow
                       "terraform -chdir=infra/prod init -backend=false -input=false"))
    (is (str/includes? workflow "terraform -chdir=infra/prod validate"))
    (is (str/includes? workflow "@redocly/cli@2.39.0 lint docs/openapi.yaml"))
    (is (str/includes? workflow
                       "bash -n script/release_acceptance.sh script/production_load_test.sh"))))

(deftest openapi-describes-the-supported-production-api
  (let [openapi (slurp "docs/openapi.yaml")]
    (is (str/includes? openapi "openapi: 3.1.0"))
    (is (str/includes? openapi "https://alphacompose.com"))
    (doseq [path ["/health:" "/v1/preview:" "/v1/overlay:" "/v1/jobs:"
                  "/v1/jobs/{jobId}:" "/v1/jobs/{jobId}/cancel:"
                  "/v1/jobs/{jobId}/retry:" "/v1/uploads:" "/v1/tokens:"
                  "/v1/tokens/{tokenId}/revoke:" "/v1/admin/members:"
                  "/v1/admin/members/revoke:"]]
      (testing path (is (str/includes? openapi path))))
    (doseq [contract ["RenderRequest:" "Job:" "Error:" "bearerAuth:"
                      "sessionCookie:" "Idempotency-Key"]]
      (testing contract (is (str/includes? openapi contract))))
    (is (str/includes? openapi "drive.file"))
    (is (not (str/includes? openapi "client_secret")))))

(deftest release-acceptance-separates-automation-from-human-evidence
  (let [automation (slurp "script/release_acceptance.sh")
        load-test (slurp "script/production_load_test.sh")
        acceptance (slurp "docs/release-acceptance.md")
        evidence (slurp "docs/release-evidence.template.json")]
    (doseq [command ["clojure -M:test" "clojure -T:build uber"
                     "terraform -chdir=infra/dev" "terraform -chdir=infra/prod"
                     "@redocly/cli@2.39.0" "docker build" "trivy image"]]
      (testing command (is (str/includes? automation command))))
    (is (not (str/includes? automation "gcloud run deploy")))
    (is (not (str/includes? automation "firebase deploy")))
    (is (str/includes? load-test "ALPHA_COMPOSE_ALLOW_COSTED_LOAD_TEST"))
    (is (str/includes? load-test "MAX_CONCURRENCY=5"))
    (doseq [manual ["DaVinci Resolve" "Google Drive" "owner-only production smoke"
                    "OAuth brand verification" "legal approval"]]
      (testing manual (is (str/includes? acceptance manual))))
    (is (str/includes? acceptance "Automated"))
    (is (str/includes? acceptance "Manual/external"))
    (is (str/includes? acceptance "Maximum-duration renders"))
    (is (str/includes? acceptance "all supported input formats"))
    (is (str/includes? acceptance "cancellation, retry, revocation, and recovery"))
    (is (str/includes? evidence "\"status\": \"not-run\""))
    (is (not (str/includes? evidence "\"status\": \"passed\"")))))

(deftest production-runbook-has-safe-bootstrap-rollback-and-oauth-checkpoints
  (let [runbook (slurp "docs/production-runbook.md")]
    (doseq [checkpoint ["cannot be undone" "Firebase Terms" "production environment"
                        "https://alphacompose.com/privacy"
                        "https://alphacompose.com/v1/auth/login/callback"
                        "https://alphacompose.com/v1/auth/drive/callback"
                        "drive.file" "owner only" "Rollback" "Secret Manager"
                        "animated-graph-cloud-prod-jp" "animated-graph-cloud-jp"]]
      (testing checkpoint (is (str/includes? runbook checkpoint))))
    (is (str/includes? runbook "gcloud secrets versions add"))
    (is (str/includes? runbook "--data-file=-"))
    (is (not (str/includes? runbook "service account key")))))

(deftest production-release-decision-is-contextual-and-discoverable
  (let [adr (slurp "docs/adr/0009-isolate-production-behind-owner-gated-release.md")
        context-map (slurp "CONTEXT-MAP.md")]
    (doseq [decision ["Firebase Hosting" "europe-central2" "production"
                      "owner-only" "separate OAuth" "manual"]]
      (testing decision (is (str/includes? adr decision))))
    (is (str/includes? context-map "infra/prod/"))
    (is (str/includes? context-map "docs/openapi.yaml"))
    (is (str/includes? context-map "docs/production-runbook.md"))))
