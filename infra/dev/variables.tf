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
  default     = "europe-central2-docker.pkg.dev/animated-graph-cloud-jp/containers/animated-graph-cloud@sha256:8b8f07538adab8962a47b8479d2fbcd47eaa0a4d68c0b6fab743f80e274ade26"

  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.renderer_image))
    error_message = "The renderer image must use an immutable SHA-256 digest."
  }
}
