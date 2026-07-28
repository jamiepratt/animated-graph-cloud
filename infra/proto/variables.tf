variable "proto_image" {
  description = "Immutable image digest promoted to the separate proto service."
  type        = string

  validation {
    condition     = can(regex("^europe-central2-docker\\.pkg\\.dev/animated-graph-cloud-prod-jp/containers/animated-graph-cloud@sha256:[0-9a-f]{64}$", var.proto_image))
    error_message = "proto_image must be the production Artifact Registry SHA-256 digest."
  }
}

variable "api_service_url" {
  description = "Production API origin used only as the proto dispatcher audience."
  type        = string

  validation {
    condition     = can(regex("^https://[^/]+\\.run\\.app$", var.api_service_url))
    error_message = "api_service_url must be an HTTPS run.app origin without a path."
  }
}

variable "owner_email" {
  description = "Proto owner email."
  type        = string
  default     = "me@jamiep.org"
}

variable "admin_emails" {
  description = "Semicolon-separated proto administrator emails."
  type        = string
  default     = "me@jamiep.org;bartoszjakubowiak@gmail.com"
}

variable "daily_submission_limit" {
  description = "Daily durable submission limit inherited by the proto runtime."
  type        = number
  default     = 100
}

variable "monthly_budget_minor_units" {
  description = "Monthly admission ceiling in minor PLN units."
  type        = number
  default     = 40000
}

variable "preview_reservation_minor_units" {
  description = "Preview admission reservation in minor PLN units."
  type        = number
  default     = 125
}

variable "render_reservation_minor_units" {
  description = "Render admission reservation in minor PLN units."
  type        = number
  default     = 125
}
