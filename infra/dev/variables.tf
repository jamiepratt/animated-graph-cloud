variable "project_id" {
  description = "Development Google Cloud project ID."
  type        = string
  default     = "animated-graph-cloud-jp"
}

variable "region" {
  description = "Regional location for supported resources."
  type        = string
  default     = "europe-central2"

  validation {
    condition     = var.region == "europe-central2"
    error_message = "Development resources must remain in Warsaw (europe-central2)."
  }
}

variable "github_repository" {
  description = "GitHub owner/repository trusted by Workload Identity Federation."
  type        = string
  default     = "jamiepratt/animated-graph-cloud"
}

variable "renderer_image" {
  description = "Immutable application image used by the durable renderer job."
  type        = string
  default     = "europe-central2-docker.pkg.dev/animated-graph-cloud-jp/containers/animated-graph-cloud@sha256:1f6a8532e432502af5d9a4eb72f48d07abf79634334dd52d1ef38227f9bfa3f7"

  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.renderer_image))
    error_message = "The renderer image must use an immutable SHA-256 digest."
  }
}

variable "api_service_url" {
  description = "Stable public URL of the authenticated API service used by Scheduler."
  type        = string
  default     = "https://agg-api-zzosxhcrza-lm.a.run.app"

  validation {
    condition     = can(regex("^https://[^/]+\\.run\\.app$", var.api_service_url))
    error_message = "The API service URL must be an HTTPS run.app origin without a path."
  }
}

variable "monthly_budget_usd" {
  description = "Monthly development billing budget mirrored by application admission."
  type        = number
  default     = 30

  validation {
    condition     = var.monthly_budget_usd > 0 && floor(var.monthly_budget_usd) == var.monthly_budget_usd
    error_message = "The monthly budget must be a positive whole-dollar amount."
  }
}

variable "operations_alert_email" {
  description = "Owner email that receives operational alert notifications."
  type        = string
  default     = "me@jamiep.org"

  validation {
    condition     = can(regex("^[^@[:space:]]+@[^@[:space:]]+$", var.operations_alert_email))
    error_message = "The operations alert recipient must be an email address."
  }
}
