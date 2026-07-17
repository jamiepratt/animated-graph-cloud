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

(deftest delivery-enables-the-api-with-its-own-authenticated-task-audience
  (is (str/includes? workflow "AGG_JOB_LIFECYCLE_ENABLED=true"))
  (is (str/includes? workflow
                     "AGG_DISPATCHER_URL=$CLOUD_RUN_SERVICE_URL"))
  (is (str/includes? workflow "AGG_TASKS_SERVICE_ACCOUNT=agg-tasks@")))

(deftest cloud-stage-reports-survive-an-uberjar-build-and-support-resume
  (is (str/includes? cloud-spike ".spike/cloud/$run_id"))
  (is (not (str/includes? cloud-spike "target/spike")))
  (is (str/includes? cloud-spike "PRIOR_ESTIMATED_COST_USD"))
  (is (str/includes? cloud-spike "resume_stage")))
