(ns agg.proto-release-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- read-json [path]
  (json/read-str (slurp path) :key-fn keyword))

(defn- terraform-resource [terraform type name]
  (let [marker (str "resource \"" type "\" \"" name "\"")
        start (str/index-of terraform marker)
        next-resource
        (when start
          (str/index-of terraform "\nresource \"" (+ start (count marker))))]
    (when start
      (subs terraform start (or next-resource (count terraform))))))

(deftest proto-media-gate-installs-ffmpeg-before-running-tests
  (let [workflow (slurp ".github/workflows/deploy-proto.yml")
        proto-tests-start (str/index-of workflow "  proto-tests:")
        deploy-start (str/index-of workflow "\n  deploy:")
        proto-tests
        (when (and proto-tests-start deploy-start)
          (subs workflow proto-tests-start deploy-start))
        install-index (some-> proto-tests
                              (str/index-of
                               "sudo apt-get install --yes ffmpeg"))
        gate-index (some-> proto-tests
                           (str/index-of
                            "Run proto-only validation gate"))]
    (is (string? proto-tests))
    (is (str/includes? proto-tests "sudo apt-get update"))
    (is (number? install-index))
    (is (number? gate-index))
    (when (and (number? install-index) (number? gate-index))
      (is (< install-index gate-index)))))

(deftest derivative-observability-is-terraform-owned-and-applied-before-release
  (let [proto-infra (slurp "infra/proto/main.tf")
        proto-workflow (slurp ".github/workflows/deploy-proto.yml")
        bootstrap (slurp "script/bootstrap_proto_terraform.sh")]
    (doseq [metric
            ["derivative_preparation_latency_ms"
             "derivative_cache_hits"
             "derivative_cache_misses"
             "derivative_failures"
             "derivative_timeouts"
             "derivative_drive_bytes"
             "derivative_output_bytes"
             "derivative_cancellation_lag_ms"
             "derivative_queue_age_ms"
             "derivative_reserved_minor_units"
             "derivative_reservation_rejections"]]
      (is (str/includes?
           proto-infra
           (str "\"alpha_compose_proto/" metric "\""))))
    (doseq [alert
            ["derivative_latency"
             "derivative_cache_ratio"
             "derivative_failures"
             "derivative_timeouts"
             "derivative_drive_bytes"
             "derivative_output_bytes"
             "derivative_cancellation_lag"
             "derivative_queue_age"
             "derivative_backlog_depth"
             "derivative_reservation_rejections"]]
      (is (str/includes?
           proto-infra
           (str "resource \"google_monitoring_alert_policy\" \""
                alert "\""))))
    (doseq [extractor
            ["EXTRACT(jsonPayload.elapsedMs)"
             "EXTRACT(jsonPayload.upstreamBytes)"
             "EXTRACT(jsonPayload.outputBytes)"
             "EXTRACT(jsonPayload.cancellationLagMs)"
             "EXTRACT(jsonPayload.queueAgeMs)"
             "EXTRACT(jsonPayload.reservedMinorUnits)"]]
      (is (str/includes? proto-infra extractor)))
    (is (str/includes?
         proto-infra
         "cloudtasks.googleapis.com/queue/depth"))
    (is (str/includes?
         proto-infra
         "resource.label.queue_id=\\\"agg-derivative-preview\\\""))
    (doseq [role ["roles/logging.configWriter" "roles/monitoring.editor"]]
      (is (str/includes? proto-infra role))
      (is (str/includes? bootstrap role)))
    (doseq [metric
            ["derivative_preparation_latency_ms"
             "derivative_cache_hits"
             "derivative_cache_misses"
             "derivative_failures"
             "derivative_timeouts"
             "derivative_drive_bytes"
             "derivative_output_bytes"
             "derivative_cancellation_lag_ms"
             "derivative_queue_age_ms"
             "derivative_reserved_minor_units"
             "derivative_reservation_rejections"]]
      (is (re-find
           #"depends_on\s*=\s*\[google_project_iam_member\.deployer\]"
           (terraform-resource proto-infra "google_logging_metric" metric))))
    (is (re-find
         #"depends_on\s*=\s*\[google_project_iam_member\.deployer\]"
         (terraform-resource
          proto-infra "google_monitoring_notification_channel"
          "proto_owner_email")))
    (let [cache-alert
          (terraform-resource
           proto-infra "google_monitoring_alert_policy"
           "derivative_cache_ratio")
          hit-metric
          "logging.googleapis.com/user/alpha_compose_proto/derivative_cache_hits"
          miss-metric
          "logging.googleapis.com/user/alpha_compose_proto/derivative_cache_misses"]
      (is (str/includes?
           cache-alert "condition_prometheus_query_language"))
      (is (= 3 (dec (count (str/split cache-alert
                                      (re-pattern
                                       (java.util.regex.Pattern/quote
                                        hit-metric)))))))
      (is (= 2 (dec (count (str/split cache-alert
                                      (re-pattern
                                       (java.util.regex.Pattern/quote
                                        miss-metric)))))))
      (is (str/includes? cache-alert "cache_hits / (cache_hits + cache_misses)"))
      (is (str/includes? cache-alert ") < 0.5"))
      (is (str/includes? cache-alert ") >= 10"))
      (is (str/includes? cache-alert "[10m]"))
      (is (not (str/includes?
                cache-alert "condition_threshold")))
      (is (str/includes?
           cache-alert
           "google_logging_metric.derivative_cache_hits"))
      (is (str/includes?
           cache-alert
           "google_logging_metric.derivative_cache_misses")))
    (is (str/includes?
         (terraform-resource
          proto-infra "google_logging_metric"
          "derivative_cancellation_lag_ms")
         "jsonPayload.status=(\\\"cancelled\\\" OR \\\"expired\\\")"))
    (let [apply-index (str/index-of
                       proto-workflow "terraform -chdir=infra/proto apply")
          verify-index (str/index-of
                        proto-workflow "Verify proto health and landing page")]
      (is (every? number? [apply-index verify-index]))
      (is (< apply-index verify-index)))
    (is (not (re-find
              #"(?i)jsonPayload\\.(?:fileId|filename|ownerSubject|email|objectKey|signedUrl|token|authority)"
              proto-infra)))))

(deftest derivative-preview-has-an-isolated-private-execution-plane
  (let [proto-infra (slurp "infra/proto/main.tf")]
    (testing "ephemeral derivative objects stay private and expire after one day"
      (is (str/includes?
           proto-infra
           "resource \"google_storage_bucket\" \"derivative_previews\""))
      (is (str/includes? proto-infra
                         "name                        = \"${local.project_id}-derivative-previews\""))
      (is (str/includes? proto-infra
                         "public_access_prevention    = \"enforced\""))
      (is (str/includes? proto-infra
                         "uniform_bucket_level_access = true"))
      (is (str/includes? proto-infra
                         "retention_duration_seconds = 0"))
      (is (re-find #"(?s)resource \"google_storage_bucket\" \"derivative_previews\".*?versioning \{\s+enabled = false\s+\}.*?lifecycle_rule \{.*?age = 1"
                   proto-infra)))
    (testing "dispatch is isolated and serialized"
      (is (str/includes?
           proto-infra
           "resource \"google_cloud_tasks_queue\" \"derivative_preview\""))
      (is (str/includes? proto-infra
                         "name     = \"agg-derivative-preview\""))
      (is (str/includes? proto-infra
                         "max_concurrent_dispatches = 1"))
      (is (str/includes? proto-infra
                         "max_dispatches_per_second = 1"))
      (is (str/includes?
           proto-infra
           "resource \"google_service_account\" \"derivative_tasks\""))
      (is (str/includes?
           proto-infra
           "resource \"google_service_account\" \"derivative_worker\"")))
    (testing "timed-out work is reconciled through the proto audience"
      (is (str/includes? proto-infra "\"roles/cloudscheduler.admin\""))
      (is (re-find
           #"(?s)resource \"google_cloud_scheduler_job\" \"derivative_reconcile\".*?name\s+=\s+\"agg-derivative-reconcile\".*?schedule\s+=\s+\"\* \* \* \* \*\".*?derivative-preparations/reconcile.*?X-CloudScheduler.*?audience\s+=\s+var\.proto_service_url"
           proto-infra))
      (is (re-find
           #"(?s)resource \"google_service_account_iam_member\" \"deployer_uses_scheduler\".*?roles/iam\.serviceAccountUser"
           proto-infra)))
    (testing "the worker is one bounded, non-retrying task"
      (is (str/includes?
           proto-infra
           "resource \"google_cloud_run_v2_job\" \"derivative_preview\""))
      (is (str/includes? proto-infra
                         "name                = \"agg-derivative-preview\""))
      (is (str/includes? proto-infra "parallelism = 1"))
      (is (str/includes? proto-infra "task_count  = 1"))
      (is (str/includes? proto-infra "max_retries           = 0"))
      (is (str/includes? proto-infra "timeout               = \"900s\""))
      (is (str/includes? proto-infra "cpu    = \"4\""))
      (is (str/includes? proto-infra "memory = \"4Gi\""))
      (is (str/includes? proto-infra "image = var.proto_image"))
      (is (re-find #"AGG_ADMIN_EMAILS\s+=\s+var\.admin_emails"
                   proto-infra))
      (is (re-find #"AGG_OWNER_EMAIL\s+=\s+var\.owner_email"
                   proto-infra))
      (is (re-find
           #"(?s)resource \"google_cloud_run_v2_job\" \"derivative_preview\".*?name = \"AGG_TOKEN_HASH_PEPPER\".*?secret\s+=\s+\"token-hash-pepper\""
           proto-infra))
      (is (str/includes?
           proto-infra
           "args  = [\"clojure.main\", \"-m\", \"agg.derivative.worker\"]")))))

(deftest derivative-preview-runtime-and-iam-are-bounded
  (let [proto-infra (slurp "infra/proto/main.tf")
        proto-workflow (slurp ".github/workflows/deploy-proto.yml")]
    (testing "only the API reader and worker receive derivative object access"
      (is (re-find #"(?s)resource \"google_storage_bucket_iam_member\" \"api_derivative_reader\".*?roles/storage\.objectViewer.*?data\.google_service_account\.api\.email"
                   proto-infra))
      (is (re-find #"(?s)resource \"google_storage_bucket_iam_member\" \"derivative_worker_objects\".*?roles/storage\.objectUser.*?google_service_account\.derivative_worker\.email"
                   proto-infra))
      (is (not (re-find #"(?s)resource \"google_storage_bucket_iam_member\" \"[^\"]*derivative[^\"]*\" \{[^}]*roles/storage\.(?:admin|objectAdmin)"
                        proto-infra))))
    (testing "the API can operate only the dedicated queue and worker job"
      (doseq [role ["roles/cloudtasks.enqueuer"
                    "roles/cloudtasks.taskDeleter"]]
        (is (re-find
             (re-pattern
              (str "(?s)resource \\\"google_cloud_tasks_queue_iam_member\\\""
                   ".*?name\\s*=\\s*google_cloud_tasks_queue\\.derivative_preview\\.name"
                   ".*?" (java.util.regex.Pattern/quote role)
                   ".*?data\\.google_service_account\\.api\\.email"))
             proto-infra)))
      (doseq [role ["roles/run.jobsExecutorWithOverrides"
                    "roles/run.viewer"]]
        (is (re-find
             (re-pattern
              (str "(?s)resource \\\"google_cloud_run_v2_job_iam_member\\\""
                   ".*?name\\s*=\\s*google_cloud_run_v2_job\\.derivative_preview\\.name"
                   ".*?" (java.util.regex.Pattern/quote role)
                   ".*?data\\.google_service_account\\.api\\.email"))
             proto-infra))))
    (testing "task and worker identities have only their required boundaries"
      (doseq [contract ["roles/iam.serviceAccountUser"
                        "roles/iam.serviceAccountTokenCreator"
                        "roles/run.invoker"
                        "roles/datastore.user"
                        "roles/cloudkms.cryptoKeyEncrypterDecrypter"
                        "roles/secretmanager.secretAccessor"]]
        (is (str/includes? proto-infra contract)))
      (is (re-find #"(?s)resource \"google_kms_crypto_key_iam_member\" \"derivative_worker_drive_token_cipher\".*?drive-refresh-tokens.*?roles/cloudkms\.cryptoKeyEncrypterDecrypter.*?google_service_account\.derivative_worker\.email"
                   proto-infra))
      (is (re-find #"(?s)resource \"google_secret_manager_secret_iam_member\" \"derivative_worker_oauth_access\".*?oauth-client-secret.*?roles/secretmanager\.secretAccessor.*?google_service_account\.derivative_worker\.email"
                   proto-infra))
      (is (re-find #"(?s)resource \"google_secret_manager_secret_iam_member\" \"derivative_worker_token_hash_pepper_access\".*?token-hash-pepper.*?roles/secretmanager\.secretAccessor.*?google_service_account\.derivative_worker\.email"
                   proto-infra))
      (is (not (re-find #"(?s)google_service_account\\.derivative_worker\\.email.*?roles/(?:owner|editor|storage\\.admin|run\\.admin)"
                        proto-infra))))
    (testing "the proto API receives the approved derivative contract"
      (doseq [runtime-value
              ["AGG_DERIVATIVE_BUCKET"
               "AGG_DERIVATIVE_TASKS_QUEUE"
               "AGG_DERIVATIVE_TASKS_SERVICE_ACCOUNT"
               "AGG_DERIVATIVE_WORKER_JOB"
               "AGG_DERIVATIVE_DISPATCHER_URL"
               "AGG_DERIVATIVE_MAX_SOURCE_DURATION_SECONDS"
               "AGG_DERIVATIVE_MAX_SOURCE_BYTES"
               "AGG_DERIVATIVE_MAX_UPSTREAM_BYTES"
               "AGG_DERIVATIVE_MAX_REQUEST_COUNT"
               "AGG_DERIVATIVE_MAX_RANGE_BYTES"
               "AGG_DERIVATIVE_MAX_OUTPUT_BYTES"
               "AGG_DERIVATIVE_MAX_PROJECT_NONTERMINAL_JOBS"
               "AGG_DERIVATIVE_MAX_USER_NONTERMINAL_JOBS"
               "AGG_DERIVATIVE_ATTEMPT_RESERVATION_MINOR_UNITS"
               "AGG_DERIVATIVE_MAX_USER_ATTEMPTS_PER_DAY"
               "AGG_DERIVATIVE_MAX_USER_MONTHLY_MINOR_UNITS"
               "AGG_DERIVATIVE_MAX_MONTHLY_MINOR_UNITS"]]
        (is (str/includes? proto-infra runtime-value))))
    (testing "immutable inputs are resolved before a guarded Terraform apply"
      (let [service-index (str/index-of
                           proto-workflow
                           "Discover current API dispatcher audience")
            image-index (str/index-of
                         proto-workflow
                         "Push and resolve immutable image digest")
            terraform-index (str/index-of
                             proto-workflow
                             "Plan and apply proto Terraform")
            verification-index (str/index-of
                                proto-workflow
                                "Verify proto health and landing page")]
        (is (every? number?
                    [service-index image-index terraform-index
                     verification-index]))
        (is (< service-index terraform-index verification-index))
        (is (< image-index terraform-index verification-index)))
      (is (str/includes? proto-workflow
                         "Delete or replace actions are forbidden"))
      (is (str/includes? proto-workflow
                         "Discover existing proto derivative audience"))
      (is (str/includes? proto-workflow
                         "TF_VAR_proto_service_url: ${{ steps.proto-service-audience.outputs.url }}"))
      (is (not (str/includes? proto-workflow "gcloud run jobs deploy")))
      (is (not (str/includes? proto-workflow "gcloud tasks queues create"))))))

(deftest proto-runbook-has-safe-request-correlated-log-queries
  (let [runbook (slurp "docs/proto-runbook.md")]
    (doseq [field ["requestId" "trace" "operation" "revision" "reason"]]
      (is (str/includes? runbook (str "jsonPayload." field))))
    (is (str/includes? runbook "gcloud logging read"))
    (is (str/includes? runbook "resource.labels.service_name=\"agg-proto\""))
    (is (str/includes? runbook "--limit=100"))
    (is (str/includes?
         runbook
         "jsonPayload.exceptionClass,jsonPayload.exceptionStack"))
    (is (not (re-find
              #"jsonPayload\\.(?:fileId|fileName|account|token|credential|requestBody|telemetry)"
              runbook)))))

(deftest proto-runbook-has-redacted-derivative-lifecycle-queries
  (let [runbook (slurp "docs/proto-runbook.md")
        section
        (second
         (str/split runbook #"## Derivative preparation observability" 2))]
    (is (string? section))
    (is (str/includes?
         section
         "jsonPayload.requestId=\"REPLACE_REQUEST_ID\""))
    (doseq [boundary
            ["derivative_cache"
             "derivative_queue"
             "derivative_encode"
             "derivative_publication"
             "derivative_playback"
             "derivative_reconciliation"]]
      (is (str/includes? section boundary)))
    (doseq [field
            ["requestId" "trace" "revision" "operation" "status" "reason"
             "elapsedMs" "queueAgeMs" "attempt" "durationBucket"
             "rangeStart" "rangeEnd" "bytesRequested" "bytesTransferred"
             "sourceBytes" "upstreamBytes" "outputBytes" "cacheOutcome"
             "profileVersion" "reservedMinorUnits"]]
      (is (str/includes? section (str "jsonPayload." field))))
    (is (str/includes? section "--freshness=24h"))
    (is (str/includes? section "--limit=100"))
    (is (str/includes?
         section
         "jsonPayload.event=\"derivative_preparation_terminal\""))
    (is (str/includes?
         section
         "jsonPayload.status=(\"failed\" OR \"rejected\" OR \"cancelled\" OR \"expired\")"))
    (is (not (str/includes?
              section "derivative_preparation_expired")))
    (is (not (re-find
              #"(?i)jsonPayload\\.(?:fileId|filename|ownerSubject|email|objectKey|signedUrl|token|authority|requestBody)"
              section)))))

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
    (is (re-find #"AGG_SERVICE_PROFILE\s*=\s*\"proto\"" proto-infra))
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
                       "TF_VAR_proto_service_url: ${{ steps.context.outputs.proto_service_url }}"))
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
    (doseq [role ["roles/cloudkms.admin"
                  "roles/cloudtasks.admin"
                  "roles/aggTerraformSecretAdmin"]]
      (is (str/includes? proto-infra role))
      (is (str/includes? proto-bootstrap role)))
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
