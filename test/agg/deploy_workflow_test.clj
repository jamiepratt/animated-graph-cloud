(ns agg.deploy-workflow-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)))

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

(def ^:private youtube-bootstrap-targets
  ["google_project_service.required[\"apikeys.googleapis.com\"]"
   "google_project_service.required[\"youtube.googleapis.com\"]"
   "google_secret_manager_secret.application[\"youtube-api-key\"]"
   "google_secret_manager_secret_iam_member.api_youtube_access"
   "google_secret_manager_secret_iam_member.deployer_youtube_access"
   "google_project_iam_member.deployer_picker_api_keys_viewer"])

(def ^:private youtube-refresh-prerequisite-targets
  ["google_project_iam_custom_role.youtube_repair_refresh_reader[0]"
   "google_project_iam_member.deployer_youtube_repair_refresh_reader[0]"])

(defn- terraform-plan-change [address actions]
  {:mode "managed"
   :address address
   :change {:actions actions}})

(defn- youtube-bootstrap-plan-result [resource-changes]
  (let [plan (File/createTempFile "youtube-bootstrap-plan-" ".json")]
    (try
      (spit plan (json/write-str {:resource_changes resource-changes}))
      (shell/sh "bash" "script/guard_youtube_metadata_bootstrap_plan.sh"
                (.getAbsolutePath plan))
      (finally
        (.delete plan)))))

(defn- youtube-refresh-prerequisite-plan-result [resource-changes]
  (let [plan (File/createTempFile "youtube-refresh-prerequisite-plan-" ".json")]
    (try
      (spit plan (json/write-str {:resource_changes resource-changes}))
      (shell/sh "bash" "script/guard_youtube_metadata_refresh_plan.sh"
                (.getAbsolutePath plan))
      (finally
        (.delete plan)))))

(defn- authorized-youtube-bootstrap-plan []
  (mapv (fn [address]
          (terraform-plan-change
           address
           (if (= address (last youtube-bootstrap-targets))
             ["create"]
             ["no-op"])))
        youtube-bootstrap-targets))

(defn- authorized-youtube-refresh-prerequisite-plan []
  [(assoc-in
    (terraform-plan-change (first youtube-refresh-prerequisite-targets)
                           ["create"])
    [:change :after]
    {:project "animated-graph-cloud-jp"
     :role_id "aggYoutubeRepairRefreshReader"
     :permissions ["iam.serviceAccounts.get"
                   "serviceusage.services.list"]})
   (assoc-in
    (terraform-plan-change (second youtube-refresh-prerequisite-targets)
                           ["create"])
    [:change :after]
    {:project "animated-graph-cloud-jp"
     :role "projects/animated-graph-cloud-jp/roles/aggYoutubeRepairRefreshReader"
     :member "serviceAccount:agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com"})
   (terraform-plan-change "google_service_account.deployer" ["no-op"])])

(defn- with-fake-gcloud [f]
  (let [directory (doto (File/createTempFile "fake-gcloud-" "")
                    (.delete)
                    (.mkdir))
        command (File. directory "gcloud")
        log (File. directory "calls.log")]
    (try
      (spit command
            (str "#!/usr/bin/env bash\n"
                 "set -euo pipefail\n"
                 "printf '%s\\n' \"$*\" >>\"$GCLOUD_LOG\"\n"
                 "if [[ \"${1:-} ${2:-}\" == 'auth list' ]]; then\n"
                 "  printf '%s\\n' \"$ACTIVE_ACCOUNT\"\n"
                 "elif [[ \"${1:-} ${2:-}\" == 'auth print-access-token' ]]; then\n"
                 "  printf '%s\\n' 'test-operator-token'\n"
                 "elif [[ \"${1:-} ${2:-} ${3:-}\" == 'run jobs describe' ]]; then\n"
                 "  printf '%s\\n' 'europe-central2-docker.pkg.dev/animated-graph-cloud-jp/agg/renderer:test'\n"
                 "elif [[ \"${1:-} ${2:-} ${3:-}\" == 'run services describe' ]]; then\n"
                 "  printf '%s\\n' 'https://agg-api.test.example'\n"
                 "elif [[ \"${1:-} ${2:-} ${3:-}\" == 'storage buckets get-iam-policy' ]]; then\n"
                 "  printf '%s\\n' '{\"bindings\":[{\"role\":\"roles/storage.objectAdmin\",\"members\":[\"serviceAccount:agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com\"]}]}'\n"
                 "fi\n"))
      (.setExecutable command true)
      (f directory log)
      (finally
        (doseq [file (reverse (file-seq directory))]
          (.delete file))))))

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
    (is (str/includes? bootstrap
                       "confirm_development_viewer_iam_repair"))
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
                                           "Record the completed development IAM repair")
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
                   "script/guard_youtube_metadata_bootstrap_plan.sh"
                   "Only creation of the development API Keys viewer IAM binding is authorized"
                   "YouTube metadata bootstrap plan blocked"]]
      (is (str/includes? bootstrap guard) guard))
    (is (str/includes? workflow
                       "Dispatch Repair development YouTube key lookup IAM with fresh authority"))
    (doseq [position [bootstrap-plan-guard bootstrap-apply bootstrap-checkpoint
                      production-apply production-validation]]
      (is (number? position)))
    (when (every? number? [bootstrap-plan-guard bootstrap-apply
                           bootstrap-checkpoint])
      (is (< bootstrap-plan-guard bootstrap-apply bootstrap-checkpoint)))
    (when (every? number? [production-apply production-validation])
      (is (< production-apply production-validation)))))

(deftest youtube-bootstrap-plan-guard-accepts-the-exact-authorized-repair
  (let [{:keys [exit err]}
        (youtube-bootstrap-plan-result (authorized-youtube-bootstrap-plan))]
    (is (zero? exit) err)))

(deftest youtube-bootstrap-plan-guard-rejects-an-additional-target-create
  (let [broader-plan (assoc-in (authorized-youtube-bootstrap-plan)
                               [0 :change :actions]
                               ["create"])
        {:keys [exit]} (youtube-bootstrap-plan-result broader-plan)]
    (is (not (zero? exit)))))

(deftest youtube-bootstrap-plan-guard-rejects-every-broader-plan
  (let [authorized (authorized-youtube-bootstrap-plan)
        viewer-index (dec (count authorized))
        cases {"viewer update"
               (assoc-in authorized [viewer-index :change :actions] ["update"])
               "viewer replacement"
               (assoc-in authorized [viewer-index :change :actions]
                         ["delete" "create"])
               "missing required no-op target"
               (subvec authorized 1)
               "unrelated create"
               (conj authorized
                     (terraform-plan-change
                      "google_project_iam_member.unrelated"
                      ["create"]))
               "production-prefixed repair"
               (mapv #(update % :address (partial str "module.application."))
                     authorized)}]
    (doseq [[case-name plan] cases]
      (testing case-name
        (is (not (zero? (:exit (youtube-bootstrap-plan-result plan)))))))))

(deftest youtube-bootstrap-workflow-enforces-the-development-only-plan-guard
  (let [bootstrap (slurp ".github/workflows/bootstrap-youtube-metadata.yml")
        guard (str/index-of bootstrap
                            "script/guard_youtube_metadata_bootstrap_plan.sh")
        apply-plan (str/index-of bootstrap
                                 "terraform -chdir=\"$TF_DIRECTORY\" apply")]
    (is (not (str/includes? bootstrap "options: [development, production]")))
    (is (not (str/includes? bootstrap "animated-graph-cloud-prod-jp")))
    (is (not (str/includes? bootstrap "TF_PREFIX")))
    (is (str/includes? bootstrap
                       "confirm_development_viewer_iam_repair"))
    (is (number? guard))
    (is (number? apply-plan))
    (when (and (number? guard) (number? apply-plan))
      (is (< guard apply-plan)))))

(deftest development-youtube-repair-refresh-reader-is-exact-and-production-disabled
  (is (re-find
       #"(?s)resource \"google_project_iam_custom_role\" \"youtube_repair_refresh_reader\".*?permissions\s+=\s+\[\s*\"iam\.serviceAccounts\.get\",\s*\"serviceusage\.services\.list\",?\s*\]"
       terraform))
  (is (re-find
       #"(?s)resource \"google_project_iam_member\" \"deployer_youtube_repair_refresh_reader\".*?role\s+=\s+\"projects/\$\{var\.project_id\}/roles/aggYoutubeRepairRefreshReader\".*?member\s+=\s+\"serviceAccount:\$\{google_service_account\.deployer\.email\}\".*?depends_on\s+=\s+\[google_project_iam_custom_role\.youtube_repair_refresh_reader\]"
       terraform))
  (is (str/includes?
       production-terraform
       "enable_youtube_repair_refresh_reader = false")))

(deftest youtube-repair-refresh-prerequisite-guard-allows-only-the-exact-plan
  (let [authorized (authorized-youtube-refresh-prerequisite-plan)
        broader
        [(assoc-in authorized [0 :change :actions] ["update"])
         (assoc-in authorized [1 :change :actions] ["delete" "create"])
         (update-in authorized [0 :change :after :permissions]
                    conj "serviceusage.services.enable")
         (assoc-in authorized [1 :change :after :member]
                   "serviceAccount:unrelated@animated-graph-cloud-jp.iam.gserviceaccount.com")
         (subvec authorized 1)
         (conj authorized
               (terraform-plan-change "google_project_iam_member.unrelated"
                                      ["create"]))
         (mapv #(update % :address (partial str "module.application."))
               authorized)]]
    (is (zero? (:exit (youtube-refresh-prerequisite-plan-result authorized))))
    (doseq [plan broader]
      (is (not (zero? (:exit
                       (youtube-refresh-prerequisite-plan-result plan))))))))

(deftest development-youtube-refresh-plan-binds-the-reviewed-human-identity
  (with-fake-gcloud
    (fn [directory log]
      (let [terraform-command (File. directory "terraform")
            plan-json (File/createTempFile "youtube-refresh-plan-" ".json")
            plan-file (File/createTempFile "youtube-refresh-plan-" ".tfplan")
            base-env {"PATH" (str (.getAbsolutePath directory)
                                  ":" (System/getenv "PATH"))
                      "GCLOUD_LOG" (.getAbsolutePath log)
                      "PLAN_JSON" (.getAbsolutePath plan-json)
                      "ACTIVE_ACCOUNT" "owner@example.com"
                      "GOOGLE_APPLICATION_CREDENTIALS" "/stale/adc.json"}]
        (try
          (spit plan-json
                (json/write-str
                 {:resource_changes
                  (authorized-youtube-refresh-prerequisite-plan)}))
          (spit terraform-command
                (str "#!/usr/bin/env bash\n"
                     "set -euo pipefail\n"
                     "test \"${GOOGLE_OAUTH_ACCESS_TOKEN:-}\" = 'test-operator-token'\n"
                     "printf 'terraform %s\\n' \"$*\" >>\"$GCLOUD_LOG\"\n"
                     "if [[ \" $* \" == *' show -json '* ]]; then\n"
                     "  cat \"$PLAN_JSON\"\n"
                     "fi\n"))
          (.setExecutable terraform-command true)
          (let [{:keys [exit err]}
                (shell/sh
                 "bash"
                 "script/plan_development_youtube_refresh.sh"
                 (.getAbsolutePath plan-file)
                 :env base-env)
                calls (slurp log)]
            (is (zero? exit) err)
            (doseq [call ["auth print-access-token --account=owner@example.com"
                          (str "run jobs describe agg-renderer "
                               "--project=animated-graph-cloud-jp "
                               "--region=europe-central2 "
                               "--account=owner@example.com")
                          (str "run services describe agg-api "
                               "--project=animated-graph-cloud-jp "
                               "--region=europe-central2 "
                               "--account=owner@example.com")
                          "terraform -chdir=infra/dev init -input=false"
                          "-target=google_project_iam_custom_role.youtube_repair_refresh_reader[0]"
                          "-target=google_project_iam_member.deployer_youtube_repair_refresh_reader[0]"
                          "terraform -chdir=infra/dev show -json"]]
              (is (str/includes? calls call) call))
            (is (not (str/includes? calls "terraform -chdir=infra/dev apply"))))
          (spit plan-json
                (json/write-str
                 {:resource_changes
                  [(terraform-plan-change
                    "google_project_iam_member.unrelated" ["create"])]}))
          (let [result
                (shell/sh
                 "bash"
                 "script/plan_development_youtube_refresh.sh"
                 (.getAbsolutePath plan-file)
                 :env base-env)]
            (is (not (zero? (:exit result)))))
          (finally
            (.delete plan-json)
            (.delete plan-file)))))))

(deftest development-youtube-refresh-plan-rejects-non-human-identities
  (with-fake-gcloud
    (fn [directory log]
      (let [terraform-command (File. directory "terraform")
            plan-json (File/createTempFile "youtube-refresh-plan-" ".json")
            plan-file (File/createTempFile "youtube-refresh-plan-" ".tfplan")
            base-env {"PATH" (str (.getAbsolutePath directory)
                                  ":" (System/getenv "PATH"))
                      "GCLOUD_LOG" (.getAbsolutePath log)
                      "PLAN_JSON" (.getAbsolutePath plan-json)}]
        (try
          (spit plan-json
                (json/write-str
                 {:resource_changes
                  (authorized-youtube-refresh-prerequisite-plan)}))
          (spit terraform-command
                (str "#!/usr/bin/env bash\n"
                     "set -euo pipefail\n"
                     "printf 'terraform %s\\n' \"$*\" >>\"$GCLOUD_LOG\"\n"
                     "if [[ \" $* \" == *' show -json '* ]]; then\n"
                     "  cat \"$PLAN_JSON\"\n"
                     "fi\n"))
          (.setExecutable terraform-command true)
          (doseq [account
                  [""
                   "agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com"
                   "operator-bot@other-project.iam.gserviceaccount.com"]]
            (spit log "")
            (let [result
                  (shell/sh
                   "bash"
                   "script/plan_development_youtube_refresh.sh"
                   (.getAbsolutePath plan-file)
                   :env (assoc base-env "ACTIVE_ACCOUNT" account))]
              (is (not (zero? (:exit result))) account)
              (is (not (str/includes? (slurp log) "terraform")) account)))
          (finally
            (.delete plan-json)
            (.delete plan-file)))))))

(deftest development-youtube-refresh-apply-fails-closed-and-applies-only-the-saved-plan
  (with-fake-gcloud
    (fn [directory log]
      (let [terraform-command (File. directory "terraform")
            plan-json (File/createTempFile "youtube-refresh-apply-" ".json")
            plan-file (File/createTempFile "youtube-refresh-apply-" ".tfplan")
            authorized (authorized-youtube-refresh-prerequisite-plan)
            base-env {"PATH" (str (.getAbsolutePath directory)
                                  ":" (System/getenv "PATH"))
                      "GCLOUD_LOG" (.getAbsolutePath log)
                      "PLAN_JSON" (.getAbsolutePath plan-json)
                      "ACTIVE_ACCOUNT" "owner@example.com"}
            run-apply (fn [env]
                        (shell/sh
                         "bash"
                         "script/apply_development_youtube_refresh_plan.sh"
                         (.getAbsolutePath plan-file)
                         :env env))]
        (try
          (spit plan-json (json/write-str {:resource_changes authorized}))
          (spit terraform-command
                (str "#!/usr/bin/env bash\n"
                     "set -euo pipefail\n"
                     "test -n \"${GOOGLE_OAUTH_ACCESS_TOKEN:-}\"\n"
                     "printf 'terraform %s\\n' \"$*\" >>\"$GCLOUD_LOG\"\n"
                     "if [[ \" $* \" == *' show -json '* ]]; then\n"
                     "  cat \"$PLAN_JSON\"\n"
                     "fi\n"))
          (.setExecutable terraform-command true)
          (is (not (zero? (:exit (run-apply base-env)))))
          (is (not
               (zero?
                (:exit
                 (run-apply
                  (assoc base-env
                         "ACTIVE_ACCOUNT"
                         "agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com"
                         "CONFIRM_DEVELOPMENT_YOUTUBE_REFRESH_IAM_REPAIR"
                         "apply exact development youtube refresh reader"))))))
          (spit log "")
          (let [result
                (run-apply
                 (assoc base-env
                        "ACTIVE_ACCOUNT"
                        "operator-bot@other-project.iam.gserviceaccount.com"
                        "CONFIRM_DEVELOPMENT_YOUTUBE_REFRESH_IAM_REPAIR"
                        "apply exact development youtube refresh reader"))]
            (is (not (zero? (:exit result))))
            (is (not (str/includes? (slurp log) "terraform"))))
          (spit plan-json
                (json/write-str
                 {:resource_changes
                  (conj authorized
                        (terraform-plan-change
                         "google_project_iam_member.unrelated" ["create"]))}))
          (is (not
               (zero?
                (:exit
                 (run-apply
                  (assoc base-env
                         "CONFIRM_DEVELOPMENT_YOUTUBE_REFRESH_IAM_REPAIR"
                         "apply exact development youtube refresh reader"))))))
          (spit plan-json (json/write-str {:resource_changes authorized}))
          (let [result
                (run-apply
                 (assoc base-env
                        "CONFIRM_DEVELOPMENT_YOUTUBE_REFRESH_IAM_REPAIR"
                        "apply exact development youtube refresh reader"))
                calls (slurp log)]
            (is (zero? (:exit result)) (:err result))
            (is (= 1 (count (re-seq #"terraform -chdir=infra/dev apply -input=false"
                                    calls))))
            (is (not (str/includes? calls "projects add-iam-policy-binding"))))
          (finally
            (.delete plan-json)
            (.delete plan-file)))))))

(deftest development-terraform-backend-recovery-is-an-exact-reviewable-plan
  (let [{:keys [exit out err]}
        (shell/sh "bash"
                  "script/recover_development_terraform_backend_access.sh"
                  "plan")]
    (is (zero? exit) err)
    (doseq [contract
            ["Development Terraform backend IAM recovery plan"
             "gs://animated-graph-cloud-jp-tfstate"
             "serviceAccount:agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com"
             "roles/storage.objectAdmin"
             "No cloud request or mutation was made."]]
      (is (str/includes? out contract) contract))
    (is (not (str/includes? out "animated-graph-cloud-prod-jp")))
    (is (not (str/includes? out "roles/storage.admin")))))

(deftest development-terraform-backend-recovery-fails-closed-before-mutation
  (with-fake-gcloud
    (fn [directory log]
      (let [base-env {"PATH" (str (.getAbsolutePath directory)
                                  ":" (System/getenv "PATH"))
                      "GCLOUD_LOG" (.getAbsolutePath log)
                      "ACTIVE_ACCOUNT" "owner@example.com"}
            missing-confirmation
            (shell/sh "bash"
                      "script/recover_development_terraform_backend_access.sh"
                      "apply"
                      :env base-env)
            target-self-grant
            (shell/sh
             "bash"
             "script/recover_development_terraform_backend_access.sh"
             "apply"
             :env (assoc base-env
                         "ACTIVE_ACCOUNT"
                         "agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com"
                         "CONFIRM_DEVELOPMENT_TERRAFORM_BACKEND_IAM_REPAIR"
                         "grant development state bucket object admin"))
            unexpected-target
            (shell/sh
             "bash"
             "script/recover_development_terraform_backend_access.sh"
             "apply"
             "production"
             :env (assoc base-env
                         "CONFIRM_DEVELOPMENT_TERRAFORM_BACKEND_IAM_REPAIR"
                         "grant development state bucket object admin"))
            calls (if (.exists log) (slurp log) "")]
        (is (not (zero? (:exit missing-confirmation))))
        (is (not (zero? (:exit target-self-grant))))
        (is (not (zero? (:exit unexpected-target))))
        (is (not (str/includes? calls "add-iam-policy-binding")))))))

(deftest development-terraform-backend-recovery-mutates-only-the-exact-binding
  (with-fake-gcloud
    (fn [directory log]
      (let [result
            (shell/sh
             "bash"
             "script/recover_development_terraform_backend_access.sh"
             "apply"
             :env {"PATH" (str (.getAbsolutePath directory)
                               ":" (System/getenv "PATH"))
                   "GCLOUD_LOG" (.getAbsolutePath log)
                   "ACTIVE_ACCOUNT" "owner@example.com"
                   "CONFIRM_DEVELOPMENT_TERRAFORM_BACKEND_IAM_REPAIR"
                   "grant development state bucket object admin"})
            calls (slurp log)]
        (is (zero? (:exit result)) (:err result))
        (is (str/includes?
             calls
             "storage buckets add-iam-policy-binding gs://animated-graph-cloud-jp-tfstate --project=animated-graph-cloud-jp --member=serviceAccount:agg-github-deployer@animated-graph-cloud-jp.iam.gserviceaccount.com --role=roles/storage.objectAdmin --condition=None"))
        (is (not (str/includes? calls "remove-iam-policy-binding")))
        (is (not (str/includes? calls "projects add-iam-policy-binding")))
        (is (str/includes? (:out result)
                           "Exact development state-bucket binding verified"))))))

(deftest youtube-bootstrap-verifies-development-backend-before-planning
  (let [bootstrap (slurp ".github/workflows/bootstrap-youtube-metadata.yml")
        backend-check (str/index-of bootstrap
                                    "Verify development Terraform backend access")
        terraform-init (str/index-of bootstrap
                                     "terraform -chdir=\"$TF_DIRECTORY\" init")
        exact-plan (str/index-of bootstrap
                                 "Plan and apply exact development viewer IAM repair")]
    (doseq [position [backend-check terraform-init exact-plan]]
      (is (number? position)))
    (when (every? number? [backend-check terraform-init exact-plan])
      (is (< backend-check terraform-init exact-plan)))
    (is (= 1 (count (re-seq #"terraform -chdir=\"\$TF_DIRECTORY\" init"
                            bootstrap))))
    (is (str/includes?
         bootstrap
         "Development Terraform backend access required"))
    (is (str/includes?
         bootstrap
         "Do not retry until an authorized operator has completed the reviewed development state-bucket recovery"))
    (is (not (str/includes? bootstrap "add-iam-policy-binding")))
    (is (not (str/includes? bootstrap
                            "recover_development_terraform_backend_access.sh apply")))))

(deftest youtube-bootstrap-verifies-exact-refresh-access-before-planning
  (let [bootstrap (slurp ".github/workflows/bootstrap-youtube-metadata.yml")
        terraform-init (str/index-of bootstrap
                                     "terraform -chdir=\"$TF_DIRECTORY\" init")
        refresh-check (str/index-of bootstrap
                                    "Verify development Terraform refresh access")
        exact-plan (str/index-of bootstrap
                                 "Plan and apply exact development viewer IAM repair")]
    (is (str/includes? bootstrap
                       "confirm_development_refresh_access_recovered"))
    (doseq [contract ["gcloud services list --enabled"
                      "apikeys.googleapis.com youtube.googleapis.com"
                      "gcloud iam service-accounts describe"
                      "--format=none"
                      "Development Terraform refresh access required"]]
      (is (str/includes? bootstrap contract) contract))
    (doseq [position [terraform-init refresh-check exact-plan]]
      (is (number? position)))
    (when (every? number? [terraform-init refresh-check exact-plan])
      (is (< terraform-init refresh-check exact-plan)))
    (is (not (str/includes? bootstrap "roles/serviceusage.serviceUsageViewer")))
    (is (not (str/includes? bootstrap "roles/iam.serviceAccountViewer")))
    (is (not (str/includes? bootstrap "projects add-iam-policy-binding")))))

(deftest development-backend-recovery-has-two-human-authority-checkpoints
  (let [runbook (slurp "docs/production-runbook.md")
        recovery (str/index-of runbook
                               "Development Terraform backend recovery")
        plan (str/index-of runbook
                           "recover_development_terraform_backend_access.sh plan")
        apply-recovery (str/index-of runbook
                                     "recover_development_terraform_backend_access.sh apply")
        retry-authority (str/index-of runbook
                                      "Fresh authority is required again before retrying")]
    (doseq [position [recovery plan apply-recovery retry-authority]]
      (is (number? position)))
    (when (every? number? [recovery plan apply-recovery retry-authority])
      (is (< recovery plan apply-recovery retry-authority)))
    (is (re-find
         #"(?s)resource \"google_storage_bucket_iam_member\" \"deployer_terraform_state\".*?bucket\s+=\s+var\.terraform_state_bucket.*?role\s+=\s+\"roles/storage.objectAdmin\".*?member\s+=\s+\"serviceAccount:\$\{google_service_account\.deployer\.email\}\""
         terraform))
    (is (str/includes? runbook "Never run recovery as the target deployer"))
    (is (str/includes? runbook "stop without retrying"))))

(deftest development-youtube-refresh-recovery-precedes-the-viewer-repair-checkpoint
  (let [runbook (slurp "docs/production-runbook.md")
        refresh-recovery (str/index-of runbook
                                       "Development Terraform refresh recovery")
        refresh-plan (str/index-of runbook
                                   "plan_development_youtube_refresh.sh")
        refresh-guard (str/index-of runbook
                                    "guard_youtube_metadata_refresh_plan.sh")
        refresh-apply (str/index-of runbook
                                    "apply_development_youtube_refresh_plan.sh")
        viewer-repair (str/index-of runbook
                                    "Repair development YouTube key lookup IAM"
                                    (or refresh-apply 0))]
    (doseq [contract ["iam.serviceAccounts.get"
                      "serviceusage.services.list"
                      "google_project_iam_custom_role.youtube_repair_refresh_reader[0]"
                      "google_project_iam_member.deployer_youtube_repair_refresh_reader[0]"
                      "plan_development_youtube_refresh.sh"
                      "CONFIRM_DEVELOPMENT_YOUTUBE_REFRESH_IAM_REPAIR"
                      "confirm_development_refresh_access_recovered"
                      "stop without retrying"]]
      (is (str/includes? runbook contract) contract))
    (doseq [position
            [refresh-recovery refresh-plan refresh-guard refresh-apply viewer-repair]]
      (is (number? position)))
    (when (every? number?
                  [refresh-recovery refresh-plan refresh-guard refresh-apply
                   viewer-repair])
      (is (< refresh-recovery refresh-plan refresh-guard refresh-apply
             viewer-repair))
      (is (not
           (str/includes? (subs runbook refresh-recovery refresh-apply)
                          "terraform -chdir=infra/dev"))))
    (is (str/includes? runbook
                       "The deployer cannot grant this prerequisite to itself"))
    (is (re-find #"Production\s+keeps this development-only role disabled"
                 runbook))))

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
