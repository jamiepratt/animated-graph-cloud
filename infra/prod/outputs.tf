output "artifact_registry_repository" {
  value = module.application.artifact_registry_repository
}

output "deployer_service_account" {
  value = module.application.deployer_service_account
}

output "github_workload_identity_provider" {
  value = module.application.github_workload_identity_provider
}

output "temporary_bucket" {
  value = module.application.temporary_bucket
}

output "renderer_job" {
  value = module.application.renderer_job
}

output "api_service_url" {
  value = module.application.api_service_url
}

output "overlay_service_url" {
  value = module.application.overlay_service_url
}

output "production_private_preview_bucket" {
  value = google_storage_bucket.production_private_previews.url
}

output "production_private_preview_queue" {
  value = google_cloud_tasks_queue.production_private_preview.id
}

output "production_private_preview_job" {
  value = google_cloud_run_v2_job.production_private_preview.id
}
