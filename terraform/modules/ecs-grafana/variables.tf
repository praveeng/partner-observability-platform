variable "name_prefix" { type = string }
variable "aws_region" { type = string }
variable "ecs_cluster_arn" { type = string }
variable "private_subnet_ids" { type = list(string) }
variable "security_group_id" { type = string }
variable "storage_security_group_id" { type = string }
variable "target_group_arn" { type = string }
variable "execution_role_arn" { type = string }
variable "task_role_arn" { type = string }
variable "image" {
  type = string
  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.image))
    error_message = "Grafana image must be pinned by digest."
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
variable "external_hostname" { type = string }
variable "secret_arns" {
  type      = map(string)
  sensitive = true
  validation {
    condition     = contains(keys(var.secret_arns), "GF_SECURITY_ADMIN_PASSWORD") && alltrue([for arn in values(var.secret_arns) : startswith(arn, "arn:")])
    error_message = "Grafana requires an admin password secret ARN; plaintext values are not accepted."
  }
}
variable "backup_role_arn" { type = string }
variable "backup_kms_key_arn" {
  type    = string
  default = null
}
variable "backup_schedule" {
  type    = string
  default = "cron(0 3 * * ? *)"
}
variable "efs_kms_key_arn" {
  type    = string
  default = null
}
variable "cpu" {
  type    = number
  default = 512
}
variable "memory" {
  type    = number
  default = 1024
}
variable "cpu_architecture" {
  type    = string
  default = "X86_64"
}
variable "container_port" {
  type    = number
  default = 3000
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
