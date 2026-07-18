(ns agg.release-config-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- read-json [path]
  (json/read-str (slurp path) :key-fn keyword))

(deftest production-infrastructure-is-isolated-and-main-only
  (let [production (slurp "infra/prod/main.tf")
        backend (slurp "infra/prod/versions.tf")
        variables (slurp "infra/prod/variables.tf")
        shared (slurp "infra/dev/main.tf")]
    (is (str/includes? production "source = \"../dev\""))
    (is (re-find #"project_id\s*=\s*\"animated-graph-cloud-prod-jp\"" production))
    (is (re-find #"environment_name\s*=\s*\"production\"" production))
    (is (re-find #"import_default_firestore\s*=\s*false" production))
    (is (re-find #"github_subject\s*=\s*\"repo:jamiepratt@558780/animated-graph-cloud@1303177214:ref:refs/heads/main\""
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

(deftest artifact-registry-bootstrap-target-is-isolated-and-state-compatible
  (let [shared (slurp "infra/dev/main.tf")
        runbook (slurp "docs/production-runbook.md")
        required-services
        (second (re-find #"(?s)required_services\s*=\s*setunion\(toset\(\[(.*?)\]\),\s*var\.enable_firebase_hosting"
                         shared))]
    (is (not (str/includes? required-services
                            "artifactregistry.googleapis.com")))
    (is (re-find #"(?s)resource \"google_project_service\" \"artifact_registry\" \{.*?service\s*=\s*\"artifactregistry.googleapis.com\".*?\}"
                 shared))
    (is (re-find #"(?s)moved \{\s*from\s*=\s*google_project_service.required\[\"artifactregistry.googleapis.com\"\]\s*to\s*=\s*google_project_service.artifact_registry\s*\}"
                 shared))
    (is (re-find #"(?s)resource \"google_artifact_registry_repository\" \"containers\" \{.*?depends_on\s*=\s*\[google_project_service.artifact_registry\].*?\}"
                 shared))
    (is (str/includes? runbook "exactly two additions"))
    (is (str/includes? runbook "Artifact Registry API"))
    (is (not (str/includes? runbook "First create only the repository")))))

(deftest production-runtime-secrets-are-utf8-safe
  (let [runbook (slurp "docs/production-runbook.md")]
    (is (= 2 (count (re-seq #"openssl rand -base64 48 \| tr -d '\\n'"
                            runbook))))
    (is (not (re-find #"openssl rand 48 \|" runbook)))))

(deftest internal-service-tokens-use-the-cloud-run-audience
  (let [runtime (slurp "src/agg/jobs/gcp.clj")
        auth-runtime (slurp "src/agg/auth/gcp.clj")]
    (is (str/includes? runtime ":internal-audience dispatcher-url"))
    (is (str/includes?
         auth-runtime
         ":task-token-verifier (task-token-verifier internal-audience)"))
    (is (str/includes? auth-runtime ":task-audience internal-audience"))))

(deftest firebase-hosting-routes-the-public-domain-to-warsaw-cloud-run
  (let [{:keys [hosting]} (read-json "firebase.json")
        production (slurp "infra/prod/main.tf")]
    (is (not (.exists (java.io.File. ".firebaserc"))))
    (is (= [{:source "**"
             :run {:serviceId "agg-api"
                   :region "europe-central2"
                   :pinTag true}}]
           (:rewrites hosting)))
    (is (= ["firebase-debug.log" "firebase-debug.*.log"] (:ignore hosting)))
    (is (re-find #"enable_firebase_hosting\s*=\s*true" production))))

(deftest production-release-deploys-main-push-and-is-domain-aware
  (let [workflow (slurp ".github/workflows/deploy-production.yml")]
    (is (str/includes? workflow "push:"))
    (is (str/includes? workflow "branches: [main]"))
    (is (not (str/includes? workflow "workflow_dispatch:")))
    (is (not (str/includes? workflow "environment: production")))
    (is (not (str/includes? workflow "confirmation")))
    (is (str/includes? workflow "PROJECT_ID: animated-graph-cloud-prod-jp"))
    (is (str/includes? workflow "PROJECT_NUMBER: \"488013150738\""))
    (is (str/includes? workflow "PUBLIC_BASE_URL: https://alphacompose.com"))
    (is (str/includes? workflow
                       "GOOGLE_CLOUD_PROJECT=$PROJECT_ID"))
    (is (str/includes? workflow "full 40-character lowercase commit SHA"))
    (is (not (str/includes? workflow "default: main")))
    (is (str/includes? workflow "^\u005b0-9a-f\u005d{40}$"))
    (is (str/includes? workflow "github.sha"))
    (is (not (str/includes? workflow "inputs.release_ref")))
    (is (not (str/includes? workflow "steps.source.outputs.commit")))
    (is (str/includes? workflow "test \"$(git rev-parse HEAD)\" = \"$RELEASE_COMMIT\""))
    (is (str/includes? workflow "AGG_OWNER_EMAIL=$OWNER_EMAIL"))
    (is (str/includes? workflow "AGG_ADMIN_EMAILS=$ADMIN_EMAILS"))
    (is (re-find
         #"gcloud run jobs update \"\$DURABLE_JOB\"[\s\S]*?--update-env-vars \"GOOGLE_CLOUD_PROJECT=\$PROJECT_ID\""
         workflow))
    (is (str/includes? workflow "AGG_PUBLIC_BASE_URL=$PUBLIC_BASE_URL"))
    (is (str/includes? workflow "npx --yes firebase-tools@15.24.0 deploy"))
    (is (str/includes? workflow "--only hosting"))
    (is (str/includes? workflow "--update-secrets"))
    (is (not (str/includes? workflow "client_secret")))
    (is (not (str/includes? workflow "service-account-key")))
    (doseq [reference
            ["actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0 # v7"
             "google-github-actions/auth@7c6bc770dae815cd3e89ee6cdf493a5fab2cc093 # v3"
             "google-github-actions/setup-gcloud@aa5489c8933f4cc7a4f7d45035b3b1440c9c10db # v3"]]
      (testing reference (is (str/includes? workflow reference))))
    (is (not (re-find #"uses: (?:actions/checkout|google-github-actions/(?:auth|setup-gcloud))@v\u005cd+"
                      workflow)))))

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
                  "/v1/admin/members/revoke:" "/v1/auth/login/start:"
                  "/v1/auth/login/callback:" "/v1/auth/drive/start:"
                  "/v1/auth/drive/callback:" "/v1/drive/picker:"]]
      (testing path (is (str/includes? openapi path))))
    (doseq [contract ["RenderRequest:" "Job:" "Error:" "bearerAuth:"
                      "sessionCookie:" "Idempotency-Key"]]
      (testing contract (is (str/includes? openapi contract))))
    (doseq [field ["telemetryFormat" "telemetrySyncAt" "sectionStartAt"
                   "spo2" "timer" "watermark" "sourceVideo"
                   "outputFormat" "fitMode" "audioMode"]]
      (testing field (is (str/includes? openapi field))))
    (is (str/includes? openapi "drive.file"))
    (doseq [behavior ["operationId: startGoogleLogin"
                      "operationId: finishGoogleLogin"
                      "operationId: startGoogleDriveAuthorization"
                      "operationId: finishGoogleDriveAuthorization"
                      "operationId: showGoogleDrivePicker"
                      "name: code" "name: state" "security: []"
                      "description: Redirect to Google OAuth authorization."
                      "description: Redirect to the signed-in homepage."
                      "description: Redirect to the homepage with Drive connected."]]
      (testing behavior (is (str/includes? openapi behavior))))
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
    (is (str/includes? load-test "ALPHA_COMPOSE_LOAD_RESULTS_DIR"))
    (is (str/includes? load-test "chmod 700"))
    (is (str/includes? load-test "Response evidence:"))
    (is (not (str/includes? load-test "rm -rf -- \"$results_dir\"")))
    (is (str/includes? load-test "rm -f -- \"$auth_config\""))
    (doseq [manual ["DaVinci Resolve" "Google Drive" "owner/admin production smoke"
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
    (doseq [checkpoint ["cannot be undone" "Firebase Terms" "protected\n`main`"
                        "https://alphacompose.com/privacy"
                        "https://alphacompose.com/v1/auth/login/callback"
                        "https://alphacompose.com/v1/auth/drive/callback"
                        "drive.file" "AGG_ADMIN_EMAILS" "Rollback" "Secret Manager"
                        "animated-graph-cloud-prod-jp" "animated-graph-cloud-jp"]]
      (testing checkpoint (is (str/includes? runbook checkpoint))))
    (is (str/includes? runbook "gcloud secrets versions add"))
    (is (str/includes? runbook "--data-file=-"))
    (is (str/includes? runbook
                       "export GOOGLE_CLOUD_QUOTA_PROJECT=animated-graph-cloud-prod-jp"))
    (is (str/includes? runbook "Application Default Credentials"))
    (is (re-find #"before any local\s+Terraform or Firebase CLI command"
                 runbook))
    (is (str/includes? runbook "Every push to protected `main`"))
    (is (not (str/includes? runbook "production environment")))
    (is (not (str/includes? runbook ".firebaserc")))
    (is (not (str/includes? runbook "service account key")))))

(deftest production-release-decision-is-contextual-and-discoverable
  (let [adr (slurp "docs/adr/0011-automatic-production-deployment.md")
        context-map (slurp "CONTEXT-MAP.md")]
    (doseq [decision ["Production deployment" "protected `main`"
                      "Branch protection" "Rollback"]]
      (testing decision (is (str/includes? adr decision))))
    (is (str/includes? context-map "infra/prod/"))
    (is (str/includes? context-map "docs/openapi.yaml"))
    (is (str/includes? context-map "docs/production-runbook.md"))))
