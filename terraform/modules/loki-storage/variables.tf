variable "name_prefix" { type = string }
variable "bucket_name" { type = string }
variable "object_prefix" {
  type    = string
  default = "telemetry"
}
variable "private_subnet_ids" { type = list(string) }
variable "storage_security_group_id" { type = string }
variable "loki_task_role_arn" { type = string }
variable "break_glass_role_arn" {
  type    = string
  default = null
}
variable "kms_key_arn" {
  type    = string
  default = null
}
variable "tags" {
  type    = map(string)
  default = {}
}
