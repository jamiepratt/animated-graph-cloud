(ns agg.deploy-workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private workflow (slurp ".github/workflows/deploy.yml"))
(def ^:private terraform (slurp "infra/dev/main.tf"))
(def ^:private cloud-spike (slurp "script/run_cloud_spike.sh"))

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

(deftest terraform-locks-the-full-retention-and-reconciliation-contract
  (is (str/includes? terraform "age = 1"))
  (is (str/includes? terraform
                     "resource \"google_cloud_scheduler_job\" \"reconcile\""))
  (is (str/includes? terraform
                     "uri         = \"${var.api_service_url}/internal/v1/jobs/reconcile\""))
  (is (re-find #"schedule\s*=\s*\"\*/5 \* \* \* \*\"" terraform))
  (is (str/includes? terraform "oidc_token")))

(deftest configured-budget-is-both-alerted-and-enforced-at-admission
  (is (str/includes? terraform
                     "resource \"google_billing_budget\" \"development\""))
  (doseq [threshold ["threshold_percent = 0.5"
                     "threshold_percent = 0.8"
                     "threshold_percent = 1.0"]]
    (is (str/includes? terraform threshold)))
  (is (str/includes? workflow "AGG_MONTHLY_BUDGET_CENTS=$MONTHLY_BUDGET_CENTS"))
  (is (str/includes? workflow "AGG_RENDER_RESERVATION_CENTS=$RENDER_RESERVATION_CENTS")))

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
  (is (= 7 (count (re-seq #"notification_channels\s*=" terraform))))
  (doseq [signal ["Queue age" "Render failures" "Memory utilization"
                  "Stale leases" "Drive reauthorization"
                  "Budget admission"]]
    (is (str/includes? terraform signal)))
  (doseq [alert ["queue_age" "render_failures" "memory_utilization"
                 "stale_leases" "drive_reauthorization"
                 "budget_admission"]]
    (is (str/includes? terraform
                       (str "resource \"google_monitoring_alert_policy\" \""
                            alert "\"")))))

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
    (is (str/includes? workflow "AGG_ALLOWED_EMAILS="))
    (is (str/includes? workflow "AGG_PICKER_APP_ID=$PROJECT_NUMBER"))))

(deftest cloud-stage-reports-survive-an-uberjar-build-and-support-resume
  (is (str/includes? cloud-spike ".spike/cloud/$run_id"))
  (is (not (str/includes? cloud-spike "target/spike")))
  (is (str/includes? cloud-spike "PRIOR_ESTIMATED_COST_USD"))
  (is (str/includes? cloud-spike "resume_stage")))
