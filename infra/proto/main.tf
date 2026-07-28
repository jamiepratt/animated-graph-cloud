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
    "roles/containeranalysis.occurrences.viewer",
    "roles/firebasehosting.admin",
    "roles/iam.serviceAccountAdmin",
    "roles/iam.workloadIdentityPoolAdmin",
    "roles/resourcemanager.projectIamAdmin",
    "roles/run.admin",
    "roles/serviceusage.serviceUsageConsumer",
    "projects/${local.project_id}/roles/aggTerraformStorageAdmin",
  ])

  runtime_environment = {
    AGG_ADMIN_EMAILS                    = var.admin_emails
    AGG_AUTH_ENABLED                    = "true"
    AGG_DAILY_SUBMISSION_LIMIT          = tostring(var.daily_submission_limit)
    AGG_DISPATCHER_URL                  = var.api_service_url
    AGG_JOB_LIFECYCLE_ENABLED           = "true"
    AGG_MONTHLY_BUDGET_MINOR_UNITS      = tostring(var.monthly_budget_minor_units)
    AGG_OWNER_EMAIL                     = var.owner_email
    AGG_PREVIEW_RESERVATION_MINOR_UNITS = tostring(var.preview_reservation_minor_units)
    AGG_PUBLIC_BASE_URL                 = local.proto_public_base_url
    AGG_REGION                          = local.region
    AGG_RENDERER_JOB                    = "agg-renderer"
    AGG_RENDER_RESERVATION_MINOR_UNITS  = tostring(var.render_reservation_minor_units)
    AGG_SCHEDULER_SERVICE_ACCOUNT       = "agg-scheduler@${local.project_id}.iam.gserviceaccount.com"
    AGG_SERVICE_PROFILE                 = "proto"
    AGG_TASKS_QUEUE                     = "agg-render"
    AGG_TASKS_SERVICE_ACCOUNT           = "agg-tasks@${local.project_id}.iam.gserviceaccount.com"
    AGG_TEMPORARY_BUCKET                = "${local.project_id}-temporary"
    GOOGLE_CLOUD_PROJECT                = local.project_id
  }

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

resource "google_iam_workload_identity_pool_provider" "proto" {
  project                            = local.project_id
  workload_identity_pool_id          = local.github_pool_id
  workload_identity_pool_provider_id = local.github_provider_id
  display_name                       = "animated-graph-cloud proto branch"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
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
