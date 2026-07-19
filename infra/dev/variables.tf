variable "project_id" {
  description = "Google Cloud project ID for this isolated environment."
  type        = string
  default     = "animated-graph-cloud-jp"
}

variable "region" {
  description = "Regional location for supported resources."
  type        = string
  default     = "europe-central2"

  validation {
    condition     = var.region == "europe-central2"
    error_message = "Application resources must remain in Warsaw (europe-central2)."
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
  description = "Cloud Run origin used by Scheduler; empty during first bootstrap."
  type        = string
  default     = "https://agg-api-zzosxhcrza-lm.a.run.app"

  validation {
    condition     = var.api_service_url == "" || can(regex("^https://[^/]+\\.run\\.app$", var.api_service_url))
    error_message = "The API service URL must be empty or an HTTPS run.app origin without a path."
  }
}

variable "monthly_budget_pln" {
  description = "Monthly environment billing budget in PLN, mirrored by application admission."
  type        = number
  default     = 400

  validation {
    condition     = var.monthly_budget_pln > 0 && floor(var.monthly_budget_pln) == var.monthly_budget_pln
    error_message = "The monthly budget must be a positive whole-zloty amount."
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

variable "environment_name" {
  description = "Human-readable isolated environment name."
  type        = string
  default     = "development"
}

variable "import_default_firestore" {
  description = "Import a pre-existing default Firestore database instead of creating it."
  type        = bool
  default     = true
}

variable "import_api_service" {
  description = "Import the pre-existing agg-api Cloud Run service into Terraform state."
  type        = bool
  default     = true
}

variable "enable_firebase_hosting" {
  description = "Enable Firebase APIs and grant the deployer Hosting administration."
  type        = bool
  default     = false
}

variable "enable_observability_log_ttl" {
  description = "Manage the expireAt TTL policy for observability-log documents."
  type        = bool
  default     = true
}

variable "github_subject" {
  description = "Optional exact GitHub OIDC subject, used to bind a deployment to a specific ref or environment."
  type        = string
  default     = ""
}

variable "enable_terraform_deployments" {
  description = "Grant the keyless GitHub deployer the infrastructure roles required to apply this module."
  type        = bool
  default     = false
}

variable "terraform_state_bucket" {
  description = "State bucket whose objects the Terraform-enabled GitHub deployer may manage."
  type        = string
  default     = ""
}
