mock_provider "aws" {}

run "https_and_private_network_contract" {
  command = plan

  override_resource {
    target          = aws_security_group.grafana_alb
    override_during = plan
    values = {
      id = "sg-mock-grafana-alb"
    }
  }

  override_resource {
    target          = aws_security_group.alloy_tasks
    override_during = plan
    values = {
      id = "sg-mock-alloy-tasks"
    }
  }

  override_resource {
    target          = aws_security_group.query_gateway_tasks
    override_during = plan
    values = {
      id = "sg-mock-query-gateway-tasks"
    }
  }

  variables {
    name_prefix                       = "synthetic-dev-observability"
    vpc_id                            = "vpc-00000000000000001"
    vpc_cidr                          = "10.40.0.0/16"
    private_subnet_ids                = ["subnet-00000000000000001", "subnet-00000000000000002"]
    public_subnet_ids                 = ["subnet-00000000000000003", "subnet-00000000000000004"]
    source_service_security_group_ids = ["sg-00000000000000001"]
    scrape_target_security_group_ids  = ["sg-00000000000000002"]
    operator_security_group_ids       = ["sg-00000000000000003"]
    aws_endpoint_security_group_id    = "sg-00000000000000004"
    s3_prefix_list_id                 = "pl-00000000000000001"
    grafana_acm_certificate_arn       = "arn:aws:acm:eu-west-1:111122223333:certificate/11111111-1111-1111-1111-111111111111"
    alloy_ingress_acm_certificate_arn = "arn:aws:acm:eu-west-1:111122223333:certificate/22222222-2222-2222-2222-222222222222"
    grafana_ingress_ipv4_cidrs        = ["192.0.2.0/24"]
    waf_web_acl_arn                   = "arn:aws:wafv2:eu-west-1:111122223333:regional/webacl/synthetic/00000000-0000-0000-0000-000000000000"
    service_discovery_namespace       = "observability.synthetic.dev.internal"
    enable_deletion_protection        = false
  }

  assert {
    condition     = aws_lb_listener.grafana_https.port == 443 && aws_lb_listener.grafana_https.protocol == "HTTPS"
    error_message = "Grafana must have exactly the architecture-approved HTTPS listener."
  }

  assert {
    condition     = aws_lb_listener.grafana_https.certificate_arn != null && startswith(aws_lb_listener.grafana_https.ssl_policy, "ELBSecurityPolicy-TLS13-")
    error_message = "Grafana HTTPS requires ACM and an approved pinned TLS policy."
  }

  assert {
    condition     = aws_lb.grafana.internal == false && aws_lb.alloy.internal == true
    error_message = "Only the Grafana ALB may be internet-facing; Alloy must remain internal."
  }

  assert {
    condition     = alltrue([for rule in aws_vpc_security_group_ingress_rule.grafana_https_ipv4 : rule.from_port == 443 && rule.to_port == 443 && rule.cidr_ipv4 != "0.0.0.0/0"])
    error_message = "Grafana ingress must be allowlisted HTTPS only."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.grafana_from_alb.referenced_security_group_id == aws_security_group.grafana_alb.id
    error_message = "Grafana task ingress must reference only the owning ALB security group."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.loki_from_alloy.referenced_security_group_id == aws_security_group.alloy_tasks.id && aws_vpc_security_group_ingress_rule.loki_from_query_gateway.referenced_security_group_id == aws_security_group.query_gateway_tasks.id
    error_message = "Loki ingress must remain limited to Alloy and the trusted query gateway."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.prometheus_from_alloy.referenced_security_group_id == aws_security_group.alloy_tasks.id && aws_vpc_security_group_ingress_rule.prometheus_from_query_gateway.referenced_security_group_id == aws_security_group.query_gateway_tasks.id
    error_message = "Prometheus ingress must remain limited to Alloy and the trusted query gateway."
  }
}
