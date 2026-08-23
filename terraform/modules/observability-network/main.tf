terraform {
  required_version = ">= 1.7.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80.0, < 7.0.0"
    }
  }
}

resource "aws_security_group" "grafana_alb" {
  name                   = "${var.name_prefix}-grafana-alb"
  description            = "443-only partner Grafana ALB"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "grafana_https_ipv4" {
  for_each          = toset(var.grafana_ingress_ipv4_cidrs)
  security_group_id = aws_security_group.grafana_alb.id
  description       = "Approved partner HTTPS ingress"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_ingress_rule" "grafana_https_ipv6" {
  for_each          = toset(var.grafana_ingress_ipv6_cidrs)
  security_group_id = aws_security_group.grafana_alb.id
  description       = "Approved partner HTTPS ingress"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv6         = each.value
}

resource "aws_security_group" "grafana_tasks" {
  name                   = "${var.name_prefix}-grafana-tasks"
  description            = "Private Grafana tasks"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "grafana_from_alb" {
  security_group_id            = aws_security_group.grafana_tasks.id
  description                  = "Grafana only from its ALB"
  ip_protocol                  = "tcp"
  from_port                    = var.grafana_port
  to_port                      = var.grafana_port
  referenced_security_group_id = aws_security_group.grafana_alb.id
}

resource "aws_vpc_security_group_egress_rule" "alb_to_grafana" {
  security_group_id            = aws_security_group.grafana_alb.id
  description                  = "ALB to private Grafana targets"
  ip_protocol                  = "tcp"
  from_port                    = var.grafana_port
  to_port                      = var.grafana_port
  referenced_security_group_id = aws_security_group.grafana_tasks.id
}

resource "aws_security_group" "alloy_nlb" {
  name                   = "${var.name_prefix}-alloy-nlb"
  description            = "Private TLS ingest NLB"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "alloy_ingest_sources" {
  for_each                     = toset(var.source_service_security_group_ids)
  security_group_id            = aws_security_group.alloy_nlb.id
  description                  = "Onboarded service telemetry over private TLS"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = each.value
}

resource "aws_security_group" "alloy_tasks" {
  name                   = "${var.name_prefix}-alloy-tasks"
  description            = "Private Alloy ingress tasks"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "alloy_from_nlb" {
  security_group_id            = aws_security_group.alloy_tasks.id
  description                  = "NLB to authenticated ingest proxy"
  ip_protocol                  = "tcp"
  from_port                    = var.alloy_container_port
  to_port                      = var.alloy_container_port
  referenced_security_group_id = aws_security_group.alloy_nlb.id
}

resource "aws_vpc_security_group_egress_rule" "nlb_to_alloy" {
  security_group_id            = aws_security_group.alloy_nlb.id
  description                  = "NLB to Alloy proxy targets"
  ip_protocol                  = "tcp"
  from_port                    = var.alloy_container_port
  to_port                      = var.alloy_container_port
  referenced_security_group_id = aws_security_group.alloy_tasks.id
}

resource "aws_security_group" "loki_tasks" {
  name                   = "${var.name_prefix}-loki-tasks"
  description            = "Private Loki tasks"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = var.tags
}

resource "aws_security_group" "prometheus_tasks" {
  name                   = "${var.name_prefix}-prometheus-tasks"
  description            = "Private Prometheus tasks"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = var.tags
}

resource "aws_security_group" "query_gateway_tasks" {
  name                   = "${var.name_prefix}-query-gateway-tasks"
  description            = "Private trusted query gateway tasks"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = var.tags
}

resource "aws_security_group" "stateful_storage" {
  name                   = "${var.name_prefix}-stateful-storage"
  description            = "Encrypted EFS mount targets"
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = true
  tags                   = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "loki_from_alloy" {
  security_group_id            = aws_security_group.loki_tasks.id
  description                  = "Loki writes from Alloy"
  ip_protocol                  = "tcp"
  from_port                    = var.loki_port
  to_port                      = var.loki_port
  referenced_security_group_id = aws_security_group.alloy_tasks.id
}

resource "aws_vpc_security_group_ingress_rule" "loki_from_query_gateway" {
  security_group_id            = aws_security_group.loki_tasks.id
  description                  = "Loki reads from trusted query gateway"
  ip_protocol                  = "tcp"
  from_port                    = var.loki_port
  to_port                      = var.loki_port
  referenced_security_group_id = aws_security_group.query_gateway_tasks.id
}

resource "aws_vpc_security_group_ingress_rule" "prometheus_from_alloy" {
  security_group_id            = aws_security_group.prometheus_tasks.id
  description                  = "Prometheus remote write from Alloy"
  ip_protocol                  = "tcp"
  from_port                    = var.prometheus_port
  to_port                      = var.prometheus_port
  referenced_security_group_id = aws_security_group.alloy_tasks.id
}

resource "aws_vpc_security_group_ingress_rule" "prometheus_from_query_gateway" {
  security_group_id            = aws_security_group.prometheus_tasks.id
  description                  = "Prometheus reads from trusted query gateway"
  ip_protocol                  = "tcp"
  from_port                    = var.prometheus_port
  to_port                      = var.prometheus_port
  referenced_security_group_id = aws_security_group.query_gateway_tasks.id
}

resource "aws_vpc_security_group_ingress_rule" "query_gateway_from_grafana" {
  security_group_id            = aws_security_group.query_gateway_tasks.id
  description                  = "Queries only from Grafana"
  ip_protocol                  = "tcp"
  from_port                    = var.query_gateway_port
  to_port                      = var.query_gateway_port
  referenced_security_group_id = aws_security_group.grafana_tasks.id
}

resource "aws_vpc_security_group_ingress_rule" "query_gateway_from_operators" {
  for_each                     = toset(var.operator_security_group_ids)
  security_group_id            = aws_security_group.query_gateway_tasks.id
  description                  = "Approved internal operator queries"
  ip_protocol                  = "tcp"
  from_port                    = var.query_gateway_port
  to_port                      = var.query_gateway_port
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_egress_rule" "alloy_to_loki" {
  security_group_id            = aws_security_group.alloy_tasks.id
  description                  = "Alloy to Loki"
  ip_protocol                  = "tcp"
  from_port                    = var.loki_port
  to_port                      = var.loki_port
  referenced_security_group_id = aws_security_group.loki_tasks.id
}

resource "aws_vpc_security_group_egress_rule" "alloy_to_prometheus" {
  security_group_id            = aws_security_group.alloy_tasks.id
  description                  = "Alloy to Prometheus"
  ip_protocol                  = "tcp"
  from_port                    = var.prometheus_port
  to_port                      = var.prometheus_port
  referenced_security_group_id = aws_security_group.prometheus_tasks.id
}

resource "aws_vpc_security_group_egress_rule" "grafana_to_query_gateway" {
  security_group_id            = aws_security_group.grafana_tasks.id
  description                  = "Grafana to trusted query gateway"
  ip_protocol                  = "tcp"
  from_port                    = var.query_gateway_port
  to_port                      = var.query_gateway_port
  referenced_security_group_id = aws_security_group.query_gateway_tasks.id
}

resource "aws_vpc_security_group_egress_rule" "query_gateway_to_loki" {
  security_group_id            = aws_security_group.query_gateway_tasks.id
  description                  = "Tenant-fixed Loki queries"
  ip_protocol                  = "tcp"
  from_port                    = var.loki_port
  to_port                      = var.loki_port
  referenced_security_group_id = aws_security_group.loki_tasks.id
}

resource "aws_vpc_security_group_egress_rule" "query_gateway_to_prometheus" {
  security_group_id            = aws_security_group.query_gateway_tasks.id
  description                  = "Slot-fixed Prometheus queries"
  ip_protocol                  = "tcp"
  from_port                    = var.prometheus_port
  to_port                      = var.prometheus_port
  referenced_security_group_id = aws_security_group.prometheus_tasks.id
}

resource "aws_vpc_security_group_egress_rule" "alloy_scrape" {
  for_each                     = toset(var.scrape_target_security_group_ids)
  security_group_id            = aws_security_group.alloy_tasks.id
  description                  = "Alloy to approved Actuator targets"
  ip_protocol                  = "tcp"
  from_port                    = var.actuator_port
  to_port                      = var.actuator_port
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_ingress_rule" "scrape_from_alloy" {
  for_each                     = toset(var.scrape_target_security_group_ids)
  security_group_id            = each.value
  description                  = "Actuator scrape only from Alloy"
  ip_protocol                  = "tcp"
  from_port                    = var.actuator_port
  to_port                      = var.actuator_port
  referenced_security_group_id = aws_security_group.alloy_tasks.id
}

locals {
  stateful_security_groups = {
    loki       = aws_security_group.loki_tasks.id
    prometheus = aws_security_group.prometheus_tasks.id
    grafana    = aws_security_group.grafana_tasks.id
  }
  task_security_groups = merge(local.stateful_security_groups, {
    alloy         = aws_security_group.alloy_tasks.id
    query_gateway = aws_security_group.query_gateway_tasks.id
  })
}

resource "aws_vpc_security_group_ingress_rule" "efs_from_stateful_tasks" {
  for_each                     = local.stateful_security_groups
  security_group_id            = aws_security_group.stateful_storage.id
  description                  = "NFS from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = 2049
  to_port                      = 2049
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_egress_rule" "stateful_tasks_to_efs" {
  for_each                     = local.stateful_security_groups
  security_group_id            = each.value
  description                  = "${each.key} to encrypted EFS"
  ip_protocol                  = "tcp"
  from_port                    = 2049
  to_port                      = 2049
  referenced_security_group_id = aws_security_group.stateful_storage.id
}

resource "aws_vpc_security_group_egress_rule" "tasks_to_aws_endpoints" {
  for_each                     = local.task_security_groups
  security_group_id            = each.value
  description                  = "${each.key} to approved AWS interface endpoints"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = var.aws_endpoint_security_group_id
}

resource "aws_vpc_security_group_egress_rule" "tasks_to_s3_endpoint" {
  for_each          = local.task_security_groups
  security_group_id = each.value
  description       = "${each.key} to the existing S3 gateway endpoint"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = var.s3_prefix_list_id
}

resource "aws_vpc_security_group_ingress_rule" "aws_endpoints_from_tasks" {
  for_each                     = local.task_security_groups
  security_group_id            = var.aws_endpoint_security_group_id
  description                  = "AWS endpoint access from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_egress_rule" "tasks_dns_udp" {
  for_each          = local.task_security_groups
  security_group_id = each.value
  description       = "VPC DNS for ${each.key}"
  ip_protocol       = "udp"
  from_port         = 53
  to_port           = 53
  cidr_ipv4         = var.vpc_cidr
}

resource "aws_vpc_security_group_egress_rule" "tasks_dns_tcp" {
  for_each          = local.task_security_groups
  security_group_id = each.value
  description       = "VPC DNS fallback for ${each.key}"
  ip_protocol       = "tcp"
  from_port         = 53
  to_port           = 53
  cidr_ipv4         = var.vpc_cidr
}

resource "aws_lb" "grafana" {
  name                       = substr("${var.name_prefix}-grafana", 0, 32)
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.grafana_alb.id]
  subnets                    = var.public_subnet_ids
  drop_invalid_header_fields = true
  enable_deletion_protection = var.enable_deletion_protection

  dynamic "access_logs" {
    for_each = var.alb_access_logs_bucket == null ? [] : [var.alb_access_logs_bucket]
    content {
      bucket  = access_logs.value
      prefix  = var.alb_access_logs_prefix
      enabled = true
    }
  }
  tags = var.tags
}

resource "aws_lb_target_group" "grafana" {
  name                 = substr("${var.name_prefix}-grafana", 0, 32)
  port                 = var.grafana_port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    enabled             = true
    healthy_threshold   = 2
    interval            = 30
    matcher             = "200-399"
    path                = var.grafana_health_check_path
    port                = "traffic-port"
    protocol            = "HTTP"
    timeout             = 5
    unhealthy_threshold = 3
  }
  tags = var.tags
}

resource "aws_lb_listener" "grafana_https" {
  load_balancer_arn = aws_lb.grafana.arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = var.grafana_acm_certificate_arn
  ssl_policy        = var.grafana_tls_security_policy

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.grafana.arn
  }

  lifecycle {
    precondition {
      condition     = startswith(var.grafana_tls_security_policy, "ELBSecurityPolicy-TLS13-") || startswith(var.grafana_tls_security_policy, "ELBSecurityPolicy-FS-")
      error_message = "The Grafana listener must use an explicitly approved TLS 1.2-or-newer policy."
    }
  }
}

resource "aws_wafv2_web_acl_association" "grafana" {
  resource_arn = aws_lb.grafana.arn
  web_acl_arn  = var.waf_web_acl_arn
}

resource "aws_lb" "alloy" {
  name                             = substr("${var.name_prefix}-alloy", 0, 32)
  internal                         = true
  load_balancer_type               = "network"
  security_groups                  = [aws_security_group.alloy_nlb.id]
  subnets                          = var.private_subnet_ids
  enable_cross_zone_load_balancing = true
  enable_deletion_protection       = var.enable_deletion_protection
  tags                             = var.tags
}

resource "aws_lb_target_group" "alloy" {
  name                 = substr("${var.name_prefix}-alloy", 0, 32)
  port                 = var.alloy_container_port
  protocol             = "TCP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 15

  health_check {
    enabled             = true
    healthy_threshold   = 2
    interval            = 30
    port                = "traffic-port"
    protocol            = "TCP"
    unhealthy_threshold = 3
  }
  tags = var.tags
}

resource "aws_lb_listener" "alloy_tls" {
  load_balancer_arn = aws_lb.alloy.arn
  port              = 443
  protocol          = "TLS"
  certificate_arn   = var.alloy_ingress_acm_certificate_arn
  ssl_policy        = var.alloy_tls_security_policy

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.alloy.arn
  }
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name        = var.service_discovery_namespace
  description = "Partner Observability private service discovery"
  vpc         = var.vpc_id
  tags        = var.tags
}

resource "aws_route53_record" "grafana" {
  count   = var.route53_zone_id == null || var.grafana_hostname == null ? 0 : 1
  zone_id = var.route53_zone_id
  name    = var.grafana_hostname
  type    = "A"
  alias {
    name                   = aws_lb.grafana.dns_name
    zone_id                = aws_lb.grafana.zone_id
    evaluate_target_health = true
  }
}

output "grafana_alb_arn" { value = aws_lb.grafana.arn }
output "grafana_alb_dns_name" { value = aws_lb.grafana.dns_name }
output "grafana_alb_arn_suffix" { value = aws_lb.grafana.arn_suffix }
output "grafana_target_group_arn" { value = aws_lb_target_group.grafana.arn }
output "grafana_target_group_arn_suffix" { value = aws_lb_target_group.grafana.arn_suffix }
output "alloy_target_group_arn" { value = aws_lb_target_group.alloy.arn }
output "alloy_ingress_dns_name" { value = aws_lb.alloy.dns_name }
output "service_discovery_namespace_id" { value = aws_service_discovery_private_dns_namespace.this.id }
output "security_group_ids" {
  value = merge(local.task_security_groups, {
    grafana_alb      = aws_security_group.grafana_alb.id
    alloy_nlb        = aws_security_group.alloy_nlb.id
    stateful_storage = aws_security_group.stateful_storage.id
  })
}
