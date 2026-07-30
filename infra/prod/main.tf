module "application" {
  source = "../dev"

  project_id                   = "animated-graph-cloud-prod-jp"
  region                       = "europe-central2"
  github_repository            = "jamiepratt/animated-graph-cloud"
  github_subject               = "repo:jamiepratt@558780/animated-graph-cloud@1303177214:ref:refs/heads/main"
  renderer_image               = var.renderer_image
  api_service_url              = var.api_service_url
  monthly_budget_pln           = var.monthly_budget_pln
  operations_alert_email       = "me@jamiep.org"
  environment_name             = "production"
  import_default_firestore     = false
  import_api_service           = false
  enable_firebase_hosting      = true
  enable_proto_service         = false
  enable_observability_log_ttl = true
  enable_terraform_deployments = true
  terraform_state_bucket       = "animated-graph-cloud-prod-jp-tfstate"
}

locals {
  project_id = "animated-graph-cloud-prod-jp"
  region     = "europe-central2"

  api_service_account       = "agg-api@${local.project_id}.iam.gserviceaccount.com"
  deployer_service_account  = "agg-github-deployer@${local.project_id}.iam.gserviceaccount.com"
  scheduler_service_account = "agg-scheduler@${local.project_id}.iam.gserviceaccount.com"

  production_private_preview_bucket = "animated-graph-cloud-prod-jp-production-private-previews"
  production_private_preview_queue  = "agg-production-private-preview"
  production_private_preview_job    = "agg-production-private-preview"

  production_private_preview_ttl_collections = toset([
    "production-derivative-preparation-jobs-v1",
    "production-derivative-preview-cache-v1",
    "production-derivative-preparation-idempotency-v1",
    "production-derivative-active-jobs-v1",
  ])

  production_private_preview_contract = {
    AGG_DERIVATIVE_ASSET_TTL_SECONDS                   = "86400"
    AGG_DERIVATIVE_ATTEMPT_RESERVATION_MINOR_UNITS     = "125"
    AGG_DERIVATIVE_CACHE_COLLECTION                    = "production-derivative-preview-cache-v1"
    AGG_DERIVATIVE_CACHE_MINIMUM_REMAINING_TTL_SECONDS = "3600"
    AGG_DERIVATIVE_CONTRACT_VERSION                    = "production-private-preview-contract-v1"
    AGG_DERIVATIVE_ENVIRONMENT                         = "production"
    AGG_DERIVATIVE_IDEMPOTENCY_COLLECTION              = "production-derivative-preparation-idempotency-v1"
    AGG_DERIVATIVE_JOB_COLLECTION                      = "production-derivative-preparation-jobs-v1"
    AGG_DERIVATIVE_JOB_METADATA_TTL_SECONDS            = "86400"
    AGG_DERIVATIVE_MAX_MONTHLY_MINOR_UNITS             = "10000"
    AGG_DERIVATIVE_MAX_OUTPUT_BYTES                    = "268435456"
    AGG_DERIVATIVE_MAX_PROJECT_NONTERMINAL_JOBS        = "10"
    AGG_DERIVATIVE_MAX_RANGE_BYTES                     = "8388608"
    AGG_DERIVATIVE_MAX_REQUEST_COUNT                   = "320"
    AGG_DERIVATIVE_MAX_SOURCE_BYTES                    = "2147483648"
    AGG_DERIVATIVE_MAX_SOURCE_DURATION_SECONDS         = "480"
    AGG_DERIVATIVE_MAX_UPSTREAM_BYTES                  = "2415919104"
    AGG_DERIVATIVE_MAX_USER_ATTEMPTS_PER_DAY           = "5"
    AGG_DERIVATIVE_MAX_USER_MONTHLY_MINOR_UNITS        = "2500"
    AGG_DERIVATIVE_MAX_USER_NONTERMINAL_JOBS           = "1"
    AGG_DERIVATIVE_OBJECT_PREFIX                       = "production/derivative-previews/v1"
    AGG_DERIVATIVE_ORCHESTRATION_COLLECTION            = "production-derivative-active-jobs-v1"
    AGG_DERIVATIVE_PLAYBACK_AUTHORITY_TTL_SECONDS      = "3600"
    AGG_DERIVATIVE_PROFILE_VERSION                     = "h264-aac-1080p25-v1"
  }

  production_private_preview_worker_environment = merge({
    AGG_DERIVATIVE_BUCKET    = google_storage_bucket.production_private_previews.name
    AGG_DRIVE_SOURCE_ENABLED = "true"
    AGG_REGION               = local.region
    GOOGLE_CLOUD_PROJECT     = local.project_id
  }, local.production_private_preview_contract)
}

data "google_project" "production" {
  project_id = local.project_id
}

resource "google_service_account" "production_private_preview_tasks" {
  project      = local.project_id
  account_id   = "agg-prod-preview-tasks"
  display_name = "Alpha Compose production private-preview dispatcher"
  description  = "Dispatches production private-preview work only to the private agg-api origin"

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [module.application]
}

resource "google_service_account" "production_private_preview_worker" {
  project      = local.project_id
  account_id   = "agg-prod-preview-worker"
  display_name = "Alpha Compose production private-preview worker"
  description  = "Executes bounded production private-preview jobs"

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [module.application]
}

resource "google_storage_bucket" "production_private_previews" {
  project                     = local.project_id
  name                        = local.production_private_preview_bucket
  location                    = local.region
  storage_class               = "STANDARD"
  public_access_prevention    = "enforced"
  uniform_bucket_level_access = true
  force_destroy               = false

  versioning {
    enabled = false
  }

  soft_delete_policy {
    retention_duration_seconds = 0
  }

  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      age = 1
    }
  }

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [module.application]
}

resource "google_cloud_tasks_queue" "production_private_preview" {
  project  = local.project_id
  location = local.region
  name     = local.production_private_preview_queue

  rate_limits {
    max_concurrent_dispatches = 1
    max_dispatches_per_second = 1
  }

  retry_config {
    max_attempts       = 100
    max_retry_duration = "3600s"
    min_backoff        = "5s"
    max_backoff        = "300s"
    max_doublings      = 5
  }

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [module.application]
}

resource "google_cloud_scheduler_job" "production_private_preview_reconcile" {
  project          = local.project_id
  region           = local.region
  name             = "agg-production-private-preview-reconcile"
  description      = "Reconciles production private-preview timeouts and cancellations"
  schedule         = "* * * * *"
  time_zone        = "Etc/UTC"
  attempt_deadline = "60s"
  paused           = true

  retry_config {
    retry_count          = 3
    max_retry_duration   = "300s"
    min_backoff_duration = "5s"
    max_backoff_duration = "60s"
    max_doublings        = 3
  }

  http_target {
    uri         = "${var.api_service_url}/internal/v1/derivative-preparations/reconcile"
    http_method = "POST"

    headers = {
      X-CloudScheduler = "true"
    }

    oidc_token {
      service_account_email = local.scheduler_service_account
      audience              = var.api_service_url
    }
  }

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = var.api_service_url != ""
      error_message = "The private agg-api Cloud Run origin is required for private-preview reconciliation."
    }
  }

  depends_on = [module.application]
}

resource "google_cloud_run_v2_job" "production_private_preview" {
  project             = local.project_id
  location            = local.region
  name                = local.production_private_preview_job
  deletion_protection = true

  template {
    parallelism = 1
    task_count  = 1

    template {
      service_account       = google_service_account.production_private_preview_worker.email
      execution_environment = "EXECUTION_ENVIRONMENT_GEN2"
      max_retries           = 0
      timeout               = "900s"

      containers {
        image = var.renderer_image
        args  = ["clojure.main", "-m", "agg.derivative.worker"]

        dynamic "env" {
          for_each = local.production_private_preview_worker_environment
          content {
            name  = env.key
            value = env.value
          }
        }

        env {
          name = "AGG_OAUTH_CLIENT_CREDENTIALS"
          value_source {
            secret_key_ref {
              secret  = "oauth-client-secret"
              version = "latest"
            }
          }
        }

        env {
          name = "AGG_TOKEN_HASH_PEPPER"
          value_source {
            secret_key_ref {
              secret  = "token-hash-pepper"
              version = "latest"
            }
          }
        }

        resources {
          limits = {
            cpu    = "4"
            memory = "4Gi"
          }
        }
      }
    }
  }

  lifecycle {
    prevent_destroy = true
    ignore_changes = [
      client,
      client_version,
    ]
  }

  depends_on = [
    time_sleep.production_private_preview_worker_iam_propagation,
    google_secret_manager_secret_iam_member.production_private_preview_worker_oauth,
    google_secret_manager_secret_iam_member.production_private_preview_worker_pepper,
  ]
}

resource "google_firestore_field" "production_private_preview_expiry" {
  for_each = local.production_private_preview_ttl_collections

  project    = local.project_id
  database   = "(default)"
  collection = each.value
  field      = "expireAt"

  ttl_config {}

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [module.application]
}

resource "google_service_account_iam_member" "production_deployer_uses_private_preview_worker" {
  service_account_id = google_service_account.production_private_preview_worker.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${local.deployer_service_account}"

  depends_on = [module.application]
}

resource "time_sleep" "production_private_preview_worker_iam_propagation" {
  create_duration = "480s"

  triggers = {
    worker_iam = google_service_account_iam_member.production_deployer_uses_private_preview_worker.id
  }
}

resource "google_service_account_iam_member" "production_api_uses_private_preview_tasks" {
  service_account_id = google_service_account.production_private_preview_tasks.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${local.api_service_account}"
}

resource "google_service_account_iam_member" "production_tasks_service_agent_mints_private_preview_oidc" {
  service_account_id = google_service_account.production_private_preview_tasks.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:service-${data.google_project.production.number}@gcp-sa-cloudtasks.iam.gserviceaccount.com"
}

resource "google_storage_bucket_iam_member" "production_api_private_preview_reader" {
  bucket = google_storage_bucket.production_private_previews.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${local.api_service_account}"
}

resource "google_storage_bucket_iam_member" "production_private_preview_worker_objects" {
  bucket = google_storage_bucket.production_private_previews.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.production_private_preview_worker.email}"
}

resource "google_cloud_tasks_queue_iam_member" "production_api_private_preview_enqueuer" {
  project  = local.project_id
  location = local.region
  name     = google_cloud_tasks_queue.production_private_preview.name
  role     = "roles/cloudtasks.enqueuer"
  member   = "serviceAccount:${local.api_service_account}"
}

resource "google_cloud_tasks_queue_iam_member" "production_api_private_preview_task_deleter" {
  project  = local.project_id
  location = local.region
  name     = google_cloud_tasks_queue.production_private_preview.name
  role     = "roles/cloudtasks.taskDeleter"
  member   = "serviceAccount:${local.api_service_account}"
}

resource "google_cloud_run_v2_job_iam_member" "production_api_private_preview_executor" {
  project  = local.project_id
  location = local.region
  name     = google_cloud_run_v2_job.production_private_preview.name
  role     = "roles/run.jobsExecutorWithOverrides"
  member   = "serviceAccount:${local.api_service_account}"
}

resource "google_cloud_run_v2_job_iam_member" "production_api_private_preview_viewer" {
  project  = local.project_id
  location = local.region
  name     = google_cloud_run_v2_job.production_private_preview.name
  role     = "roles/run.viewer"
  member   = "serviceAccount:${local.api_service_account}"
}

resource "google_cloud_run_service_iam_member" "production_private_preview_tasks_invoke_api" {
  project  = local.project_id
  location = local.region
  service  = "agg-api"
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.production_private_preview_tasks.email}"

  depends_on = [module.application]
}

resource "google_project_iam_member" "production_private_preview_worker_firestore" {
  project = local.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.production_private_preview_worker.email}"
}

resource "google_kms_crypto_key_iam_member" "production_private_preview_worker_drive_tokens" {
  crypto_key_id = "projects/${local.project_id}/locations/${local.region}/keyRings/application/cryptoKeys/drive-refresh-tokens"
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${google_service_account.production_private_preview_worker.email}"

  depends_on = [module.application]
}

resource "google_secret_manager_secret_iam_member" "production_private_preview_worker_oauth" {
  project   = local.project_id
  secret_id = "oauth-client-secret"
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.production_private_preview_worker.email}"

  depends_on = [module.application]
}

resource "google_secret_manager_secret_iam_member" "production_private_preview_worker_pepper" {
  project   = local.project_id
  secret_id = "token-hash-pepper"
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.production_private_preview_worker.email}"

  depends_on = [module.application]
}

resource "google_logging_metric" "production_private_preview_latency_ms" {
  project         = local.project_id
  name            = "alpha_compose_production/private_preview_latency_ms"
  description     = "Successful production private-preview latency in milliseconds"
  filter          = "jsonPayload.event=\"derivative_preparation_terminal\" AND jsonPayload.environment=\"production\" AND jsonPayload.status=\"succeeded\""
  value_extractor = "EXTRACT(jsonPayload.elapsedMs)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "ms"
    display_name = "Production private-preview latency"
  }

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 12
      growth_factor      = 2
      scale              = 1000
    }
  }

  depends_on = [module.application]
}

resource "google_logging_metric" "production_private_preview_failures" {
  project     = local.project_id
  name        = "alpha_compose_production/private_preview_failures"
  description = "Terminal production private-preview failures"
  filter      = "jsonPayload.event=\"derivative_preparation_terminal\" AND jsonPayload.environment=\"production\" AND jsonPayload.status=\"failed\""

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Production private-preview failures"
  }

  depends_on = [module.application]
}

resource "google_logging_metric" "production_private_preview_queue_age_ms" {
  project         = local.project_id
  name            = "alpha_compose_production/private_preview_queue_age_ms"
  description     = "Queue age when production private-preview work dispatches"
  filter          = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"derivative_preparation_dispatched\" AND jsonPayload.environment=\"production\""
  value_extractor = "EXTRACT(jsonPayload.queueAgeMs)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "ms"
    display_name = "Production private-preview queue age"
  }

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 12
      growth_factor      = 2
      scale              = 1000
    }
  }

  depends_on = [module.application]
}

resource "google_logging_metric" "production_private_preview_reserved_minor_units" {
  project         = local.project_id
  name            = "alpha_compose_production/private_preview_reserved_minor_units"
  description     = "Minor PLN units reserved by admitted production private-preview attempts"
  filter          = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"derivative_preparation_submitted\" AND jsonPayload.environment=\"production\" AND jsonPayload.reservedMinorUnits>0"
  value_extractor = "EXTRACT(jsonPayload.reservedMinorUnits)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Production private-preview reserved minor units"
  }

  bucket_options {
    explicit_buckets {
      bounds = [125, 250, 500, 1000, 2500, 10000]
    }
  }

  depends_on = [module.application]
}

resource "time_sleep" "production_private_preview_metrics_propagation" {
  create_duration = "660s"

  triggers = {
    failures_metric      = google_logging_metric.production_private_preview_failures.id
    latency_metric       = google_logging_metric.production_private_preview_latency_ms.id
    queue_age_metric     = google_logging_metric.production_private_preview_queue_age_ms.id
    reserved_cost_metric = google_logging_metric.production_private_preview_reserved_minor_units.id
  }
}

resource "google_monitoring_alert_policy" "production_private_preview_latency" {
  project      = local.project_id
  display_name = "Production private-preview latency"
  combiner     = "OR"
  notification_channels = [
    module.application.operations_notification_channel,
  ]

  conditions {
    display_name = "Private-preview latency approaches the worker deadline"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.production_private_preview_latency_ms.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 720000
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }

  depends_on = [time_sleep.production_private_preview_metrics_propagation]
}

resource "google_monitoring_alert_policy" "production_private_preview_failures" {
  project      = local.project_id
  display_name = "Production private-preview failures"
  combiner     = "OR"
  notification_channels = [
    module.application.operations_notification_channel,
  ]

  conditions {
    display_name = "A production private-preview worker failed"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.production_private_preview_failures.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }

  depends_on = [time_sleep.production_private_preview_metrics_propagation]
}

resource "google_monitoring_alert_policy" "production_private_preview_queue_age" {
  project      = local.project_id
  display_name = "Production private-preview queue age"
  combiner     = "OR"
  notification_channels = [
    module.application.operations_notification_channel,
  ]

  conditions {
    display_name = "Production private-preview queue age exceeds five minutes"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.production_private_preview_queue_age_ms.name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 300000
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }

  depends_on = [time_sleep.production_private_preview_metrics_propagation]
}

resource "google_monitoring_alert_policy" "production_private_preview_backlog" {
  project      = local.project_id
  display_name = "Production private-preview backlog"
  combiner     = "OR"
  notification_channels = [
    module.application.operations_notification_channel,
  ]

  conditions {
    display_name = "Production private-preview queue remains non-empty"

    condition_threshold {
      filter          = "metric.type=\"cloudtasks.googleapis.com/queue/depth\" AND resource.type=\"cloud_tasks_queue\" AND resource.label.queue_id=\"agg-production-private-preview\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "300s"

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MAX"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "production_private_preview_reserved_cost" {
  project      = local.project_id
  display_name = "Production private-preview reserved cost"
  combiner     = "OR"
  notification_channels = [
    module.application.operations_notification_channel,
  ]

  conditions {
    display_name = "Production private-preview reservation differs from contract"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.production_private_preview_reserved_minor_units.name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 125
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }

  depends_on = [time_sleep.production_private_preview_metrics_propagation]
}

import {
  to = module.application.google_cloud_run_v2_service.api
  id = "projects/animated-graph-cloud-prod-jp/locations/europe-central2/services/agg-api"
}
