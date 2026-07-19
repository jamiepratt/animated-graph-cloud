(ns agg.deploy-workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private workflow (slurp ".github/workflows/deploy.yml"))
(def ^:private dockerfile (slurp "Dockerfile"))
(def ^:private terraform (slurp "infra/dev/main.tf"))
(def ^:private terraform-variables (slurp "infra/dev/variables.tf"))
(def ^:private cloud-spike (slurp "script/run_cloud_spike.sh"))

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
  (is (str/includes? workflow "RENDER_RESERVATION_MINOR_UNITS: \"125\""))
  (is (str/includes? workflow
                     "--remove-env-vars \"AGG_MONTHLY_BUDGET_CENTS,AGG_RENDER_RESERVATION_CENTS\""))
  (is (str/includes? workflow
                     "--update-env-vars \"AGG_JOB_LIFECYCLE_ENABLED=true"))
  (is (str/includes? workflow
                     "AGG_MONTHLY_BUDGET_MINOR_UNITS=$MONTHLY_BUDGET_MINOR_UNITS"))
  (is (str/includes? workflow
                     "AGG_RENDER_RESERVATION_MINOR_UNITS=$RENDER_RESERVATION_MINOR_UNITS"))
  (is (not (str/includes? terraform-variables "monthly_budget_usd")))
  (is (= 1 (count (re-seq #"AGG_MONTHLY_BUDGET_CENTS" workflow))))
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
