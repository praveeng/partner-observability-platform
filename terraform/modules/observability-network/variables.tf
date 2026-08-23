variable "name_prefix" { type = string }
variable "vpc_id" { type = string }
variable "vpc_cidr" {
  type = string
  validation {
    condition     = var.vpc_cidr != "0.0.0.0/0"
    error_message = "The VPC CIDR must not be the public internet."
  }
}
variable "private_subnet_ids" {
  type = list(string)
  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "At least two private subnets are required."
  }
}
variable "public_subnet_ids" {
  type = list(string)
  validation {
    condition     = length(var.public_subnet_ids) >= 2
    error_message = "At least two public ALB subnets are required."
  }
}
variable "source_service_security_group_ids" {
  type = list(string)
  validation {
    condition     = length(var.source_service_security_group_ids) > 0
    error_message = "At least one onboarded source-service security group is required."
  }
}
variable "scrape_target_security_group_ids" { type = list(string) }
variable "operator_security_group_ids" {
  type    = list(string)
  default = []
}
variable "aws_endpoint_security_group_id" { type = string }
variable "s3_prefix_list_id" { type = string }
variable "grafana_acm_certificate_arn" {
  type = string
  validation {
    condition     = can(regex("^arn:[^:]+:acm:[^:]+:[0-9]{12}:certificate/", var.grafana_acm_certificate_arn))
    error_message = "Provide an ACM certificate ARN; certificate values and private keys are not accepted."
  }
}
variable "alloy_ingress_acm_certificate_arn" {
  type = string
  validation {
    condition     = can(regex("^arn:[^:]+:acm:[^:]+:[0-9]{12}:certificate/", var.alloy_ingress_acm_certificate_arn))
    error_message = "Provide an ACM certificate ARN for private Alloy TLS ingress."
  }
}
variable "grafana_tls_security_policy" {
  type    = string
  default = "ELBSecurityPolicy-TLS13-1-2-2021-06"
}
variable "alloy_tls_security_policy" {
  type    = string
  default = "ELBSecurityPolicy-TLS13-1-2-2021-06"
}
variable "grafana_ingress_ipv4_cidrs" {
  type = list(string)
  validation {
    condition     = length(var.grafana_ingress_ipv4_cidrs) > 0 && alltrue([for cidr in var.grafana_ingress_ipv4_cidrs : cidr != "0.0.0.0/0"])
    error_message = "Grafana requires an approved IPv4 allowlist and rejects 0.0.0.0/0."
  }
}
variable "grafana_ingress_ipv6_cidrs" {
  type    = list(string)
  default = []
  validation {
    condition     = alltrue([for cidr in var.grafana_ingress_ipv6_cidrs : cidr != "::/0"])
    error_message = "Grafana rejects unrestricted IPv6 ingress."
  }
}
variable "waf_web_acl_arn" {
  type = string
  validation {
    condition     = can(regex("^arn:[^:]+:wafv2:[^:]+:[0-9]{12}:regional/webacl/", var.waf_web_acl_arn))
    error_message = "An approved regional WAF web ACL ARN is required for public Grafana ingress."
  }
}
variable "route53_zone_id" {
  type    = string
  default = null
}
variable "grafana_hostname" {
  type    = string
  default = null
}
variable "alb_access_logs_bucket" {
  type    = string
  default = null
}
variable "alb_access_logs_prefix" {
  type    = string
  default = "partner-observability/grafana"
}
variable "service_discovery_namespace" { type = string }
variable "enable_deletion_protection" {
  type    = bool
  default = true
}
variable "grafana_port" {
  type    = number
  default = 3000
}
variable "grafana_health_check_path" {
  type    = string
  default = "/api/health"
}
variable "alloy_container_port" {
  type    = number
  default = 4318
}
variable "loki_port" {
  type    = number
  default = 3100
}
variable "prometheus_port" {
  type    = number
  default = 9090
}
variable "query_gateway_port" {
  type    = number
  default = 8080
}
variable "actuator_port" {
  type    = number
  default = 8081
}
variable "tags" {
  type    = map(string)
  default = {}
}
