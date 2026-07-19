(ns agg.release-config-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- read-json [path]
  (json/read-str (slurp path) :key-fn keyword))

(defn- config-section [text start-marker end-marker]
  (let [start (str/index-of text start-marker)
        end (some->> start
                     (+ (count start-marker))
                     (str/index-of text end-marker))]
    (when (and start end)
      (subs text start end))))

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
    (is (= [{:source "/v1/overlay"
             :run {:serviceId "agg-overlay"
                   :region "europe-central2"
                   :pinTag true}}
            {:source "**"
             :run {:serviceId "agg-api"
                   :region "europe-central2"
                   :pinTag true}}]
           (:rewrites hosting)))
    (is (= ["firebase-debug.log" "firebase-debug.*.log"] (:ignore hosting)))
    (is (re-find #"enable_firebase_hosting\s*=\s*true" production))))

(deftest synchronous-overlays-have-one-same-origin-diagnostic-contract
  (let [openapi (slurp "docs/openapi.yaml")
        readme (slurp "README.md")
        decision (slurp
                  "docs/adr/0012-bound-synchronous-overlays-to-production-evidence.md")
        runbook (slurp "docs/production-runbook.md")]
    (is (= 1 (count (re-seq #"url: https://alphacompose.com(?:\n|$)" openapi))))
    (doseq [contract ["synchronous_overlay_duration_exceeded"
                      "maxDurationSeconds: {const: 1}"
                      "durableJobsPath: {const: /v1/jobs}"
                      "\"422\": {$ref: \"#/components/responses/SynchronousOverlayDurationExceeded\"}"]]
      (testing contract
        (is (str/includes? openapi contract))))
    (doseq [implication ["global external load balancer"
                         "No CORS response is added"
                         "session cookie scope is\nnot broadened"]]
      (testing implication
        (is (str/includes? decision implication))))
    (is (re-find #"(?s)one-second production diagnostic.*?\"sectionStartAt\": \"2026-07-17T09:00:00Z\",\s+\"sectionEndAt\": \"2026-07-17T09:00:01Z\""
                 readme))
    (is (str/includes? runbook "before encoder startup"))
    (is (str/includes? runbook "direct `run.app` URL is not a public fallback"))))

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
    (is (str/includes? workflow
                       "-target=module.application.google_cloud_run_v2_job.renderer"))
    (is (str/includes? (slurp "infra/dev/main.tf")
                       "name  = \"GOOGLE_CLOUD_PROJECT\""))
    (is (str/includes? workflow "AGG_PUBLIC_BASE_URL=$PUBLIC_BASE_URL"))
    (is (str/includes? workflow "npx --yes firebase-tools@15.24.0 deploy"))
    (is (str/includes? workflow "--only hosting"))
    (is (str/includes? workflow "$PUBLIC_BASE_URL/openapi.yaml"))
    (is (str/includes? workflow "^openapi: 3.1.0$"))
    (is (str/includes? workflow "^content-type: application/yaml; charset=utf-8"))
    (is (str/includes? workflow "--update-secrets"))
    (is (not (str/includes? workflow "client_secret")))
    (is (not (str/includes? workflow "service-account-key")))
    (doseq [reference
            ["actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0 # v7"
             "google-github-actions/auth@7c6bc770dae815cd3e89ee6cdf493a5fab2cc093 # v3"
             "google-github-actions/setup-gcloud@aa5489c8933f4cc7a4f7d45035b3b1440c9c10db # v3"
             "hashicorp/setup-terraform@dfe3c3f87815947d99a8997f908cb6525fc44e9e # v4"]]
      (testing reference (is (str/includes? workflow reference))))
    (is (not (re-find #"uses: (?:actions/checkout|google-github-actions/(?:auth|setup-gcloud))@v\u005cd+"
                      workflow)))))

(deftest production-deployer-can-resolve-the-pushed-image-digest
  (let [infrastructure (slurp "infra/dev/main.tf")
        workflow (slurp ".github/workflows/deploy-production.yml")]
    (is (str/includes?
         infrastructure
         "role    = \"roles/containeranalysis.occurrences.viewer\""))
    (is (str/includes?
         workflow
         "gcloud artifacts docker images describe \"$IMAGE_TAG\""))))

(deftest api-and-overlay-have-distinct-terraform-owned-service-envelopes
  (let [shared (slurp "infra/dev/main.tf")
        production (slurp "infra/prod/main.tf")
        overlay (or (config-section
                     shared
                     "resource \"google_cloud_run_v2_service\" \"overlay\""
                     "resource \"google_cloud_run_v2_service\" \"api\"")
                    "")
        api (or (config-section
                 shared
                 "resource \"google_cloud_run_v2_service\" \"api\""
                 "resource \"google_cloud_run_v2_job\" \"renderer\"")
                "")]
    (doseq [service [api overlay]]
      (is (str/includes? service "min_instance_count = 0"))
      (is (str/includes? service "image = var.renderer_image"))
      (is (str/includes? service
                         "service_account                  = google_service_account.api.email"))
      (is (str/includes? service "cpu_idle          = true"))
      (is (str/includes? service "startup_cpu_boost = true")))
    (is (str/includes? api "name                = \"agg-api\""))
    (is (not (str/includes? api "EXECUTION_ENVIRONMENT_GEN2")))
    (is (str/includes? api "max_instance_count = 2"))
    (is (str/includes? api "max_instance_request_concurrency = 80"))
    (is (str/includes? api "timeout                          = \"300s\""))
    (is (re-find #"cpu\s*=\s*\"1\"" api))
    (is (re-find #"memory\s*=\s*\"512Mi\"" api))
    (is (str/includes? overlay "name                = \"agg-overlay\""))
    (is (str/includes? overlay
                       "execution_environment            = \"EXECUTION_ENVIRONMENT_GEN2\""))
    (is (str/includes? overlay "max_instance_count = 2"))
    (is (str/includes? overlay "max_instance_request_concurrency = 1"))
    (is (str/includes? overlay "timeout                          = \"3600s\""))
    (is (str/includes? overlay "name  = \"AGG_SERVICE_PROFILE\""))
    (is (str/includes? overlay "value = \"overlay\""))
    (is (re-find #"cpu\s*=\s*\"8\"" overlay))
    (is (re-find #"memory\s*=\s*\"32Gi\"" overlay))
    (is (re-find #"(?s)import\s*\{.*?to\s*=\s*module\.application\.google_cloud_run_v2_service\.api"
                 production))
    (is (re-find #"import_api_service\s*=\s*false" production))
    (is (not (re-find #"to\s*=\s*module\.application\.google_cloud_run_v2_service\.overlay"
                      production)))))

(deftest container-smoke-exercises-api-and-overlay-profiles-separately
  (let [smoke (slurp "test/container_smoke.sh")]
    (is (str/includes? smoke "api_container_id="))
    (is (str/includes? smoke "overlay_container_id="))
    (is (str/includes? smoke "-e AGG_SERVICE_PROFILE=overlay"))
    (is (str/includes? smoke "$api_host_port/v1/preview"))
    (is (str/includes? smoke "$overlay_host_port/v1/overlay"))))

(deftest production-release-verifies-private-services-before-targeted-reconciliation-and-promotion
  (let [workflow (slurp ".github/workflows/deploy-production.yml")
        terraform-init (str/index-of workflow
                                     "terraform -chdir=infra/prod init")
        overlay-bootstrap (str/index-of workflow
                                        "Ensure Terraform-owned overlay service exists")
        api-deploy (str/index-of workflow "Deploy private API candidate")
        overlay-deploy (str/index-of workflow "Deploy private overlay candidate")
        private-verification (str/index-of workflow
                                           "Verify private API and overlay candidates")
        terraform-apply (str/index-of workflow
                                      "Reconcile production runtimes through Terraform")
        reconciled-verification (str/index-of workflow
                                              "Verify reconciled private services")
        public-ingress (str/index-of workflow "Restore public invokers")
        firebase-deploy (str/index-of workflow "Publish pinned Firebase Hosting routes")
        api-candidate (or (config-section workflow
                                          "- name: Deploy private API candidate"
                                          "- name: Deploy private overlay candidate") "")
        overlay-candidate (or (config-section workflow
                                              "- name: Deploy private overlay candidate"
                                              "- name: Execute isolated renderer smoke") "")]
    (is (str/includes? workflow
                       "terraform -chdir=infra/prod init -input=false"))
    (doseq [argument ["--cpu 1" "--memory 512Mi" "--concurrency 80"
                      "--timeout 300" "--cpu-boost"
                      "--min 0" "--max 2" "--no-allow-unauthenticated"]]
      (testing (str "API " argument)
        (is (str/includes? api-candidate argument))))
    (doseq [argument ["--cpu 8" "--memory 32Gi" "--concurrency 1"
                      "--timeout 3600" "--execution-environment gen2"
                      "--cpu-boost" "--min 0" "--max 2"
                      "--no-allow-unauthenticated"]]
      (testing (str "overlay " argument)
        (is (str/includes? overlay-candidate argument))))
    (is (str/includes? api-candidate "--image \"$IMAGE_DIGEST\""))
    (is (str/includes? overlay-candidate "--image \"$IMAGE_DIGEST\""))
    (is (str/includes? overlay-candidate "--service-account \"$API_SERVICE_ACCOUNT\""))
    (is (str/includes? workflow "AGG_SERVICE_PROFILE=api"))
    (is (str/includes? workflow "AGG_SERVICE_PROFILE=overlay"))
    (is (str/includes?
         workflow
         "X-Serverless-Authorization: Bearer $OVERLAY_RUN_ID_TOKEN"))
    (is (str/includes? workflow
                       "-var=\"renderer_image=$IMAGE_DIGEST\""))
    (is (str/includes? workflow
                       "-var=\"api_service_url=$CLOUD_RUN_SERVICE_URL\""))
    (is (str/includes? workflow
                       "-target=module.application.google_cloud_run_v2_service.api"))
    (is (str/includes? workflow
                       "-target=module.application.google_cloud_run_v2_service.overlay"))
    (is (str/includes? workflow
                       "-target=module.application.google_cloud_run_v2_job.renderer"))
    (is (= 2 (count (re-seq #"--member=allUsers" workflow))))
    (doseq [position [terraform-init overlay-bootstrap api-deploy overlay-deploy
                      private-verification terraform-apply
                      reconciled-verification public-ingress firebase-deploy]]
      (is (number? position)))
    (when (every? number? [terraform-init overlay-bootstrap api-deploy
                           overlay-deploy private-verification terraform-apply
                           reconciled-verification public-ingress
                           firebase-deploy])
      (is (< terraform-init overlay-bootstrap api-deploy overlay-deploy
             private-verification terraform-apply reconciled-verification
             public-ingress firebase-deploy)))
    (is (not (str/includes? workflow
                            "gcloud run jobs update \"$DURABLE_JOB\"")))))

(deftest production-release-validates-the-picker-key-before-deploying
  (let [workflow (slurp ".github/workflows/deploy-production.yml")
        key-check (str/index-of workflow
                                "Verify production Picker API key")
        private-deploy (str/index-of workflow "Deploy private API candidate")]
    (is (number? key-check))
    (is (number? private-deploy))
    (when (and (number? key-check) (number? private-deploy))
      (is (< key-check private-deploy)))
    (doseq [check ["gcloud secrets versions access latest"
                   "--secret=picker-api-key"
                   "https://apikeys.googleapis.com/v2/keys:lookupKey"
                   "projects/$PROJECT_NUMBER/locations/global/keys/"
                   "picker.googleapis.com"
                   "--format='json(restrictions)'"
                   "(.restrictions | keys | sort) == [\"apiTargets\"]"]]
      (testing check (is (str/includes? workflow check))))
    (is (str/includes? workflow "--data-urlencode 'keyString@-'"))
    (is (str/includes? workflow "key_resource"))
    (is (not (str/includes? workflow "echo \"$picker_key\"")))))

(deftest production-deployer-can-validate-picker-secret-metadata
  (let [shared (slurp "infra/dev/main.tf")]
    (is (str/includes? shared "apikeys.googleapis.com"))
    (is (str/includes? shared "roles/serviceusage.apiKeysViewer"))
    (is (str/includes? shared
                       "resource \"google_secret_manager_secret_iam_member\" \"deployer_picker_access\""))))

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
                  "/v1/auth/drive/callback:" "/v1/drive/picker:"
                  "/v1/drive/picker/diagnostic:"]]
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

(deftest dedicated-overlay-operation-is-cost-and-hosting-timeout-aware
  (let [runbook (slurp "docs/production-runbook.md")
        infrastructure (slurp "infra/README.md")
        sizing-adr (slurp
                    "docs/adr/0003-bounded-prores-rendering-and-cloud-run-sizing.md")]
    (doseq [statement ["`agg-api` remains at 1 vCPU and 512 MiB"
                       "`agg-overlay` uses 8 vCPU and 32 GiB"
                       "request-based billing" "minimum instance count of zero"
                       "concurrency of one"
                       "resource.labels.service_name=\"agg-overlay\""
                       "60 seconds" "HTTP 504"]]
      (testing statement
        (is (str/includes? runbook statement))))
    (is (re-find #"Only\s+exact\s+`/v1/overlay` requests use `agg-overlay`"
                 infrastructure))
    (is (re-find #"separate\s+`agg-overlay` Cloud Run\s+service" sizing-adr))
    (is (not (str/includes?
              sizing-adr
              "Sizing the API for the maximum synchronous contract")))))

(deftest production-release-decision-is-contextual-and-discoverable
  (let [adr (slurp "docs/adr/0011-automatic-production-deployment.md")
        context-map (slurp "CONTEXT-MAP.md")]
    (doseq [decision ["Production deployment" "protected `main`"
                      "Branch protection" "Rollback"]]
      (testing decision (is (str/includes? adr decision))))
    (is (str/includes? context-map "infra/prod/"))
    (is (str/includes? context-map "docs/openapi.yaml"))
    (is (str/includes? context-map "docs/production-runbook.md"))))
