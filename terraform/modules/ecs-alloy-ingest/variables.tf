variable "name_prefix" { type = string }
variable "aws_region" { type = string }
variable "ecs_cluster_arn" { type = string }
variable "ecs_cluster_name" { type = string }
variable "private_subnet_ids" { type = list(string) }
variable "security_group_id" { type = string }
variable "target_group_arn" { type = string }
variable "service_discovery_namespace_id" { type = string }
variable "service_discovery_namespace" { type = string }
variable "execution_role_arn" { type = string }
variable "task_role_arn" { type = string }
variable "alloy_image" {
  type = string
  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.alloy_image))
    error_message = "Alloy image must be pinned by digest."
  }
}
variable "proxy_image" {
  type = string
  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.proxy_image))
    error_message = "Ingress proxy image must be pinned by digest."
  }
}
variable "configuration_s3_uri" { type = string }
variable "configuration_sha256" { type = string }
variable "proxy_configuration_s3_uri" { type = string }
variable "proxy_configuration_sha256" { type = string }
variable "proxy_secret_arns" {
  type      = map(string)
  sensitive = true
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
  default = 4318
}
variable "alloy_health_port" {
  type    = number
  default = 12345
}
variable "desired_count" {
  type    = number
  default = 1
}
variable "autoscaling_min_capacity" {
  type    = number
  default = 1
}
variable "autoscaling_max_capacity" {
  type    = number
  default = 2
}
variable "autoscaling_cpu_target" {
  type    = number
  default = 70
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
