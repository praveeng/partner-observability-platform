variable "aws_partition" {
  type    = string
  default = "aws"
}
variable "aws_region" { type = string }
variable "aws_account_id" { type = string }
variable "market" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.market))
    error_message = "Market must be a bounded lowercase identifier."
  }
}
variable "environment" {
  type = string
  validation {
    condition     = contains(["DEV", "STAGE", "PROD"], var.environment)
    error_message = "Environment must be DEV, STAGE, or PROD; LOCAL_SYNTHETIC is not deployable."
  }
}
variable "vpc_id" { type = string }
variable "vpc_cidr" { type = string }
variable "private_subnet_ids" { type = list(string) }
variable "public_subnet_ids" { type = list(string) }
variable "ecs_cluster_arn" { type = string }
variable "ecs_cluster_name" { type = string }
variable "source_service_security_group_ids" { type = list(string) }
variable "scrape_target_security_group_ids" { type = list(string) }
variable "operator_security_group_ids" {
  type    = list(string)
  default = []
}
variable "aws_endpoint_security_group_id" { type = string }
variable "s3_prefix_list_id" { type = string }
variable "grafana_acm_certificate_arn" { type = string }
variable "alloy_ingress_acm_certificate_arn" { type = string }
variable "grafana_tls_security_policy" {
  type    = string
  default = "ELBSecurityPolicy-TLS13-1-2-2021-06"
}
variable "alloy_tls_security_policy" {
  type    = string
  default = "ELBSecurityPolicy-TLS13-1-2-2021-06"
}
variable "grafana_ingress_ipv4_cidrs" { type = list(string) }
variable "grafana_ingress_ipv6_cidrs" {
  type    = list(string)
  default = []
}
variable "grafana_hostname" { type = string }
variable "route53_zone_id" {
  type    = string
  default = null
}
variable "waf_web_acl_arn" { type = string }
variable "alb_access_logs_bucket" {
  type    = string
  default = null
}
variable "enable_deletion_protection" {
  type    = bool
  default = true
}
variable "loki_bucket_name" { type = string }
variable "loki_object_prefix" {
  type    = string
  default = "telemetry"
}
variable "loki_kms_key_arn" {
  type    = string
  default = null
}
variable "efs_kms_key_arn" {
  type    = string
  default = null
}
variable "cloudwatch_kms_key_arn" {
  type    = string
  default = null
}
variable "backup_kms_key_arn" {
  type    = string
  default = null
}
variable "break_glass_role_arn" {
  type    = string
  default = null
}
variable "grafana_backup_role_arn" { type = string }
variable "notification_topic_arn" {
  type    = string
  default = null
}
variable "production_deployment_enabled" {
  type        = bool
  default     = false
  description = "Must remain false outside the externally approved human production workflow."
}
variable "production_change_reference" {
  type        = string
  default     = null
  description = "External approval/ticket reference; it is not a credential."
}
variable "cloudwatch_retention_days" {
  type    = number
  default = 14
  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90], var.cloudwatch_retention_days)
    error_message = "Use an explicit conservative CloudWatch retention supported by AWS."
  }
}
variable "prometheus_retention_size_gib" {
  type    = number
  default = null
}
variable "images" {
  type = object({
    alloy            = string
    ingress_proxy    = string
    loki             = string
    prometheus       = string
    grafana          = string
    prom_label_proxy = string
    journey_resolver = string
  })
}
variable "ecr_repository_arns" { type = list(string) }
variable "configuration_artifacts" {
  type = map(object({ s3_uri = string, object_arn = string, sha256 = string }))
  validation {
    condition     = alltrue([for service in ["alloy", "alloy-proxy", "loki", "prometheus", "grafana", "query-gateway"] : contains(keys(var.configuration_artifacts), service)])
    error_message = "Versioned validated artifacts are required for every service and the Alloy proxy."
  }
}
variable "alloy_proxy_secret_arns" {
  type      = map(string)
  sensitive = true
}
variable "grafana_secret_arns" {
  type      = map(string)
  sensitive = true
}
variable "query_gateway_secret_arns" {
  type      = map(string)
  sensitive = true
}
variable "secret_kms_key_arns" {
  type    = list(string)
  default = []
}
variable "partners" {
  type = map(object({
    tenant_id                     = string
    partner_slot                  = string
    grafana_organization_uid      = string
    grafana_datasource_secret_arn = string
    source_service_keys           = set(string)
    outbound_api_names            = set(string)
    callback_names                = set(string)
    dev_mock_only                 = bool
    callback_ingress_evidence = object({
      owner_reference           = string
      https_listener_arn        = string
      acm_certificate_arn       = string
      private_targets_confirmed = bool
      authentication_adapter_id = string
    })
  }))
  validation {
    condition     = length(var.partners) >= 2 && length(var.partners) <= 64
    error_message = "Examples and stacks require 2-64 configuration-driven partners."
  }
  validation {
    condition = (
      length(distinct([for partner in values(var.partners) : partner.tenant_id])) == length(var.partners) &&
      length(distinct([for partner in values(var.partners) : partner.partner_slot])) == length(var.partners) &&
      length(distinct([for partner in values(var.partners) : partner.grafana_organization_uid])) == length(var.partners)
    )
    error_message = "Tenant IDs, partner slots, and Grafana organizations must be unique."
  }
  validation {
    condition = alltrue([for partner in values(var.partners) :
      can(regex("^[a-z0-9-]{1,40}$", partner.tenant_id)) &&
      can(regex("^p0(0[1-9]|[1-5][0-9]|6[0-4])$", partner.partner_slot)) &&
      startswith(partner.grafana_datasource_secret_arn, "arn:") &&
      partner.callback_ingress_evidence.private_targets_confirmed
    ])
    error_message = "Partner mappings require opaque bounded IDs, p001-p064 slots, secret ARNs, and private callback target evidence."
  }
}
variable "sizing" {
  type = map(object({ cpu = number, memory = number }))
  default = {
    alloy         = { cpu = 1024, memory = 2048 }
    loki          = { cpu = 1024, memory = 2048 }
    prometheus    = { cpu = 1024, memory = 2048 }
    grafana       = { cpu = 512, memory = 1024 }
    query_gateway = { cpu = 1024, memory = 2048 }
  }
}
variable "capacity_provider_strategy" {
  type    = list(object({ capacity_provider = string, weight = number, base = number }))
  default = []
}
variable "tags" {
  type    = map(string)
  default = {}
}
