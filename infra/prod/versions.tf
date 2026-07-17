terraform {
  required_version = "~> 1.15.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.40"
    }
  }

  backend "gcs" {
    bucket = "animated-graph-cloud-prod-jp-tfstate"
    prefix = "prod"
  }
}

provider "google" {
  project = "animated-graph-cloud-prod-jp"
  region  = "europe-central2"
}
