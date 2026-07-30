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
    "roles/logging.configWriter",
    "roles/monitoring.editor",
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
    AGG_ADMIN_EMAILS         = var.admin_emails
    AGG_DERIVATIVE_BUCKET    = google_storage_bucket.derivative_previews.name
    AGG_DRIVE_SOURCE_ENABLED = "true"
    AGG_OWNER_EMAIL          = var.owner_email
    AGG_REGION               = local.region
    GOOGLE_CLOUD_PROJECT     = local.project_id
  }, local.derivative_contract_environment)

  runtime_secrets = {
    AGG_OAUTH_CLIENT_CREDENTIALS = "oauth-client-secret"
    AGG_PROTO_SOURCE_FILE_IDS    = "proto-source-file-ids"
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

data "google_service_account" "scheduler" {
  account_id = "agg-scheduler@${local.project_id}.iam.gserviceaccount.com"
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

resource "google_cloud_scheduler_job" "derivative_reconcile" {
  project          = local.project_id
  region           = local.region
  name             = "agg-derivative-reconcile"
  description      = "Reconciles timed-out and cancelled derivative previews"
  schedule         = "* * * * *"
  time_zone        = "Etc/UTC"
  attempt_deadline = "60s"

  retry_config {
    retry_count          = 3
    max_retry_duration   = "300s"
    min_backoff_duration = "5s"
    max_backoff_duration = "60s"
    max_doublings        = 3
  }

  http_target {
    uri         = "${var.proto_service_url}/internal/v1/derivative-preparations/reconcile"
    http_method = "POST"

    headers = {
      X-CloudScheduler = "true"
    }

    oidc_token {
      service_account_email = data.google_service_account.scheduler.email
      audience              = var.proto_service_url
    }
  }

  depends_on = [
    google_project_iam_member.deployer,
    google_service_account_iam_member.deployer_uses_scheduler,
  ]
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
    google_service_account_iam_member.deployer_uses_derivative_worker,
    google_secret_manager_secret_iam_member.derivative_worker_oauth_access,
    google_secret_manager_secret_iam_member.derivative_worker_token_hash_pepper_access,
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

resource "google_service_account_iam_member" "deployer_uses_scheduler" {
  service_account_id = data.google_service_account.scheduler.name
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

resource "google_secret_manager_secret_iam_member" "derivative_worker_token_hash_pepper_access" {
  project   = local.project_id
  secret_id = "token-hash-pepper"
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.derivative_worker.email}"

  depends_on = [google_project_iam_member.deployer]
}

resource "google_secret_manager_secret_iam_member" "proto_source_file_ids_access" {
  project   = local.project_id
  secret_id = "proto-source-file-ids"
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${data.google_service_account.api.email}"

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

  depends_on = [
    google_secret_manager_secret_iam_member.proto_source_file_ids_access,
  ]
}

resource "google_cloud_run_service_iam_member" "public_invoker" {
  project  = local.project_id
  location = local.region
  service  = google_cloud_run_v2_service.proto.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_logging_metric" "derivative_preparation_latency_ms" {
  project         = local.project_id
  depends_on      = [google_project_iam_member.deployer]
  name            = "alpha_compose_proto/derivative_preparation_latency_ms"
  description     = "Successful derivative preparation latency in milliseconds"
  filter          = "jsonPayload.event=\"derivative_preparation_terminal\" AND jsonPayload.status=\"succeeded\""
  value_extractor = "EXTRACT(jsonPayload.elapsedMs)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "ms"
    display_name = "Derivative preparation latency"
  }

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 12
      growth_factor      = 2
      scale              = 1000
    }
  }
}

resource "google_logging_metric" "derivative_cache_hits" {
  project     = local.project_id
  depends_on  = [google_project_iam_member.deployer]
  name        = "alpha_compose_proto/derivative_cache_hits"
  description = "Eligible derivative preparation cache hits"
  filter      = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"derivative_cache_hit\" AND jsonPayload.cacheOutcome=\"hit\""

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Derivative cache hits"
  }
}

resource "google_logging_metric" "derivative_cache_misses" {
  project     = local.project_id
  depends_on  = [google_project_iam_member.deployer]
  name        = "alpha_compose_proto/derivative_cache_misses"
  description = "Derivative preparation cache misses"
  filter      = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"derivative_cache_miss\" AND jsonPayload.cacheOutcome=\"miss\""

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Derivative cache misses"
  }
}

resource "google_logging_metric" "derivative_failures" {
  project     = local.project_id
  depends_on  = [google_project_iam_member.deployer]
  name        = "alpha_compose_proto/derivative_failures"
  description = "Terminal derivative failures grouped by bounded reason"
  filter      = "jsonPayload.event=\"derivative_preparation_terminal\" AND jsonPayload.status=\"failed\""

  label_extractors = {
    reason = "EXTRACT(jsonPayload.reason)"
  }

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Derivative failures"

    labels {
      key         = "reason"
      value_type  = "STRING"
      description = "Bounded derivative failure reason"
    }
  }
}

resource "google_logging_metric" "derivative_timeouts" {
  project     = local.project_id
  depends_on  = [google_project_iam_member.deployer]
  name        = "alpha_compose_proto/derivative_timeouts"
  description = "Derivative attempts that exceeded a bounded deadline"
  filter      = "jsonPayload.event=\"derivative_preparation_terminal\" AND jsonPayload.reason=\"derivative_timeout\""

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Derivative timeouts"
  }
}

resource "google_logging_metric" "derivative_drive_bytes" {
  project         = local.project_id
  depends_on      = [google_project_iam_member.deployer]
  name            = "alpha_compose_proto/derivative_drive_bytes"
  description     = "Drive bytes transferred through the derivative range proxy"
  filter          = "resource.type=\"cloud_run_job\" AND jsonPayload.event=\"derivative_encode_exited\" AND jsonPayload.status=\"succeeded\""
  value_extractor = "EXTRACT(jsonPayload.upstreamBytes)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "By"
    display_name = "Derivative Drive bytes"
  }

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 16
      growth_factor      = 2
      scale              = 1048576
    }
  }
}

resource "google_logging_metric" "derivative_output_bytes" {
  project         = local.project_id
  depends_on      = [google_project_iam_member.deployer]
  name            = "alpha_compose_proto/derivative_output_bytes"
  description     = "Verified derivative bytes published to private storage"
  filter          = "resource.type=\"cloud_run_job\" AND jsonPayload.event=\"derivative_preparation_terminal\" AND jsonPayload.status=\"succeeded\""
  value_extractor = "EXTRACT(jsonPayload.outputBytes)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "By"
    display_name = "Derivative output bytes"
  }

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 12
      growth_factor      = 2
      scale              = 1048576
    }
  }
}

resource "google_logging_metric" "derivative_cancellation_lag_ms" {
  project         = local.project_id
  depends_on      = [google_project_iam_member.deployer]
  name            = "alpha_compose_proto/derivative_cancellation_lag_ms"
  description     = "Lag between cancellation request and terminal acknowledgement"
  filter          = "jsonPayload.event=\"derivative_preparation_terminal\" AND jsonPayload.status=(\"cancelled\" OR \"expired\")"
  value_extractor = "EXTRACT(jsonPayload.cancellationLagMs)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "ms"
    display_name = "Derivative cancellation lag"
  }

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 12
      growth_factor      = 2
      scale              = 100
    }
  }
}

resource "google_logging_metric" "derivative_queue_age_ms" {
  project         = local.project_id
  depends_on      = [google_project_iam_member.deployer]
  name            = "alpha_compose_proto/derivative_queue_age_ms"
  description     = "Queue age when a derivative preparation dispatches"
  filter          = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"derivative_preparation_dispatched\""
  value_extractor = "EXTRACT(jsonPayload.queueAgeMs)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "ms"
    display_name = "Derivative queue age"
  }

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 12
      growth_factor      = 2
      scale              = 1000
    }
  }
}

resource "google_logging_metric" "derivative_reserved_minor_units" {
  project         = local.project_id
  depends_on      = [google_project_iam_member.deployer]
  name            = "alpha_compose_proto/derivative_reserved_minor_units"
  description     = "Minor PLN units reserved by admitted derivative attempts"
  filter          = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"derivative_preparation_submitted\" AND jsonPayload.reservedMinorUnits>0"
  value_extractor = "EXTRACT(jsonPayload.reservedMinorUnits)"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Derivative reserved minor units"
  }

  bucket_options {
    linear_buckets {
      num_finite_buckets = 20
      width              = 125
      offset             = 0
    }
  }
}

resource "google_logging_metric" "derivative_reservation_rejections" {
  project     = local.project_id
  depends_on  = [google_project_iam_member.deployer]
  name        = "alpha_compose_proto/derivative_reservation_rejections"
  description = "Derivative requests rejected by a bounded reservation ceiling"
  filter      = "resource.type=\"cloud_run_revision\" AND jsonPayload.event=\"derivative_preparation_terminal\" AND jsonPayload.status=\"rejected\" AND jsonPayload.reason=(\"derivative_user_budget_exhausted\" OR \"derivative_pool_budget_exhausted\" OR \"project_budget_exhausted\")"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    display_name = "Derivative reservation rejections"
  }
}

resource "google_monitoring_notification_channel" "proto_owner_email" {
  project      = local.project_id
  depends_on   = [google_project_iam_member.deployer]
  display_name = "Alpha Compose proto owner"
  type         = "email"

  labels = {
    email_address = var.owner_email
  }
}

resource "google_monitoring_alert_policy" "derivative_latency" {
  project      = local.project_id
  display_name = "Proto derivative preparation latency"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "Derivative p99 preparation latency exceeds ten minutes"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.derivative_preparation_latency_ms.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 600000
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "derivative_cache_ratio" {
  project      = local.project_id
  display_name = "Proto derivative cache ratio"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "Derivative cache hit ratio remains below fifty percent"

    condition_prometheus_query_language {
      query               = <<-EOT
        # cache_hits / (cache_hits + cache_misses), with ten observations minimum.
        (
          sum(increase({"logging.googleapis.com/user/alpha_compose_proto/derivative_cache_hits", monitored_resource="cloud_run_revision"}[10m]))
          /
          (
            sum(increase({"logging.googleapis.com/user/alpha_compose_proto/derivative_cache_hits", monitored_resource="cloud_run_revision"}[10m]))
            +
            sum(increase({"logging.googleapis.com/user/alpha_compose_proto/derivative_cache_misses", monitored_resource="cloud_run_revision"}[10m]))
          )
        ) < 0.5
        and
        (
          sum(increase({"logging.googleapis.com/user/alpha_compose_proto/derivative_cache_hits", monitored_resource="cloud_run_revision"}[10m]))
          +
          sum(increase({"logging.googleapis.com/user/alpha_compose_proto/derivative_cache_misses", monitored_resource="cloud_run_revision"}[10m]))
        ) >= 10
      EOT
      duration            = "600s"
      evaluation_interval = "300s"
    }
  }

  documentation {
    content   = "The ten-minute cache hit ratio fell below 50% with at least ten resolved cache decisions."
    mime_type = "text/markdown"
  }

  depends_on = [
    google_logging_metric.derivative_cache_hits,
    google_logging_metric.derivative_cache_misses,
  ]
}

resource "google_monitoring_alert_policy" "derivative_failures" {
  project      = local.project_id
  display_name = "Proto derivative failures"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "A derivative worker failed"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.derivative_failures.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "derivative_timeouts" {
  project      = local.project_id
  display_name = "Proto derivative timeouts"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "A derivative attempt exceeded its deadline"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.derivative_timeouts.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "derivative_drive_bytes" {
  project      = local.project_id
  display_name = "Proto derivative Drive byte cost"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "Derivative Drive transfer reaches the bounded envelope"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.derivative_drive_bytes.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 2147483648
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "derivative_output_bytes" {
  project      = local.project_id
  display_name = "Proto derivative output byte cost"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "Derivative output approaches its size ceiling"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.derivative_output_bytes.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 201326592
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "derivative_cancellation_lag" {
  project      = local.project_id
  display_name = "Proto derivative cancellation lag"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "Derivative cancellation acknowledgement exceeds one minute"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.derivative_cancellation_lag_ms.name}\" AND resource.type=\"cloud_run_job\""
      comparison      = "COMPARISON_GT"
      threshold_value = 60000
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "derivative_queue_age" {
  project      = local.project_id
  display_name = "Proto derivative queue age"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "Derivative queue age exceeds five minutes"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.derivative_queue_age_ms.name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 300000
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "derivative_backlog_depth" {
  project      = local.project_id
  display_name = "Proto derivative backlog depth"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "Derivative Cloud Tasks queue remains non-empty"

    condition_threshold {
      filter          = "metric.type=\"cloudtasks.googleapis.com/queue/depth\" AND resource.type=\"cloud_tasks_queue\" AND resource.label.queue_id=\"agg-derivative-preview\""
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

resource "google_monitoring_alert_policy" "derivative_reservation_rejections" {
  project      = local.project_id
  display_name = "Proto derivative reservation rejection"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "A derivative reservation was rejected"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.derivative_reservation_rejections.name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_SUM"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "derivative_reserved_cost" {
  project      = local.project_id
  display_name = "Proto derivative reserved cost"
  combiner     = "OR"
  notification_channels = [
    google_monitoring_notification_channel.proto_owner_email.name,
  ]

  conditions {
    display_name = "Derivative attempt reservation differs from contract"

    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.derivative_reserved_minor_units.name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 125
      duration        = "0s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_PERCENTILE_99"
      }
    }
  }
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
