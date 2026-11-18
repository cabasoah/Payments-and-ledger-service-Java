variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "ca-central-1"
}

variable "name" {
  description = "Name prefix for all resources."
  type        = string
  default     = "payments-ledger"
}

variable "image_tag" {
  description = "Tag of the image in ECR to run (deploy.sh passes the git SHA)."
  type        = string
  default     = "latest"
}

variable "task_cpu" {
  description = "Fargate task CPU units (512 = 0.5 vCPU)."
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Fargate task memory in MiB."
  type        = number
  default     = 1024
}

variable "alert_email" {
  description = "Email address that receives AWS Budget alerts."
  type        = string
}

variable "monthly_budget_usd" {
  description = "Monthly cost budget in USD. Alerts at 80% forecast and 100% actual."
  type        = number
  default     = 25
}
