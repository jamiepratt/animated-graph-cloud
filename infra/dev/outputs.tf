output "artifact_registry_repository" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.containers.repository_id}"
}

output "deployer_service_account" {
  value = google_service_account.deployer.email
}

output "github_workload_identity_provider" {
  value = google_iam_workload_identity_pool_provider.github.name
}

output "temporary_bucket" {
  value = google_storage_bucket.temporary.url
}

output "renderer_job" {
  value = google_cloud_run_v2_job.renderer.id
}
