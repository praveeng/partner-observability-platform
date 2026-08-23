variable "name_prefix" { type = string }
variable "aws_partition" {
  type    = string
  default = "aws"
}
variable "aws_region" { type = string }
variable "aws_account_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "AWS account ID must be exactly 12 digits."
  }
}
variable "cloudwatch_log_group_names" { type = list(string) }
variable "ecr_repository_arns" {
  type = list(string)
  validation {
    condition     = length(var.ecr_repository_arns) > 0 && alltrue([for arn in var.ecr_repository_arns : arn != "*"])
    error_message = "Exact ECR repository ARNs are required."
  }
}
variable "secret_arns" {
  type      = list(string)
  default   = []
  sensitive = true
  validation {
    condition     = alltrue([for arn in var.secret_arns : startswith(arn, "arn:")])
    error_message = "Secrets must be references to Secrets Manager or SSM ARNs, never values."
  }
}
variable "secret_kms_key_arns" {
  type    = list(string)
  default = []
}
variable "configuration_object_arns" {
  type = map(list(string))
  validation {
    condition     = alltrue([for service in ["alloy", "loki", "prometheus", "grafana", "query-gateway"] : contains(keys(var.configuration_object_arns), service)])
    error_message = "Versioned configuration object ARNs are required for every service."
  }
}
variable "loki_bucket_arn" { type = string }
variable "loki_object_prefix" {
  type    = string
  default = "telemetry"
}
variable "loki_kms_key_arn" {
  type    = string
  default = null
}
variable "tags" {
  type    = map(string)
  default = {}
}
