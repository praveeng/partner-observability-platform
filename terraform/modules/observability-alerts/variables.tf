variable "name_prefix" { type = string }
variable "ecs_cluster_name" { type = string }
variable "service_names" { type = map(string) }
variable "grafana_load_balancer_arn_suffix" { type = string }
variable "grafana_target_group_arn_suffix" { type = string }
variable "notification_topic_arn" {
  type    = string
  default = null
}
variable "tags" {
  type    = map(string)
  default = {}
}
