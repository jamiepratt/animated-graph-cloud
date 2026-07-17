locals {
  required_services = toset([
    "artifactregistry.googleapis.com",
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
  ])

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

resource "google_firestore_database" "default" {
  project     = var.project_id
  name        = "(default)"
  location_id = var.region
  type        = "FIRESTORE_NATIVE"

  deletion_policy = "ABANDON"

  depends_on = [google_project_service.required["firestore.googleapis.com"]]
}

import {
  to = google_firestore_database.default
  id = "projects/animated-graph-cloud-jp/databases/(default)"
}

resource "google_artifact_registry_repository" "containers" {
  project       = var.project_id
  location      = var.region
  repository_id = "containers"
  description   = "Animated Graph Cloud container images"
  format        = "DOCKER"

  depends_on = [google_project_service.required["artifactregistry.googleapis.com"]]
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

  attribute_condition = "assertion.repository == '${var.github_repository}'"

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
