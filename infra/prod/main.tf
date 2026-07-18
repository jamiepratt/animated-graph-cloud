module "application" {
  source = "../dev"

  project_id               = "animated-graph-cloud-prod-jp"
  region                   = "europe-central2"
  github_repository        = "jamiepratt/animated-graph-cloud"
  github_subject           = "repo:jamiepratt@558780/animated-graph-cloud@1303177214:environment:production"
  renderer_image           = var.renderer_image
  api_service_url          = var.api_service_url
  monthly_budget_pln       = var.monthly_budget_pln
  operations_alert_email   = "me@jamiep.org"
  environment_name         = "production"
  import_default_firestore = false
  enable_firebase_hosting  = true
}
