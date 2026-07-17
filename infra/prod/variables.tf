variable "renderer_image" {
  description = "Immutable production renderer image digest."
  type        = string

  validation {
    condition     = can(regex("^europe-central2-docker\\.pkg\\.dev/animated-graph-cloud-prod-jp/containers/animated-graph-cloud@sha256:[0-9a-f]{64}$", var.renderer_image))
    error_message = "Production renderer_image must be the production Artifact Registry SHA-256 digest."
  }
}

variable "api_service_url" {
  description = "Production Cloud Run origin; leave empty for bootstrap, then set and re-apply."
  type        = string
  default     = ""

  validation {
    condition     = var.api_service_url == "" || can(regex("^https://[^/]+\\.run\\.app$", var.api_service_url))
    error_message = "api_service_url must be empty or an HTTPS run.app origin without a path."
  }
}

variable "monthly_budget_pln" {
  description = "Production billing and application admission ceiling in PLN."
  type        = number
  default     = 400

  validation {
    condition     = var.monthly_budget_pln == 400
    error_message = "Production budget and admission are intentionally locked to PLN 400."
  }
}
