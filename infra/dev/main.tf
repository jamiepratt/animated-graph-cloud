locals {
  required_services = setunion(toset([
    "billingbudgets.googleapis.com",
    "cloudbilling.googleapis.com",
    "cloudkms.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "cloudscheduler.googleapis.com",
    "cloudtasks.googleapis.com",
    "containeranalysis.googleapis.com",
    "containerscanning.googleapis.com",
    "drive.googleapis.com",
    "firestore.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "picker.googleapis.com",
    "pubsub.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "serviceusage.googleapis.com",
    "storage.googleapis.com",
    "sts.googleapis.com",
    ]), var.enable_firebase_hosting ? toset([
    "firebase.googleapis.com",
    "firebasehosting.googleapis.com",
  ]) : toset([]))

  secret_ids = toset([
    "oauth-client-secret",
    "picker-api-key",
    "session-key",
    "token-hash-pepper",
  ])
}

data "google_project" "current" {
  project_id = var.project_id
}

resource "google_project_service" "required" {
  for_each = local.required_services

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_project_service" "artifact_registry" {
  project            = var.project_id
  service            = "artifactregistry.googleapis.com"
  disable_on_destroy = false
}

moved {
  from = google_project_service.required["artifactregistry.googleapis.com"]
  to   = google_project_service.artifact_registry
}

resource "google_firestore_database" "default" {
  project     = var.project_id
  name        = "(default)"
  location_id = var.region
  type        = "FIRESTORE_NATIVE"

  deletion_policy = "ABANDON"

  depends_on = [google_project_service.required["firestore.googleapis.com"]]
}

import {
  for_each = var.import_default_firestore ? toset([var.project_id]) : toset([])

  to = google_firestore_database.default
  id = "projects/${each.value}/databases/(default)"
}

resource "google_artifact_registry_repository" "containers" {
  project       = var.project_id
  location      = var.region
  repository_id = "containers"
  description   = "Animated Graph Cloud container images"
  format        = "DOCKER"

  depends_on = [google_project_service.artifact_registry]
}

resource "google_storage_bucket" "temporary" {
  project                     = var.project_id
  name                        = "${var.project_id}-temporary"
  location                    = var.region
  storage_class               = "STANDARD"
  public_access_prevention    = "enforced"
  uniform_bucket_level_access = true

  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      age = 1
    }
  }
}

resource "google_kms_key_ring" "application" {
  project  = var.project_id
  name     = "application"
  location = var.region

  depends_on = [google_project_service.required["cloudkms.googleapis.com"]]
}

resource "google_kms_crypto_key" "drive_tokens" {
  name            = "drive-refresh-tokens"
  key_ring        = google_kms_key_ring.application.id
  rotation_period = "7776000s"

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_secret_manager_secret" "application" {
  for_each = local.secret_ids

  project   = var.project_id
  secret_id = each.value

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.required["secretmanager.googleapis.com"]]
}

resource "google_service_account" "api" {
  project      = var.project_id
  account_id   = "agg-api"
  display_name = "Animated Graph Cloud API"
}

resource "google_service_account" "renderer" {
  project      = var.project_id
  account_id   = "agg-renderer"
  display_name = "Animated Graph Cloud renderer"
}

resource "google_service_account" "tasks" {
  project      = var.project_id
  account_id   = "agg-tasks"
  display_name = "Animated Graph Cloud task dispatcher"
}

resource "google_service_account" "scheduler" {
  project      = var.project_id
  account_id   = "agg-scheduler"
  display_name = "Animated Graph Cloud reconciliation scheduler"
}

resource "google_service_account" "deployer" {
  project      = var.project_id
  account_id   = "agg-github-deployer"
  display_name = "Animated Graph Cloud GitHub deployer"
}

resource "google_cloud_run_v2_job" "renderer" {
  project             = var.project_id
  location            = var.region
  name                = "agg-renderer"
  deletion_protection = false

  template {
    parallelism = 1
    task_count  = 1

    template {
      service_account       = google_service_account.renderer.email
      execution_environment = "EXECUTION_ENVIRONMENT_GEN2"
      max_retries           = 0
      timeout               = "3600s"

      containers {
        image = var.renderer_image
        args  = ["clojure.main", "-m", "agg.renderer.main"]

        env {
          name  = "AGG_TEMPORARY_BUCKET"
          value = google_storage_bucket.temporary.name
        }

        env {
          name  = "AGG_REGION"
          value = var.region
        }

        env {
          name  = "AGG_DRIVE_DELIVERY_ENABLED"
          value = "true"
        }

        env {
          name = "AGG_OAUTH_CLIENT_CREDENTIALS"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.application["oauth-client-secret"].secret_id
              version = "latest"
            }
          }
        }

        resources {
          limits = {
            cpu    = "8"
            memory = "32Gi"
          }
        }
      }
    }
  }

  depends_on = [google_project_service.required["run.googleapis.com"]]
}

resource "google_cloud_tasks_queue" "render" {
  project  = var.project_id
  location = var.region
  name     = "agg-render"

  rate_limits {
    max_concurrent_dispatches = 5
    max_dispatches_per_second = 5
  }

  retry_config {
    max_attempts       = 100
    max_retry_duration = "3600s"
    min_backoff        = "5s"
    max_backoff        = "300s"
    max_doublings      = 5
  }

  depends_on = [google_project_service.required["cloudtasks.googleapis.com"]]
}

resource "google_firestore_field" "job_expiry" {
  project    = var.project_id
  database   = google_firestore_database.default.name
  collection = "jobs"
  field      = "expireAt"

  ttl_config {}
}

resource "google_cloud_scheduler_job" "reconcile" {
  count = var.api_service_url == "" ? 0 : 1

  project = var.project_id
  region  = var.region
  name    = "agg-reconcile"

  description      = "Repair stale render jobs and orphaned capacity leases"
  schedule         = "*/5 * * * *"
  time_zone        = "Etc/UTC"
  attempt_deadline = "60s"

  retry_config {
    retry_count          = 3
    min_backoff_duration = "10s"
    max_backoff_duration = "60s"
    max_doublings        = 2
  }

  http_target {
    http_method = "POST"
    uri         = "${var.api_service_url}/internal/v1/jobs/reconcile"
    headers     = { "Content-Type" = "application/json" }

    oidc_token {
      service_account_email = google_service_account.scheduler.email
      audience              = var.api_service_url
    }
  }

  depends_on = [google_project_service.required["cloudscheduler.googleapis.com"]]
}

resource "google_billing_budget" "development" {
  billing_account = trimprefix(data.google_project.current.billing_account,
  "billingAccounts/")
  display_name    = "Alpha Compose ${var.environment_name}"
  deletion_policy = "ABANDON"

  budget_filter {
    calendar_period = "MONTH"
    projects        = ["projects/${data.google_project.current.number}"]
  }

  amount {
    specified_amount {
      currency_code = "PLN"
      units         = tostring(var.monthly_budget_pln)
    }
  }

  threshold_rules {
    threshold_percent = 0.5
  }

  threshold_rules {
    threshold_percent = 0.8
  }

  threshold_rules {
    threshold_percent = 1.0
  }

  all_updates_rule {
    monitoring_notification_channels = [
      google_monitoring_notification_channel.owner_email.name,
    ]
    disable_default_iam_recipients = false
  }

  depends_on = [google_project_service.required["billingbudgets.googleapis.com"]]
}

resource "google_logging_metric" "queue_age_ms" {
  project = var.project_id
  name    = "animated_graph_cloud/queue_age_ms"

  description     = "Queue age in milliseconds when a render is dispatched"
  filter          = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"render_dispatched\""
  value_extractor = "EXTRACT(jsonPayload.queueAgeMs)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "ms"
    display_name = "Animated Graph Cloud queue age"
  }

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 12
      growth_factor      = 2
      scale              = 1000
    }
  }
}

resource "google_logging_metric" "render_failures" {
  project = var.project_id
  name    = "animated_graph_cloud/render_failures"

  description = "Failed durable cloud renderer executions"
  filter      = "(resource.type=\"cloud_run_job\" AND jsonPayload.event=\"cloud_render_failed\") OR (resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"job_failed\")"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Animated Graph Cloud render failures"
  }
}

resource "google_logging_metric" "stale_leases" {
  project = var.project_id
  name    = "animated_graph_cloud/stale_leases"

  description = "Reconciliation runs that release stale or orphaned leases"
  filter      = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"reconciliation_complete\" AND jsonPayload.releasedLeases>0"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Animated Graph Cloud stale leases"
  }
}

resource "google_logging_metric" "drive_reauthorization" {
  project = var.project_id
  name    = "animated_graph_cloud/drive_reauthorization"

  description = "Cloud renders blocked on a revoked Drive grant"
  filter      = "resource.type=\"cloud_run_job\" AND jsonPayload.event=\"drive_reauthorization_required\""

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Animated Graph Cloud Drive reauthorization"
  }
}

resource "google_logging_metric" "budget_admission_rejections" {
  project = var.project_id
  name    = "animated_graph_cloud/budget_admission_rejections"

  description = "Submissions rejected by the monthly compute admission ceiling"
  filter      = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"admission_rejected\" AND jsonPayload.reason=\"monthly_budget_exhausted\""

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Animated Graph Cloud budget admission rejections"
  }
}

resource "google_monitoring_notification_channel" "owner_email" {
  project      = var.project_id
  display_name = "Animated Graph Cloud owner email"
  type         = "email"

  labels = {
    email_address = var.operations_alert_email
  }

  depends_on = [google_project_service.required["monitoring.googleapis.com"]]
}

resource "google_monitoring_alert_policy" "queue_age" {
  project      = var.project_id
  display_name = "Animated Graph Cloud queue age"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.owner_email.name,
  ]

  conditions {
    display_name = "Queue age exceeds five minutes"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.queue_age_ms.name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 300000
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }

  documentation {
    content   = "Inspect Cloud Tasks backlog and API dispatch errors."
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "backlog_depth" {
  project      = var.project_id
  display_name = "Animated Graph Cloud sustained backlog"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.owner_email.name,
  ]

  conditions {
    display_name = "Sustained Cloud Tasks backlog"

    condition_threshold {
      filter          = "metric.type=\"cloudtasks.googleapis.com/queue/depth\" AND resource.type=\"cloud_tasks_queue\" AND resource.label.queue_id=\"agg-render\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "300s"

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MAX"
      }
    }
  }

  documentation {
    content   = "The render queue has remained non-empty for five minutes. Inspect Cloud Tasks response codes, especially OIDC and API authentication failures."
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "render_failures" {
  project      = var.project_id
  display_name = "Animated Graph Cloud render failures"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.owner_email.name,
  ]

  conditions {
    display_name = "A durable renderer execution failed"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.render_failures.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }

  conditions {
    display_name = "A renderer launch failed"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.render_failures.name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }

  documentation {
    content   = "Inspect the generic renderer failure event and durable job failure code."
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "memory_utilization" {
  project      = var.project_id
  display_name = "Animated Graph Cloud renderer memory"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.owner_email.name,
  ]

  conditions {
    display_name = "Renderer memory utilization exceeds 90 percent"

    condition_threshold {
      filter          = "metric.type=\"run.googleapis.com/container/memory/utilizations\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0.9
      duration        = "300s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }

  documentation {
    content   = "Inspect the durable renderer execution and the 32 GiB memory ceiling."
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "stale_leases" {
  project      = var.project_id
  display_name = "Animated Graph Cloud stale leases"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.owner_email.name,
  ]

  conditions {
    display_name = "Reconciliation released stale capacity"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.stale_leases.name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }

  documentation {
    content   = "Inspect Cloud Run execution termination and the repaired Firestore job."
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "drive_reauthorization" {
  project      = var.project_id
  display_name = "Animated Graph Cloud Drive reauthorization"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.owner_email.name,
  ]

  conditions {
    display_name = "A Drive grant requires reauthorization"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.drive_reauthorization.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }

  documentation {
    content   = "Ask the affected allowlisted user to reconnect Google Drive; logs contain no subject or token."
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "budget_admission" {
  project      = var.project_id
  display_name = "Animated Graph Cloud budget admission"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.owner_email.name,
  ]

  conditions {
    display_name = "Application rejected work at the monthly ceiling"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.budget_admission_rejections.name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }

  documentation {
    content   = "Budget notifications are advisory; Firestore admission reservations enforce the ceiling."
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_dashboard" "operations" {
  project = var.project_id
  dashboard_json = jsonencode({
    displayName = "Animated Graph Cloud operations"
    mosaicLayout = {
      columns = 12
      tiles = [
        {
          width = 6, height = 4
          widget = {
            title = "Queue age"
            xyChart = {
              dataSets = [{
                plotType   = "LINE"
                targetAxis = "Y1"
                timeSeriesQuery = { timeSeriesFilter = {
                  filter      = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.queue_age_ms.name}\" AND resource.type=\"cloud_run_revision\""
                  aggregation = { alignmentPeriod = "300s", perSeriesAligner = "ALIGN_PERCENTILE_99" }
                } }
              }]
              yAxis = { label = "ms", scale = "LINEAR" }
            }
          }
        },
        {
          xPos = 6, width = 6, height = 4
          widget = {
            title = "Render failures"
            xyChart = {
              dataSets = [
                {
                  plotType   = "LINE"
                  targetAxis = "Y1"
                  timeSeriesQuery = { timeSeriesFilter = {
                    filter      = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.render_failures.name}\" AND resource.type=\"cloud_run_job\""
                    aggregation = { alignmentPeriod = "300s", perSeriesAligner = "ALIGN_SUM" }
                  } }
                },
                {
                  plotType   = "LINE"
                  targetAxis = "Y1"
                  timeSeriesQuery = { timeSeriesFilter = {
                    filter      = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.render_failures.name}\" AND resource.type=\"cloud_run_revision\""
                    aggregation = { alignmentPeriod = "300s", perSeriesAligner = "ALIGN_SUM" }
                  } }
                }
              ]
            }
          }
        },
        {
          yPos = 4, width = 6, height = 4
          widget = {
            title = "Memory utilization"
            xyChart = {
              dataSets = [{
                plotType   = "LINE"
                targetAxis = "Y1"
                timeSeriesQuery = { timeSeriesFilter = {
                  filter      = "metric.type=\"run.googleapis.com/container/memory/utilizations\" AND resource.type=\"cloud_run_job\""
                  aggregation = { alignmentPeriod = "300s", perSeriesAligner = "ALIGN_PERCENTILE_99" }
                } }
              }]
              yAxis = { label = "utilization", scale = "LINEAR" }
            }
          }
        },
        {
          xPos = 6, yPos = 4, width = 6, height = 4
          widget = {
            title = "Stale leases"
            xyChart = {
              dataSets = [{
                plotType   = "LINE"
                targetAxis = "Y1"
                timeSeriesQuery = { timeSeriesFilter = {
                  filter      = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.stale_leases.name}\" AND resource.type=\"cloud_run_revision\""
                  aggregation = { alignmentPeriod = "300s", perSeriesAligner = "ALIGN_SUM" }
                } }
              }]
            }
          }
        },
        {
          yPos = 8, width = 6, height = 4
          widget = {
            title = "Drive reauthorization"
            xyChart = {
              dataSets = [{
                plotType   = "LINE"
                targetAxis = "Y1"
                timeSeriesQuery = { timeSeriesFilter = {
                  filter      = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.drive_reauthorization.name}\" AND resource.type=\"cloud_run_job\""
                  aggregation = { alignmentPeriod = "300s", perSeriesAligner = "ALIGN_SUM" }
                } }
              }]
            }
          }
        },
        {
          xPos = 6, yPos = 8, width = 6, height = 4
          widget = {
            title = "Budget admission"
            xyChart = {
              dataSets = [{
                plotType   = "LINE"
                targetAxis = "Y1"
                timeSeriesQuery = { timeSeriesFilter = {
                  filter      = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.budget_admission_rejections.name}\" AND resource.type=\"cloud_run_revision\""
                  aggregation = { alignmentPeriod = "300s", perSeriesAligner = "ALIGN_SUM" }
                } }
              }]
            }
          }
        },
        {
          yPos = 12, width = 6, height = 4
          widget = {
            title = "Cloud Tasks backlog depth"
            xyChart = {
              dataSets = [{
                plotType   = "LINE"
                targetAxis = "Y1"
                timeSeriesQuery = { timeSeriesFilter = {
                  filter      = "metric.type=\"cloudtasks.googleapis.com/queue/depth\" AND resource.type=\"cloud_tasks_queue\" AND resource.label.queue_id=\"agg-render\""
                  aggregation = { alignmentPeriod = "60s", perSeriesAligner = "ALIGN_MAX" }
                } }
              }]
              yAxis = { label = "tasks", scale = "LINEAR" }
            }
          }
        }
      ]
    }
  })
}

resource "google_storage_bucket_iam_member" "renderer_temporary_object_creator" {
  bucket = google_storage_bucket.temporary.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.renderer.email}"
}

resource "google_storage_bucket_iam_member" "renderer_temporary_object_viewer" {
  bucket = google_storage_bucket.temporary.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.renderer.email}"
}

resource "google_storage_bucket_iam_member" "api_temporary_object_creator" {
  bucket = google_storage_bucket.temporary.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.api.email}"
}

resource "google_storage_bucket_iam_member" "api_temporary_object_viewer" {
  bucket = google_storage_bucket.temporary.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.api.email}"
}

resource "google_project_iam_member" "api_firestore_user" {
  project = var.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.api.email}"
}

resource "google_project_iam_member" "renderer_firestore_user" {
  project = var.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.renderer.email}"
}

resource "google_kms_crypto_key_iam_member" "api_drive_token_cipher" {
  crypto_key_id = google_kms_crypto_key.drive_tokens.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${google_service_account.api.email}"
}

resource "google_kms_crypto_key_iam_member" "renderer_drive_token_cipher" {
  crypto_key_id = google_kms_crypto_key.drive_tokens.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${google_service_account.renderer.email}"
}

resource "google_secret_manager_secret_iam_member" "oauth_runtime_access" {
  for_each = toset([
    google_service_account.api.email,
    google_service_account.renderer.email,
  ])

  project   = var.project_id
  secret_id = google_secret_manager_secret.application["oauth-client-secret"].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${each.value}"
}

resource "google_secret_manager_secret_iam_member" "api_session_access" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.application["session-key"].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.api.email}"
}

resource "google_secret_manager_secret_iam_member" "api_picker_access" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.application["picker-api-key"].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.api.email}"
}

resource "google_secret_manager_secret_iam_member" "api_token_hash_access" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.application["token-hash-pepper"].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.api.email}"
}

resource "google_project_iam_member" "api_tasks_enqueuer" {
  project = var.project_id
  role    = "roles/cloudtasks.enqueuer"
  member  = "serviceAccount:${google_service_account.api.email}"
}

resource "google_project_iam_member" "api_run_invoker" {
  project = var.project_id
  role    = "roles/run.jobsExecutorWithOverrides"
  member  = "serviceAccount:${google_service_account.api.email}"
}

resource "google_project_iam_custom_role" "api_execution_reader" {
  project     = var.project_id
  role_id     = "aggExecutionReader"
  title       = "Animated Graph Cloud execution reader"
  description = "Read Cloud Run execution identity and overridden arguments for durable reconciliation"
  permissions = ["run.executions.get", "run.executions.list"]
}

resource "google_project_iam_member" "api_execution_reader" {
  project = var.project_id
  role    = google_project_iam_custom_role.api_execution_reader.id
  member  = "serviceAccount:${google_service_account.api.email}"
}

resource "google_project_iam_member" "tasks_run_invoker" {
  project = var.project_id
  role    = "roles/run.invoker"
  member  = "serviceAccount:${google_service_account.tasks.email}"
}

resource "google_service_account_iam_member" "api_uses_tasks_identity" {
  service_account_id = google_service_account.tasks.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.api.email}"
}

resource "google_service_account_iam_member" "tasks_service_agent_mints_oidc" {
  service_account_id = google_service_account.tasks.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-cloudtasks.iam.gserviceaccount.com"
}

resource "google_service_account_iam_member" "scheduler_service_agent_mints_oidc" {
  service_account_id = google_service_account.scheduler.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-cloudscheduler.iam.gserviceaccount.com"
}

resource "google_service_account_iam_member" "api_signs_uploads" {
  service_account_id = google_service_account.api.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${google_service_account.api.email}"
}

resource "google_storage_bucket_iam_member" "deployer_temporary_object_viewer" {
  bucket = google_storage_bucket.temporary.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_iam_workload_identity_pool" "github" {
  project                   = var.project_id
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"

  depends_on = [google_project_service.required["iam.googleapis.com"]]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  project                            = var.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "animated-graph-cloud"
  display_name                       = "animated-graph-cloud repository"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  attribute_condition = var.github_subject == "" ? "assertion.repository == '${var.github_repository}'" : "assertion.repository == '${var.github_repository}' && assertion.sub == '${var.github_subject}'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account_iam_member" "github_deployer" {
  service_account_id = google_service_account.deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

resource "google_project_iam_member" "deployer_artifact_writer" {
  project = var.project_id
  role    = "roles/artifactregistry.writer"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_project_iam_member" "deployer_run_admin" {
  project = var.project_id
  role    = "roles/run.admin"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_project_iam_member" "deployer_log_viewer" {
  project = var.project_id
  role    = "roles/logging.viewer"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_project_iam_member" "deployer_firebase_hosting_admin" {
  count = var.enable_firebase_hosting ? 1 : 0

  project = var.project_id
  role    = "roles/firebasehosting.admin"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_service_account_iam_member" "deployer_uses_api" {
  service_account_id = google_service_account.api.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_service_account_iam_member" "deployer_uses_renderer" {
  service_account_id = google_service_account.renderer.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}
