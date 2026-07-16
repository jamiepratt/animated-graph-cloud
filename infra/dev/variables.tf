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

