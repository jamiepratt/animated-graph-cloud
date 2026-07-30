locals {
  project_id                   = "animated-graph-cloud-prod-jp"
  region                       = "europe-central2"
  github_repository            = "jamiepratt/animated-graph-cloud"
  github_pool_id               = "github"
  github_provider_id           = "animated-graph-cloud-proto"
  proto_branch_subject         = "repo:jamiepratt@558780/animated-graph-cloud@1303177214:ref:refs/heads/proto"
  bootstrap_tag_subject_prefix = "repo:jamiepratt@558780/animated-graph-cloud@1303177214:ref:refs/tags/proto-terraform-bootstrap-"
  deployer_account_id          = "agg-proto-github-deployer"
  deployer_email               = "${local.deployer_account_id}@${local.project_id}.iam.gserviceaccount.com"
  api_service_account          = "agg-api@${local.project_id}.iam.gserviceaccount.com"
  state_bucket                 = "${local.project_id}-tfstate"
  proto_public_base_url        = "https://proto.alphacompose.com"
  proto_firebase_site_id       = "proto-alphacompose"

  deployer_project_roles = toset([
    "roles/artifactregistry.writer",
    "roles/cloudkms.admin",
    "roles/cloudtasks.admin",
    "roles/containeranalysis.occurrences.viewer",
    "roles/firebasehosting.admin",
    "roles/iam.serviceAccountAdmin",
    "roles/iam.workloadIdentityPoolAdmin",
    "roles/resourcemanager.projectIamAdmin",
    "roles/run.admin",
    "roles/serviceusage.serviceUsageConsumer",
    "projects/${local.project_id}/roles/aggTerraformStorageAdmin",
    "projects/${local.project_id}/roles/aggTerraformSecretAdmin",
  ])

  derivative_contract_environment = {
    AGG_DERIVATIVE_ASSET_TTL_SECONDS                   = "86400"
    AGG_DERIVATIVE_ATTEMPT_RESERVATION_MINOR_UNITS     = "125"
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
    AGG_DERIVATIVE_PLAYBACK_AUTHORITY_TTL_SECONDS      = "3600"
    AGG_DERIVATIVE_CACHE_MINIMUM_REMAINING_TTL_SECONDS = "3600"
  }

  runtime_environment = merge({
    AGG_ADMIN_EMAILS                      = var.admin_emails
    AGG_AUTH_ENABLED                      = "true"
    AGG_DAILY_SUBMISSION_LIMIT            = tostring(var.daily_submission_limit)
    AGG_DERIVATIVE_BUCKET                 = google_storage_bucket.derivative_previews.name
    AGG_DERIVATIVE_DISPATCHER_URL         = var.proto_service_url
    AGG_DERIVATIVE_TASKS_QUEUE            = google_cloud_tasks_queue.derivative_preview.name
    AGG_DERIVATIVE_TASKS_SERVICE_ACCOUNT  = google_service_account.derivative_tasks.email
    AGG_DERIVATIVE_WORKER_JOB             = google_cloud_run_v2_job.derivative_preview.name
    AGG_DERIVATIVE_WORKER_SERVICE_ACCOUNT = google_service_account.derivative_worker.email
    AGG_DISPATCHER_URL                    = var.api_service_url
    AGG_JOB_LIFECYCLE_ENABLED             = "true"
    AGG_MONTHLY_BUDGET_MINOR_UNITS        = tostring(var.monthly_budget_minor_units)
    AGG_OWNER_EMAIL                       = var.owner_email
    AGG_PREVIEW_RESERVATION_MINOR_UNITS   = tostring(var.preview_reservation_minor_units)
    AGG_PUBLIC_BASE_URL                   = local.proto_public_base_url
    AGG_REGION                            = local.region
    AGG_RENDERER_JOB                      = "agg-renderer"
    AGG_RENDER_RESERVATION_MINOR_UNITS    = tostring(var.render_reservation_minor_units)
    AGG_SCHEDULER_SERVICE_ACCOUNT         = "agg-scheduler@${local.project_id}.iam.gserviceaccount.com"
    AGG_SERVICE_PROFILE                   = "proto"
    AGG_TASKS_QUEUE                       = "agg-render"
    AGG_TASKS_SERVICE_ACCOUNT             = "agg-tasks@${local.project_id}.iam.gserviceaccount.com"
    AGG_TEMPORARY_BUCKET                  = "${local.project_id}-temporary"
    GOOGLE_CLOUD_PROJECT                  = local.project_id
  }, local.derivative_contract_environment)

  derivative_worker_environment = merge({
    AGG_DERIVATIVE_BUCKET    = google_storage_bucket.derivative_previews.name
    AGG_DRIVE_SOURCE_ENABLED = "true"
    AGG_REGION               = local.region
    GOOGLE_CLOUD_PROJECT     = local.project_id
  }, local.derivative_contract_environment)

  runtime_secrets = {
    AGG_OAUTH_CLIENT_CREDENTIALS = "oauth-client-secret"
    AGG_SESSION_KEY              = "session-key"
    AGG_TOKEN_HASH_PEPPER        = "token-hash-pepper"
  }
}

data "google_project" "current" {
  project_id = local.project_id
}

data "google_service_account" "api" {
  account_id = local.api_service_account
}

resource "google_service_account" "deployer" {
  project      = local.project_id
  account_id   = local.deployer_account_id
  display_name = "Alpha Compose proto GitHub deployer"
  description  = "Branch-bound deployer for the separate proto Terraform root"
}

resource "google_service_account" "derivative_tasks" {
  project      = local.project_id
  account_id   = "agg-derivative-tasks"
  display_name = "Alpha Compose derivative task dispatcher"
  description  = "Dispatches private derivative preview work to the proto API"
}

resource "google_service_account" "derivative_worker" {
  project      = local.project_id
  account_id   = "agg-derivative-worker"
  display_name = "Alpha Compose derivative preview worker"
  description  = "Executes bounded private derivative preview jobs"
}

resource "google_storage_bucket" "derivative_previews" {
  project                     = local.project_id
  name                        = "${local.project_id}-derivative-previews"
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
}

resource "google_cloud_tasks_queue" "derivative_preview" {
  project  = local.project_id
  location = local.region
  name     = "agg-derivative-preview"

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

  depends_on = [google_project_iam_member.deployer]
}

resource "google_cloud_run_v2_job" "derivative_preview" {
  project             = local.project_id
  location            = local.region
  name                = "agg-derivative-preview"
  deletion_protection = true

  template {
    parallelism = 1
    task_count  = 1

    template {
      service_account       = google_service_account.derivative_worker.email
      execution_environment = "EXECUTION_ENVIRONMENT_GEN2"
      max_retries           = 0
      timeout               = "900s"

      containers {
        image = var.proto_image
        args  = ["clojure.main", "-m", "agg.derivative.worker"]

        dynamic "env" {
          for_each = local.derivative_worker_environment
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
    google_service_account_iam_member.deployer_uses_derivative_worker,
  ]
}

resource "google_iam_workload_identity_pool_provider" "proto" {
  project                            = local.project_id
  workload_identity_pool_id          = local.github_pool_id
  workload_identity_pool_provider_id = local.github_provider_id
  display_name                       = "Alpha Compose proto GitHub"

  attribute_mapping = {
    "google.subject"       = "assertion.repository_id + ':' + assertion.ref"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  attribute_condition = "assertion.repository == '${local.github_repository}' && (assertion.sub == '${local.proto_branch_subject}' || assertion.sub.startsWith('${local.bootstrap_tag_subject_prefix}'))"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account_iam_member" "github_deployer" {
  service_account_id = google_service_account.deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/projects/${data.google_project.current.number}/locations/global/workloadIdentityPools/${local.github_pool_id}/attribute.repository/${local.github_repository}"
}

resource "google_project_iam_member" "deployer" {
  for_each = local.deployer_project_roles

  project = local.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_storage_bucket_iam_member" "terraform_state" {
  bucket = local.state_bucket
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_service_account_iam_member" "deployer_uses_api" {
  service_account_id = data.google_service_account.api.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_service_account_iam_member" "deployer_uses_derivative_worker" {
  service_account_id = google_service_account.derivative_worker.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_storage_bucket_iam_member" "api_derivative_reader" {
  bucket = google_storage_bucket.derivative_previews.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${data.google_service_account.api.email}"
}

resource "google_storage_bucket_iam_member" "derivative_worker_objects" {
  bucket = google_storage_bucket.derivative_previews.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.derivative_worker.email}"
}

resource "google_cloud_tasks_queue_iam_member" "api_derivative_enqueuer" {
  project  = local.project_id
  location = local.region
  name     = google_cloud_tasks_queue.derivative_preview.name
  role     = "roles/cloudtasks.enqueuer"
  member   = "serviceAccount:${data.google_service_account.api.email}"
}

resource "google_cloud_tasks_queue_iam_member" "api_derivative_task_deleter" {
  project  = local.project_id
  location = local.region
  name     = google_cloud_tasks_queue.derivative_preview.name
  role     = "roles/cloudtasks.taskDeleter"
  member   = "serviceAccount:${data.google_service_account.api.email}"
}

resource "google_cloud_run_v2_job_iam_member" "api_derivative_job_executor" {
  project  = local.project_id
  location = local.region
  name     = google_cloud_run_v2_job.derivative_preview.name
  role     = "roles/run.jobsExecutorWithOverrides"
  member   = "serviceAccount:${data.google_service_account.api.email}"
}

resource "google_cloud_run_v2_job_iam_member" "api_derivative_execution_reader" {
  project  = local.project_id
  location = local.region
  name     = google_cloud_run_v2_job.derivative_preview.name
  role     = "roles/run.viewer"
  member   = "serviceAccount:${data.google_service_account.api.email}"
}

resource "google_service_account_iam_member" "api_uses_derivative_tasks_identity" {
  service_account_id = google_service_account.derivative_tasks.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${data.google_service_account.api.email}"
}

resource "google_service_account_iam_member" "tasks_service_agent_mints_derivative_oidc" {
  service_account_id = google_service_account.derivative_tasks.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-cloudtasks.iam.gserviceaccount.com"
}

resource "google_cloud_run_service_iam_member" "derivative_tasks_invoke_proto" {
  project  = local.project_id
  location = local.region
  service  = google_cloud_run_v2_service.proto.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.derivative_tasks.email}"
}

resource "google_project_iam_member" "derivative_worker_firestore" {
  project = local.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.derivative_worker.email}"
}

resource "google_kms_crypto_key_iam_member" "derivative_worker_drive_token_cipher" {
  crypto_key_id = "projects/${local.project_id}/locations/${local.region}/keyRings/application/cryptoKeys/drive-refresh-tokens"
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${google_service_account.derivative_worker.email}"

  depends_on = [google_project_iam_member.deployer]
}

resource "google_secret_manager_secret_iam_member" "derivative_worker_oauth_access" {
  project   = local.project_id
  secret_id = "oauth-client-secret"
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.derivative_worker.email}"

  depends_on = [google_project_iam_member.deployer]
}

resource "google_cloud_run_v2_service" "proto" {
  project             = local.project_id
  location            = local.region
  name                = "agg-proto"
  deletion_protection = true
  ingress             = "INGRESS_TRAFFIC_ALL"

  scaling {
    min_instance_count = 0
    max_instance_count = 2
  }

  template {
    max_instance_request_concurrency = 16
    service_account                  = data.google_service_account.api.email
    timeout                          = "300s"

    containers {
      image = var.proto_image

      dynamic "env" {
        for_each = local.runtime_environment
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.runtime_secrets
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = env.value
              version = "latest"
            }
          }
        }
      }

      ports {
        name           = "http1"
        container_port = 8080
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        cpu_idle          = true
        startup_cpu_boost = true
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
}

resource "google_cloud_run_service_iam_member" "public_invoker" {
  project  = local.project_id
  location = local.region
  service  = google_cloud_run_v2_service.proto.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_firebase_hosting_site" "proto" {
  provider = google-beta
  project  = local.project_id
  site_id  = local.proto_firebase_site_id

  lifecycle {
    prevent_destroy = true
  }
}

import {
  to = google_service_account.deployer
  id = "projects/${local.project_id}/serviceAccounts/${local.deployer_email}"
}

import {
  to = google_iam_workload_identity_pool_provider.proto
  id = "projects/${local.project_id}/locations/global/workloadIdentityPools/${local.github_pool_id}/providers/${local.github_provider_id}"
}

import {
  to = google_cloud_run_v2_service.proto
  id = "projects/${local.project_id}/locations/${local.region}/services/agg-proto"
}

import {
  to = google_firebase_hosting_site.proto
  id = "projects/${local.project_id}/sites/${local.proto_firebase_site_id}"
}
