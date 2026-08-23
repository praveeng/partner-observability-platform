variable "name_prefix" { type = string }
variable "aws_region" { type = string }
variable "ecs_cluster_arn" { type = string }
variable "private_subnet_ids" { type = list(string) }
variable "security_group_id" { type = string }
variable "service_discovery_namespace_id" { type = string }
variable "execution_role_arn" { type = string }
variable "task_role_arn" { type = string }
variable "image" {
  type = string
  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.image))
    error_message = "Loki image must be pinned by digest."
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
variable "loki_bucket_name" { type = string }
variable "loki_object_prefix" {
  type    = string
  default = "telemetry"
}
variable "efs_file_system_id" { type = string }
variable "efs_access_point_id" { type = string }
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
  default = 3100
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
