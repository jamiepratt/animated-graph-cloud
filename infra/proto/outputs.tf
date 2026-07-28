output "deployer_service_account" {
  value = google_service_account.deployer.email
}

output "github_workload_identity_provider" {
  value = google_iam_workload_identity_pool_provider.proto.name
}

output "proto_service_url" {
  value = google_cloud_run_v2_service.proto.uri
}

output "proto_hosting_site" {
  value = google_firebase_hosting_site.proto.site_id
}
