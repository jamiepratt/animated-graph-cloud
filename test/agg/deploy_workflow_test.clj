(ns agg.deploy-workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private workflow (slurp ".github/workflows/deploy.yml"))
(def ^:private dockerfile (slurp "Dockerfile"))
(def ^:private terraform (slurp "infra/dev/main.tf"))
(def ^:private terraform-variables (slurp "infra/dev/variables.tf"))
(def ^:private production-workflow
  (slurp ".github/workflows/deploy-production.yml"))
(def ^:private production-terraform (slurp "infra/prod/main.tf"))
(def ^:private production-terraform-versions
  (slurp "infra/prod/versions.tf"))
(def ^:private cloud-spike (slurp "script/run_cloud_spike.sh"))

(defn- workflow-section [start-marker end-marker]
  (let [start (str/index-of workflow start-marker)
        end (and start (str/index-of workflow end-marker start))]
    (when (and start end)
      (subs workflow start end))))

(defn- production-workflow-section [start-marker end-marker]
  (let [start (str/index-of production-workflow start-marker)
        end (and start
                 (str/index-of production-workflow end-marker start))]
    (when (and start end)
      (subs production-workflow start end))))

(defn- production-resource-section [resource-type resource-name]
  (let [start-marker (str "resource \"" resource-type "\" \""
                          resource-name "\"")
        start (str/index-of production-terraform start-marker)
        end (and start
                 (str/index-of production-terraform
                               "\nresource \""
                               (+ start (count start-marker))))]
    (when start
      (subs production-terraform start (or end (count production-terraform))))))

(defn- production-trigger-map [resource-name]
  (some->> (production-resource-section "time_sleep" resource-name)
           (re-seq #"(?m)^    ([a-z_]+)\s+=\s+([^\n]+)$")
           (map (fn [[_ trigger value]] [trigger value]))
           (into {})))

(deftest docker-build-includes-runtime-resources
  (is (str/includes? dockerfile "COPY resources ./resources"))
  (is (str/includes? dockerfile "RUN clojure -T:build uber")))

(deftest docker-build-includes-selected-source-preview-media-capabilities
  (let [runtime-start (str/last-index-of dockerfile "\nFROM ")
        ffmpeg-builder (subs dockerfile 0 runtime-start)
        runtime (subs dockerfile runtime-start)]
    (is (str/includes? ffmpeg-builder "zlib1g-dev"))
    (is (str/includes? ffmpeg-builder "--enable-zlib"))
    (is (str/includes? dockerfile "--enable-muxer=image2pipe,mov,mp4"))
    (is (str/includes? dockerfile "--enable-encoder=aac,libx264,png,prores_ks"))
    (doseq [filter-name ["hstack" "select" "setpts" "split" "volume"]]
      (is (re-find (re-pattern (str "--enable-filter=[^\\n]*\\b"
                                    filter-name "\\b"))
                   ffmpeg-builder)
          (str "selected-source Preview requires FFmpeg filter " filter-name)))
    (is (not (str/includes? runtime "zlib1g-dev")))
    (is (not (str/includes? runtime "apt-get install")))))

(deftest renderer-job-pins-the-runtime-project
  (is (str/includes? terraform
                     "name  = \"GOOGLE_CLOUD_PROJECT\""))
  (is (str/includes? terraform
                     "value = var.project_id")))

(deftest private-health-probe-uses-an-audience-correct-wif-id-token
  (is (str/includes? workflow "token_format: id_token"))
  (is (str/includes? workflow
                     "id_token_audience: ${{ steps.service-url.outputs.url }}"))
  (is (str/includes? workflow
                     "CLOUD_RUN_ID_TOKEN: ${{ steps.health-auth.outputs.id_token }}"))
  (is (not (str/includes? workflow "gcloud auth print-identity-token"))))

(deftest development-confirms-resend-before-publishing-an-application-image
  (let [secret-check (str/index-of workflow
                                   "Verify enabled development Resend secret version")
        image-push (str/index-of workflow
                                 "Push and resolve immutable image digest")
        durable-promotion (str/index-of workflow "Promote durable renderer")
        api-deploy (str/index-of workflow "Deploy private API service")
        checkpoint (or (workflow-section
                        "- name: Verify enabled development Resend secret version"
                        "- id: image") "")]
    (doseq [position [secret-check image-push durable-promotion api-deploy]]
      (is (number? position)))
    (when (every? number? [secret-check image-push durable-promotion api-deploy])
      (is (< secret-check image-push))
      (is (< secret-check durable-promotion))
      (is (< secret-check api-deploy)))
    (is (str/includes? checkpoint
                       "gcloud secrets versions describe latest"))
    (is (str/includes? checkpoint "--secret=resend-api-key"))
    (is (str/includes? checkpoint "ENABLED"))
    (is (str/includes? checkpoint "workflow_dispatch"))
    (is (not (str/includes? checkpoint
                            "gcloud secrets versions access latest")))))

(deftest development-rolls-back-a-partial-api-renderer-promotion
  (let [image-push (str/index-of workflow
                                 "Push and resolve immutable image digest")
        previous-release (str/index-of workflow
                                       "Capture current API and renderer release")
        durable-promotion (str/index-of workflow
                                        "Promote durable renderer")
        api-deploy (str/index-of workflow "Deploy private API service")
        image-verification (str/index-of workflow
                                         "Verify API and durable renderer image parity")
        api-activation (str/index-of workflow "Activate verified API revision")
        rollback (str/index-of workflow
                               "Restore matched previous API and renderer release")
        smoke-execution (str/index-of workflow
                                      "Deploy and execute renderer smoke job")
        lifecycle-activation (str/index-of workflow
                                           "Enable authenticated durable job lifecycle")
        previous-section (or (workflow-section
                              "name: Capture current API and renderer release"
                              "name: Deploy private API service") "")
        durable-section (or (workflow-section
                             "name: Promote durable renderer"
                             "name: Verify API and durable renderer image parity") "")
        api-section (or (workflow-section
                         "name: Deploy private API service"
                         "name: Promote durable renderer") "")
        verification-section (or (workflow-section
                                  "name: Verify API and durable renderer image parity"
                                  "name: Activate verified API revision") "")
        activation-section (or (workflow-section
                                "name: Activate verified API revision"
                                "- name: Restore matched previous API and renderer release") "")
        rollback-section (or (workflow-section
                              "- name: Restore matched previous API and renderer release"
                              "- name: Deploy and execute renderer smoke job") "")]
    (is (str/includes? workflow "name: Deploy smoke path"))
    (is (str/includes? workflow "branches: [main, dev]"))
    (is (str/includes? workflow "group: development-deployment"))
    (is (str/includes? workflow "ref: ${{ github.sha }}"))
    (is (str/includes? workflow "DURABLE_JOB: agg-renderer"))
    (is (str/includes? workflow "SMOKE_JOB: agg-renderer-smoke"))
    (doseq [position [image-push previous-release api-deploy durable-promotion
                      image-verification api-activation rollback smoke-execution
                      lifecycle-activation]]
      (is (number? position)))
    (when (every? number? [image-push previous-release api-deploy durable-promotion
                           image-verification api-activation rollback smoke-execution
                           lifecycle-activation])
      (is (< image-push previous-release api-deploy durable-promotion
             image-verification api-activation rollback smoke-execution
             lifecycle-activation)))
    (is (str/includes? workflow
                       "echo \"uri=$REGION-docker.pkg.dev/$PROJECT_ID/$REPOSITORY/animated-graph-cloud@$digest\""))
    (is (str/includes? previous-section "select(.percent == 100"))
    (is (str/includes? previous-section
                       "echo \"api_revision=$api_revision\" >>\"$GITHUB_OUTPUT\""))
    (is (str/includes? previous-section
                       "echo \"renderer_image=$renderer_image\" >>\"$GITHUB_OUTPUT\""))
    (is (str/includes? durable-section
                       "gcloud run jobs update \"$DURABLE_JOB\""))
    (is (str/includes? durable-section "--image \"$IMAGE_DIGEST\""))
    (doseq [preserved-setting ["--service-account" "--args" "--tasks"
                               "--max-retries" "--task-timeout" "--cpu"
                               "--memory" "--update-env-vars"
                               "--update-secrets"]]
      (is (not (str/includes? durable-section preserved-setting))))
    (is (str/includes? api-section "--image \"$IMAGE_DIGEST\""))
    (is (str/includes? api-section "--no-traffic"))
    (is (str/includes? verification-section
                       "test \"$service_image\" = \"$durable_image\""))
    (is (str/includes? verification-section
                       "test \"$service_image\" = \"$IMAGE_DIGEST\""))
    (is (str/includes? activation-section
                       "gcloud run services update-traffic \"$SERVICE\""))
    (is (str/includes? activation-section "--to-latest"))
    (is (str/includes? rollback-section "failure()"))
    (is (str/includes? rollback-section
                       "steps.durable-promotion.outcome != 'skipped'"))
    (is (str/includes? rollback-section
                       "steps.api-activation.outcome != 'success'"))
    (is (str/includes? rollback-section
                       "--image \"${{ steps.previous-release.outputs.renderer_image }}\""))
    (is (str/includes? rollback-section
                       "--to-revisions \"${{ steps.previous-release.outputs.api_revision }}=100\""))))

(deftest terraform-locks-the-measured-renderer-job-shape
  (is (str/includes? terraform "resource \"google_cloud_run_v2_job\" \"renderer\""))
  (is (str/includes? terraform "name                = \"agg-renderer\""))
  (is (str/includes? terraform "cpu    = \"8\""))
  (is (str/includes? terraform "memory = \"32Gi\""))
  (is (str/includes? terraform "max_retries           = 0"))
  (is (str/includes? terraform "timeout               = \"3600s\""))
  (is (str/includes? terraform
                     "execution_environment = \"EXECUTION_ENVIRONMENT_GEN2\"")))

(deftest durable-renderer-pins-the-deployed-operations-image
  (is (str/includes?
       terraform-variables
       "europe-central2-docker.pkg.dev/animated-graph-cloud-jp/containers/animated-graph-cloud@sha256:1f6a8532e432502af5d9a4eb72f48d07abf79634334dd52d1ef38227f9bfa3f7")))

(deftest terraform-locks-durable-dispatch-and-retention
  (is (str/includes? terraform
                     "resource \"google_cloud_tasks_queue\" \"render\""))
  (is (str/includes? terraform "max_concurrent_dispatches = 5"))
  (is (str/includes? terraform
                     "resource \"google_firestore_field\" \"job_expiry\""))
  (is (str/includes? terraform "ttl_config {}"))
  (is (str/includes? terraform "roles/cloudtasks.enqueuer"))
  (is (str/includes? terraform "roles/run.jobsExecutorWithOverrides"))
  (is (str/includes? terraform "roles/run.invoker")))

(deftest reconciliation-can-list-executions-without-broad-run-administration
  (is (str/includes? terraform
                     "resource \"google_project_iam_custom_role\" \"api_execution_reader\""))
  (is (str/includes? terraform "permissions = [\"run.executions.get\", \"run.executions.list\"]"))
  (is (str/includes? terraform
                     "role    = google_project_iam_custom_role.api_execution_reader.id"))
  (is (str/includes? terraform
                     "member  = \"serviceAccount:${google_service_account.api.email}\"")))

(deftest terraform-locks-the-full-retention-and-reconciliation-contract
  (is (str/includes? terraform "age = 1"))
  (is (str/includes? terraform
                     "resource \"google_cloud_scheduler_job\" \"reconcile\""))
  (is (str/includes? terraform
                     "uri         = \"${var.api_service_url}/internal/v1/jobs/reconcile\""))
  (is (re-find #"schedule\s*=\s*\"\*/5 \* \* \* \*\"" terraform))
  (is (str/includes? terraform "oidc_token")))

(deftest reconciliation-uses-a-dedicated-least-privilege-identity
  (is (str/includes? terraform
                     "resource \"google_service_account\" \"scheduler\""))
  (is (str/includes? terraform "account_id   = \"agg-scheduler\""))
  (is (str/includes? terraform
                     "service_account_email = google_service_account.scheduler.email"))
  (is (str/includes? terraform
                     "service_account_id = google_service_account.scheduler.name"))
  (is (not (str/includes?
            terraform
            "service_account_id = google_service_account.tasks.name\n  role               = \"roles/iam.serviceAccountTokenCreator\"\n  member             = \"serviceAccount:service-${data.google_project.current.number}@gcp-sa-cloudscheduler.iam.gserviceaccount.com\"")))
  (is (str/includes? workflow
                     "AGG_SCHEDULER_SERVICE_ACCOUNT=agg-scheduler@")))

(deftest configured-budget-is-both-alerted-and-enforced-at-admission
  (is (str/includes? terraform
                     "resource \"google_billing_budget\" \"development\""))
  (is (str/includes? terraform "currency_code = \"PLN\""))
  (is (str/includes? terraform "tostring(var.monthly_budget_pln)"))
  (is (str/includes? terraform-variables "variable \"monthly_budget_pln\""))
  (is (re-find #"variable \"monthly_budget_pln\"[\s\S]*?default\s*=\s*400"
               terraform-variables))
  (doseq [threshold ["threshold_percent = 0.5"
                     "threshold_percent = 0.8"
                     "threshold_percent = 1.0"]]
    (is (str/includes? terraform threshold)))
  (is (str/includes? workflow "MONTHLY_BUDGET_MINOR_UNITS: \"40000\""))
  (is (str/includes? workflow "PREVIEW_RESERVATION_MINOR_UNITS: \"125\""))
  (is (str/includes? workflow "RENDER_RESERVATION_MINOR_UNITS: \"125\""))
  (is (str/includes? workflow
                     "--remove-env-vars \"AGG_MONTHLY_BUDGET_CENTS,AGG_PREVIEW_RESERVATION_CENTS,AGG_RENDER_RESERVATION_CENTS\""))
  (is (str/includes? workflow
                     "--update-env-vars \"AGG_JOB_LIFECYCLE_ENABLED=true"))
  (is (str/includes? workflow
                     "AGG_MONTHLY_BUDGET_MINOR_UNITS=$MONTHLY_BUDGET_MINOR_UNITS"))
  (is (str/includes? workflow
                     "AGG_PREVIEW_RESERVATION_MINOR_UNITS=$PREVIEW_RESERVATION_MINOR_UNITS"))
  (is (str/includes? workflow
                     "AGG_RENDER_RESERVATION_MINOR_UNITS=$RENDER_RESERVATION_MINOR_UNITS"))
  (is (not (str/includes? terraform-variables "monthly_budget_usd")))
  (is (= 1 (count (re-seq #"AGG_MONTHLY_BUDGET_CENTS" workflow))))
  (is (= 1 (count (re-seq #"AGG_PREVIEW_RESERVATION_CENTS" workflow))))
  (is (= 1 (count (re-seq #"AGG_RENDER_RESERVATION_CENTS" workflow))))
  (is (not (str/includes? workflow "--set-env-vars"))))

(deftest logs-metrics-dashboard-and-alerts-cover-the-operating-envelope
  (doseq [metric ["queue_age_ms" "render_failures" "stale_leases"
                  "drive_reauthorization" "budget_admission_rejections"]]
    (is (re-find (re-pattern
                  (str "name\\s*=\\s*\\\"animated_graph_cloud/"
                       metric "\\\""))
                 terraform)))
  (is (str/includes? terraform "EXTRACT(jsonPayload.queueAgeMs)"))
  (is (str/includes? terraform
                     "resource \"google_monitoring_dashboard\" \"operations\""))
  (is (str/includes? terraform
                     "resource \"google_monitoring_notification_channel\" \"owner_email\""))
  (is (= 8 (count (re-seq #"notification_channels\s*=" terraform))))
  (doseq [signal ["Queue age" "Render failures" "Memory utilization"
                  "Stale leases" "Drive reauthorization"
                  "Budget admission"]]
    (is (str/includes? terraform signal)))
  (doseq [alert ["queue_age" "render_failures" "memory_utilization"
                 "stale_leases" "drive_reauthorization"
                 "budget_admission" "backlog_depth"]]
    (is (str/includes? terraform
                       (str "resource \"google_monitoring_alert_policy\" \""
                            alert "\"")))))

(deftest sustained-cloud-tasks-backlog-is-visible-without-api-dispatch
  (is (str/includes? terraform
                     "cloudtasks.googleapis.com/queue/depth"))
  (is (str/includes? terraform "resource.type=\\\"cloud_tasks_queue\\\""))
  (is (str/includes? terraform "resource.label.queue_id=\\\"agg-render\\\""))
  (is (str/includes? terraform "display_name = \"Sustained Cloud Tasks backlog\""))
  (is (str/includes? terraform "duration        = \"300s\""))
  (is (str/includes? terraform "title = \"Cloud Tasks backlog depth\"")))

(deftest operations-dashboard-matches-monitoring-canonical-json
  (is (= 8 (count (re-seq #"plotType\s*=\s*\"LINE\"" terraform))))
  (is (= 8 (count (re-seq #"targetAxis\s*=\s*\"Y1\"" terraform))))
  (is (zero? (count (re-seq #"xPos\s*=\s*0" terraform))))
  (is (zero? (count (re-seq #"yPos\s*=\s*0" terraform)))))

(deftest delivery-enables-the-api-with-its-own-authenticated-task-audience
  (is (str/includes? workflow "AGG_JOB_LIFECYCLE_ENABLED=true"))
  (is (str/includes? workflow
                     "AGG_DISPATCHER_URL=$CLOUD_RUN_SERVICE_URL"))
  (is (str/includes? workflow "AGG_TASKS_SERVICE_ACCOUNT=agg-tasks@")))

(deftest oauth-and-drive-runtime-use-kms-and-secret-manager-with-least-privilege
  (is (str/includes? terraform "roles/cloudkms.cryptoKeyEncrypterDecrypter"))
  (is (= 2 (count (re-seq #"roles/cloudkms\.cryptoKeyEncrypterDecrypter"
                          terraform))))
  (is (str/includes? terraform "roles/secretmanager.secretAccessor"))
  (is (str/includes? terraform "AGG_OAUTH_CLIENT_CREDENTIALS"))
  (is (str/includes? terraform "AGG_DRIVE_DELIVERY_ENABLED"))
  (is (str/includes? workflow
                     "AGG_OAUTH_CLIENT_CREDENTIALS=oauth-client-secret:latest"))
  (is (str/includes? workflow "AGG_SESSION_KEY=session-key:latest"))
  (is (str/includes? workflow "AGG_PICKER_API_KEY=picker-api-key:latest"))
  (is (str/includes? workflow
                     "AGG_TOKEN_HASH_PEPPER=token-hash-pepper:latest"))
  (is (= 2 (count (re-seq #"AGG_TOKEN_HASH_PEPPER=token-hash-pepper:latest"
                          workflow))))
  (is (str/includes? terraform
                     "resource \"google_secret_manager_secret_iam_member\" \"api_token_hash_access\"")))

(deftest youtube-metadata-key-is-terraform-managed-api-only-and-guarded-before-promotion
  (doseq [contract ["youtube.googleapis.com" "youtube-api-key"
                    "api_youtube_access" "deployer_youtube_access"]]
    (is (str/includes? terraform contract) contract))
  (doseq [deployment [workflow production-workflow]]
    (is (str/includes? deployment "Verify YouTube metadata API key"))
    (is (str/includes? deployment "AGG_YOUTUBE_API_KEY=youtube-api-key:latest"))
    (is (str/includes? deployment "--secret=youtube-api-key"))
    (is (str/includes? deployment "youtube.googleapis.com"))
    (is (str/includes? deployment "https://apikeys.googleapis.com/v2/keys:lookupKey"))
    (is (str/includes? deployment "YouTube metadata bootstrap required")))
  (let [bootstrap (slurp ".github/workflows/bootstrap-youtube-metadata.yml")]
    (is (str/includes? bootstrap "workflow_dispatch"))
    (is (str/includes? bootstrap "confirm_bootstrap"))
    (is (str/includes? bootstrap "youtube-api-key"))
    (is (str/includes? bootstrap "google_secret_manager_secret.application[\\\"youtube-api-key\\\"]"))
    (is (not (str/includes? bootstrap "gcloud services api-keys create")))
    (is (not (str/includes? bootstrap "secrets versions add"))))
  (let [runbook (slurp "docs/production-runbook.md")]
    (is (not (str/includes? runbook "gcloud services api-keys create")))
    (is (not (str/includes? runbook "gcloud services api-keys get-key-string")))
    (is (str/includes? runbook "fields=name"))
    (is (str/includes? runbook "fields=done,error"))
    (is (str/includes? runbook "fields=response/keyString"))
    (is (str/includes? runbook
                       "jq -er '.response.keyString' | gcloud secrets versions add"))))

(deftest youtube-key-lookup-iam-is-applied-before-validation
  (let [bootstrap (slurp ".github/workflows/bootstrap-youtube-metadata.yml")
        bootstrap-apply (str/index-of bootstrap
                                      "terraform -chdir=\"$TF_DIRECTORY\" apply")
        bootstrap-plan-guard (str/index-of bootstrap
                                           "YouTube metadata bootstrap plan blocked")
        bootstrap-checkpoint (str/index-of bootstrap
                                           "Stop before the mandatory operator checkpoint")
        production-apply (str/index-of production-workflow
                                       "terraform -chdir=infra/prod apply")
        production-validation (str/index-of production-workflow
                                            "Verify YouTube metadata API key")]
    (is (re-find
         #"(?s)resource \"google_project_iam_member\" \"deployer_picker_api_keys_viewer\".*?role\s+=\s+\"roles/serviceusage.apiKeysViewer\".*?member\s+=\s+\"serviceAccount:\$\{google_service_account.deployer.email\}\""
         terraform))
    (doseq [target ["google_project_service.required[\\\"apikeys.googleapis.com\\\"]"
                    "google_project_iam_member.deployer_picker_api_keys_viewer"]]
      (is (str/includes? bootstrap target) target))
    (doseq [guard ["terraform -chdir=\"$TF_DIRECTORY\" show -json"
                   "unexpected_changes"
                   "destructive_changes"
                   "YouTube metadata bootstrap plan blocked"]]
      (is (str/includes? bootstrap guard) guard))
    (is (str/includes? workflow
                       "Apply the reviewed guarded YouTube metadata Terraform bootstrap with fresh authority"))
    (doseq [position [bootstrap-plan-guard bootstrap-apply bootstrap-checkpoint
                      production-apply production-validation]]
      (is (number? position)))
    (when (every? number? [bootstrap-plan-guard bootstrap-apply
                           bootstrap-checkpoint])
      (is (< bootstrap-plan-guard bootstrap-apply bootstrap-checkpoint)))
    (when (every? number? [production-apply production-validation])
      (is (< production-apply production-validation)))))

(deftest public-ingress-is-enabled-only-after-app-and-task-auth-configuration
  (let [auth-index (str/index-of workflow "AGG_AUTH_ENABLED=true")
        public-index (str/index-of workflow "--member=allUsers")]
    (is (number? auth-index))
    (is (number? public-index))
    (is (< auth-index public-index))
    (is (str/includes? workflow "AGG_PUBLIC_BASE_URL=$CLOUD_RUN_SERVICE_URL"))
    (is (= 2 (count (re-seq #"AGG_OWNER_EMAIL=\$OWNER_EMAIL" workflow))))
    (is (str/includes? workflow "AGG_PICKER_APP_ID=$PROJECT_NUMBER"))))

(deftest cloud-stage-reports-survive-an-uberjar-build-and-support-resume
  (is (str/includes? cloud-spike ".spike/cloud/$run_id"))
  (is (not (str/includes? cloud-spike "target/spike")))
  (is (str/includes? cloud-spike "PRIOR_ESTIMATED_COST_USD"))
  (is (str/includes? cloud-spike "resume_stage")))

(deftest production-private-preview-infrastructure-is-isolated-and-private
  (doseq [name ["animated-graph-cloud-prod-jp-production-private-previews"
                "agg-production-private-preview"
                "agg-prod-preview-tasks"
                "agg-prod-preview-worker"]]
    (is (str/includes? production-terraform name) name))
  (doseq [proto-name ["name     = \"agg-derivative-preview\""
                      "account_id   = \"agg-derivative-tasks\""
                      "account_id   = \"agg-derivative-worker\""
                      "name                        = \"${local.project_id}-derivative-previews\""]]
    (is (not (str/includes? production-terraform proto-name)) proto-name))
  (is (str/includes?
       production-terraform
       "resource \"google_storage_bucket\" \"production_private_previews\""))
  (is (str/includes? production-terraform
                     "public_access_prevention    = \"enforced\""))
  (is (str/includes? production-terraform
                     "uniform_bucket_level_access = true"))
  (is (str/includes? production-terraform "force_destroy               = false"))
  (is (str/includes? production-terraform "age = 1"))
  (is (str/includes? production-terraform "prevent_destroy = true")))

(deftest production-private-preview-capacity-retry-and-retention-match-contract
  (is (str/includes?
       production-terraform
       "resource \"google_cloud_tasks_queue\" \"production_private_preview\""))
  (is (str/includes? production-terraform
                     "max_concurrent_dispatches = 1"))
  (is (str/includes? production-terraform
                     "max_dispatches_per_second = 1"))
  (is (str/includes? production-terraform "max_retry_duration = \"3600s\""))
  (is (str/includes?
       production-terraform
       "resource \"google_cloud_run_v2_job\" \"production_private_preview\""))
  (doseq [contract ["image = var.renderer_image"
                    "args  = [\"clojure.main\", \"-m\", \"agg.derivative.worker\"]"
                    "task_count  = 1"
                    "max_retries           = 0"
                    "timeout               = \"900s\""
                    "cpu    = \"4\""
                    "memory = \"4Gi\""
                    "AGG_DERIVATIVE_ENVIRONMENT"
                    "production-private-preview-contract-v1"
                    "h264-aac-1080p25-v1"
                    "AGG_DERIVATIVE_ASSET_TTL_SECONDS"
                    "86400"]]
    (is (str/includes? production-terraform contract) contract))
  (doseq [collection
          ["production-derivative-preparation-jobs-v1"
           "production-derivative-preview-cache-v1"
           "production-derivative-preparation-idempotency-v1"
           "production-derivative-active-jobs-v1"]]
    (is (str/includes? production-terraform collection) collection))
  (is (str/includes? production-terraform
                     "resource \"google_firestore_field\" \"production_private_preview_expiry\""))
  (is (str/includes? production-terraform "ttl_config {}")))

(deftest production-private-preview-worker-can-initialize-membership
  (is (re-find
       #"(?s)production_private_preview_worker_environment = merge\(\{.*?AGG_OWNER_EMAIL\s+=\s+local\.owner_email.*?\}, local\.production_private_preview_contract\)"
       production-terraform)
      "the worker membership directory requires the production owner"))

(deftest production-private-preview-iam-metrics-and-alerts-are-owned
  (doseq [role ["roles/storage.objectViewer"
                "roles/storage.objectUser"
                "roles/cloudtasks.enqueuer"
                "roles/cloudtasks.taskDeleter"
                "roles/run.jobsExecutorWithOverrides"
                "roles/run.viewer"
                "roles/run.invoker"
                "roles/datastore.user"
                "roles/cloudkms.cryptoKeyEncrypterDecrypter"
                "roles/secretmanager.secretAccessor"]]
    (is (str/includes? production-terraform role) role))
  (is (str/includes?
       production-terraform
       "service  = \"agg-api\""))
  (is (str/includes? production-terraform
                     "module.application.operations_notification_channel"))
  (is (not (str/includes?
            production-terraform
            "resource \"google_monitoring_notification_channel\" \"production_private_preview_owner\"")))
  (is (str/includes?
       production-terraform
       "paused           = var.private_preview_reconciliation_paused"))
  (doseq [metric ["production_private_preview_latency_ms"
                  "production_private_preview_failures"
                  "production_private_preview_queue_age_ms"
                  "production_private_preview_reserved_minor_units"
                  "production_private_preview_cache_outcomes"
                  "production_private_preview_terminal_reasons"
                  "production_private_preview_verification_failures"
                  "production_private_preview_cancellations"
                  "production_private_preview_infrastructure_failures"]]
    (is (str/includes?
         production-terraform
         (str "resource \"google_logging_metric\" \"" metric "\""))
        metric))
  (doseq [alert ["production_private_preview_latency"
                 "production_private_preview_failures"
                 "production_private_preview_queue_age"
                 "production_private_preview_backlog"
                 "production_private_preview_reserved_cost"
                 "production_private_preview_terminal_reasons"
                 "production_private_preview_cache_outcomes"
                 "production_private_preview_verification_failures"
                 "production_private_preview_cancellations"
                 "production_private_preview_infrastructure_failures"]]
    (is (str/includes?
         production-terraform
         (str "resource \"google_monitoring_alert_policy\" \"" alert "\""))
        alert)))

(deftest production-private-preview-alerts-distinguish-failures-from-spikes
  (doseq [[alert expected-fragments]
          {"production_private_preview_terminal_reasons"
           ["google_logging_metric.production_private_preview_terminal_reasons.name"
            "metric.labels.status=\\\"failed\\\""
            "resource.type=\\\"cloud_run_job\\\""
            "resource.type=\\\"cloud_run_revision\\\""
            "threshold_value = 0"
            "alignment_period   = \"300s\""]
           "production_private_preview_cache_outcomes"
           ["google_logging_metric.production_private_preview_cache_outcomes.name"
            "metric.labels.cache_outcome=\\\"miss\\\""
            "resource.type=\\\"cloud_run_revision\\\""
            "threshold_value = 5"
            "alignment_period     = \"900s\""
            "cross_series_reducer = \"REDUCE_SUM\""]
           "production_private_preview_verification_failures"
           ["google_logging_metric.production_private_preview_verification_failures.name"
            "resource.type=\\\"cloud_run_job\\\""
            "threshold_value = 0"
            "alignment_period   = \"300s\""]
           "production_private_preview_cancellations"
           ["google_logging_metric.production_private_preview_cancellations.name"
            "resource.type=\\\"cloud_run_revision\\\""
            "threshold_value = 5"
            "alignment_period     = \"900s\""
            "cross_series_reducer = \"REDUCE_SUM\""]
           "production_private_preview_infrastructure_failures"
           ["google_logging_metric.production_private_preview_infrastructure_failures.name"
            "resource.type=\\\"cloud_run_job\\\""
            "resource.type=\\\"cloud_run_revision\\\""
            "threshold_value = 0"
            "alignment_period   = \"300s\""]}]
    (let [policy (production-resource-section
                  "google_monitoring_alert_policy"
                  alert)]
      (is (some? policy) alert)
      (doseq [fragment
              (concat
               ["module.application.operations_notification_channel"
                "comparison      = \"COMPARISON_GT\""
                "duration        = \"0s\""
                "per_series_aligner"
                "\"ALIGN_SUM\""
                "time_sleep.production_private_preview_lifecycle_metrics_propagation"]
               expected-fragments)]
        (is (and policy (str/includes? policy fragment))
            (str alert " requires " fragment))))))

(deftest production-private-preview-first-apply-waits-for-propagation
  (is (str/includes? production-terraform-versions
                     "source  = \"hashicorp/time\""))
  (doseq [[wait-name duration]
          [["production_private_preview_worker_iam_propagation" "480s"]
           ["production_private_preview_metrics_propagation" "660s"]
           ["production_private_preview_lifecycle_metrics_propagation" "660s"]]]
    (let [wait-resource
          (production-resource-section "time_sleep" wait-name)]
      (is (some? wait-resource) wait-name)
      (is (and wait-resource
               (str/includes? wait-resource
                              (str "create_duration = \"" duration "\"")))
          wait-name)))
  (is (str/includes?
       (production-resource-section
        "google_cloud_run_v2_job"
        "production_private_preview")
       "time_sleep.production_private_preview_worker_iam_propagation"))
  (is (= {"failures_metric"
          "google_logging_metric.production_private_preview_failures.id"
          "latency_metric"
          "google_logging_metric.production_private_preview_latency_ms.id"
          "queue_age_metric"
          "google_logging_metric.production_private_preview_queue_age_ms.id"
          "reserved_cost_metric"
          "google_logging_metric.production_private_preview_reserved_minor_units.id"}
         (production-trigger-map
          "production_private_preview_metrics_propagation")))
  (is (= {"cache_metric"
          "google_logging_metric.production_private_preview_cache_outcomes.id"
          "terminal_metric"
          "google_logging_metric.production_private_preview_terminal_reasons.id"
          "verification_metric"
          "google_logging_metric.production_private_preview_verification_failures.id"
          "cancellation_metric"
          "google_logging_metric.production_private_preview_cancellations.id"
          "infrastructure_metric"
          "google_logging_metric.production_private_preview_infrastructure_failures.id"}
         (production-trigger-map
          "production_private_preview_lifecycle_metrics_propagation")))
  (doseq [[wait-name alerts]
          {"production_private_preview_metrics_propagation"
           ["production_private_preview_latency"
            "production_private_preview_failures"
            "production_private_preview_queue_age"
            "production_private_preview_reserved_cost"]
           "production_private_preview_lifecycle_metrics_propagation"
           ["production_private_preview_terminal_reasons"
            "production_private_preview_cache_outcomes"
            "production_private_preview_verification_failures"
            "production_private_preview_cancellations"
            "production_private_preview_infrastructure_failures"]}]
    (doseq [alert alerts]
      (let [policy (production-resource-section
                    "google_monitoring_alert_policy"
                    alert)]
        (is (str/includes? policy (str "time_sleep." wait-name)) alert)
        (is (not (str/includes?
                  policy
                  (str "time_sleep."
                       (if (= wait-name
                              "production_private_preview_metrics_propagation")
                         "production_private_preview_lifecycle_metrics_propagation"
                         "production_private_preview_metrics_propagation"))))
            alert)))))

(deftest production-runtime-reconcile-keeps-worker-iam-known
  (let [worker-iam
        (production-resource-section
         "google_service_account_iam_member"
         "production_deployer_uses_private_preview_worker")]
    (is (str/includes?
         worker-iam
         "member             = \"serviceAccount:${local.deployer_service_account}\""))
    (is (str/includes? worker-iam "depends_on = [module.application]"))
    (is (not (str/includes?
              worker-iam
              "data.google_service_account.production_deployer.email")))))

(deftest production-full-reconcile-keeps-runtime-principals-known
  (is (not (str/includes?
            production-terraform
            "data \"google_service_account\" \"production_api\"")))
  (is (not (str/includes?
            production-terraform
            "data \"google_service_account\" \"production_scheduler\"")))
  (is (= 6
         (count
          (re-seq #"member\s*=\s*\"serviceAccount:\$\{local\.api_service_account\}\""
                  production-terraform))))
  (let [scheduler
        (production-resource-section
         "google_cloud_scheduler_job"
         "production_private_preview_reconcile")]
    (is (str/includes?
         scheduler
         "service_account_email = local.scheduler_service_account"))
    (is (str/includes? scheduler "depends_on = [module.application]"))))

(deftest production-workflow-keeps-private-preview-on-the-candidate-release
  (let [terraform-apply
        (str/index-of production-workflow
                      "Plan and apply production Terraform")
        candidate-deploy
        (str/index-of production-workflow "Deploy private API candidate")
        full-reconcile
        (or (production-workflow-section
             "name: Plan and apply production Terraform"
             "name: Verify production Picker API key") "")
        runtime-reconcile
        (or (production-workflow-section
             "name: Reconcile production runtimes through Terraform"
             "name: Verify reconciled private services") "")]
    (is (< terraform-apply candidate-deploy))
    (is (str/includes? production-workflow
                       "PRIVATE_PREVIEW_JOB: agg-production-private-preview"))
    (is (str/includes?
         production-workflow
         "AGG_DERIVATIVE_DISPATCHER_URL=$CLOUD_RUN_SERVICE_URL"))
    (is (not (str/includes? production-workflow "agg-proto")))
    (is (str/includes?
         full-reconcile
         "-var=\"private_preview_reconciliation_paused=true\""))
    (is (str/includes?
         runtime-reconcile
         "-target=google_cloud_run_v2_job.production_private_preview"))
    (is (str/includes?
         runtime-reconcile
         "-target=google_cloud_scheduler_job.production_private_preview_reconcile"))
    (is (str/includes?
         runtime-reconcile
         "-var=\"private_preview_reconciliation_paused=false\""))
    (is (str/includes? runtime-reconcile
                       "-var=\"renderer_image=$IMAGE_DIGEST\""))
    (is (str/includes? runtime-reconcile
                       "terraform -chdir=infra/prod show -json"))
    (is (str/includes? runtime-reconcile
                       "select(.change.actions | index(\"delete\"))"))
    (is (str/includes? runtime-reconcile
                       "Destructive runtime Terraform plan blocked"))
    (is (str/includes? production-workflow
                       "select(.change.actions | index(\"delete\"))"))
    (is (str/includes? production-workflow
                       "import_existing_private_preview"))
    (is (str/includes? production-workflow
                       "Existing private-preview resources require reviewed import"))))
