terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Local state for now (see docs/DECISIONS.md). Move to S3 + locking before
  # anyone else runs this.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "payments-ledger"
      ManagedBy = "terraform"
    }
  }
}
