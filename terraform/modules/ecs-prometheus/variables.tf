variable "name_prefix" { type = string }
variable "aws_region" { type = string }
variable "ecs_cluster_arn" { type = string }
variable "private_subnet_ids" { type = list(string) }
variable "security_group_id" { type = string }
variable "storage_security_group_id" { type = string }
variable "service_discovery_namespace_id" { type = string }
variable "execution_role_arn" { type = string }
variable "task_role_arn" { type = string }
variable "image" {
  type = string
  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.image))
    error_message = "Prometheus image must be pinned by digest."
  }
}
variable "configuration_s3_uri" { type = string }
variable "configuration_sha256" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.configuration_sha256))
    error_message = "Configuration digest must be SHA-256."
  }
}
variable "retention_size_gib" {
  type = number
  validation {
    condition     = var.retention_size_gib >= 1 && var.retention_size_gib <= 1024
    error_message = "Prometheus retention size must remain explicitly bounded."
  }
}
variable "efs_kms_key_arn" {
  type    = string
  default = null
}
variable "cpu" {
  type    = number
  default = 1024
}
variable "memory" {
  type    = number
  default = 2048
}
variable "cpu_architecture" {
  type    = string
  default = "X86_64"
}
variable "container_port" {
  type    = number
  default = 9090
}
variable "fargate_platform_version" {
  type    = string
  default = "1.4.0"
}
variable "capacity_provider_strategy" {
  type    = list(object({ capacity_provider = string, weight = number, base = number }))
  default = []
}
variable "log_group_name" { type = string }
variable "cloudwatch_retention_days" {
  type    = number
  default = 14
}
variable "cloudwatch_kms_key_arn" {
  type    = string
  default = null
}
variable "tags" {
  type    = map(string)
  default = {}
}
