terraform {
  required_version = ">= 1.7.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80.0, < 7.0.0"
    }
  }
}

locals {
  region           = "eu-west-1"
  account_id       = "111122223333"
  environment      = lower(var.environment)
  artifact_arn     = "arn:aws:s3:::synthetic-observability-artifacts/${var.market}/${local.environment}"
  artifact_uri     = "s3://synthetic-observability-artifacts/${var.market}/${local.environment}"
  synthetic_digest = "0000000000000000000000000000000000000000000000000000000000000000"
  partners = {
    PARTNER_A = {
      tenant_id                     = "tenant-a-${local.environment}"
      partner_slot                  = "p001"
      grafana_organization_uid      = "partner-a-${local.environment}"
      grafana_datasource_secret_arn = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:synthetic/${local.environment}/partner-a-datasource"
      source_service_keys           = ["synthetic-credit-service"]
      outbound_api_names            = ["loan-submit"]
      callback_names                = ["loan-result"]
      dev_mock_only                 = true
      callback_ingress_evidence = {
        owner_reference           = "SYNTHETIC-CALLBACK-ALB-A"
        https_listener_arn        = "arn:aws:elasticloadbalancing:${local.region}:${local.account_id}:listener/app/synthetic-callback-a/1111111111111111/2222222222222222"
        acm_certificate_arn       = "arn:aws:acm:${local.region}:${local.account_id}:certificate/11111111-1111-1111-1111-111111111111"
        private_targets_confirmed = true
        authentication_adapter_id = "synthetic-signature-a"
      }
    }
    PARTNER_B = {
      tenant_id                     = "tenant-b-${local.environment}"
      partner_slot                  = "p002"
      grafana_organization_uid      = "partner-b-${local.environment}"
      grafana_datasource_secret_arn = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:synthetic/${local.environment}/partner-b-datasource"
      source_service_keys           = ["synthetic-credit-service"]
      outbound_api_names            = ["eligibility-check"]
      callback_names                = ["eligibility-result"]
      dev_mock_only                 = true
      callback_ingress_evidence = {
        owner_reference           = "SYNTHETIC-CALLBACK-ALB-B"
        https_listener_arn        = "arn:aws:elasticloadbalancing:${local.region}:${local.account_id}:listener/app/synthetic-callback-b/3333333333333333/4444444444444444"
        acm_certificate_arn       = "arn:aws:acm:${local.region}:${local.account_id}:certificate/22222222-2222-2222-2222-222222222222"
        private_targets_confirmed = true
        authentication_adapter_id = "synthetic-signature-b"
      }
    }
    PARTNER_C = {
      tenant_id                     = "tenant-c-${local.environment}"
      partner_slot                  = "p003"
      grafana_organization_uid      = "partner-c-${local.environment}"
      grafana_datasource_secret_arn = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:synthetic/${local.environment}/partner-c-datasource"
      source_service_keys           = ["synthetic-credit-service"]
      outbound_api_names            = ["document-status"]
      callback_names                = ["document-status-update"]
      dev_mock_only                 = true
      callback_ingress_evidence = {
        owner_reference           = "SYNTHETIC-CALLBACK-ALB-C"
        https_listener_arn        = "arn:aws:elasticloadbalancing:${local.region}:${local.account_id}:listener/app/synthetic-callback-c/5555555555555555/6666666666666666"
        acm_certificate_arn       = "arn:aws:acm:${local.region}:${local.account_id}:certificate/33333333-3333-3333-3333-333333333333"
        private_targets_confirmed = true
        authentication_adapter_id = "synthetic-signature-c"
      }
    }
  }
  services = ["alloy", "alloy-proxy", "loki", "prometheus", "grafana", "query-gateway"]
  configuration_artifacts = {
    for service in local.services : service => {
      s3_uri     = "${local.artifact_uri}/${service}.json"
      object_arn = "${local.artifact_arn}/${service}.json"
      sha256     = local.synthetic_digest
    }
  }
}

module "stack" {
  source = "../../modules/market-observability-stack"

  aws_region                        = local.region
  aws_account_id                    = local.account_id
  market                            = var.market
  environment                       = var.environment
  vpc_id                            = "vpc-00000000000000001"
  vpc_cidr                          = "10.40.0.0/16"
  private_subnet_ids                = ["subnet-00000000000000001", "subnet-00000000000000002"]
  public_subnet_ids                 = ["subnet-00000000000000003", "subnet-00000000000000004"]
  ecs_cluster_arn                   = "arn:aws:ecs:${local.region}:${local.account_id}:cluster/${var.market}-${var.environment}"
  ecs_cluster_name                  = "${var.market}-${var.environment}"
  source_service_security_group_ids = ["sg-00000000000000001"]
  scrape_target_security_group_ids  = ["sg-00000000000000002"]
  operator_security_group_ids       = ["sg-00000000000000003"]
  aws_endpoint_security_group_id    = "sg-00000000000000004"
  s3_prefix_list_id                 = "pl-00000000000000001"
  grafana_acm_certificate_arn       = "arn:aws:acm:${local.region}:${local.account_id}:certificate/44444444-4444-4444-4444-444444444444"
  alloy_ingress_acm_certificate_arn = "arn:aws:acm:${local.region}:${local.account_id}:certificate/55555555-5555-5555-5555-555555555555"
  grafana_tls_security_policy       = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  alloy_tls_security_policy         = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  grafana_ingress_ipv4_cidrs        = ["192.0.2.0/24"]
  grafana_hostname                  = "grafana.${local.environment}.${var.market}.example.invalid"
  waf_web_acl_arn                   = "arn:aws:wafv2:${local.region}:${local.account_id}:regional/webacl/synthetic-observability/00000000-0000-0000-0000-000000000000"
  enable_deletion_protection        = var.environment == "PROD"
  loki_bucket_name                  = "synthetic-${var.market}-${local.environment}-partner-observability"
  grafana_backup_role_arn           = "arn:aws:iam::${local.account_id}:role/synthetic-observability-backup"
  images = {
    alloy            = "synthetic.example.invalid/alloy:1.0@sha256:${local.synthetic_digest}"
    ingress_proxy    = "synthetic.example.invalid/proxy:1.0@sha256:${local.synthetic_digest}"
    loki             = "synthetic.example.invalid/loki:1.0@sha256:${local.synthetic_digest}"
    prometheus       = "synthetic.example.invalid/prometheus:1.0@sha256:${local.synthetic_digest}"
    grafana          = "synthetic.example.invalid/grafana:1.0@sha256:${local.synthetic_digest}"
    prom_label_proxy = "synthetic.example.invalid/prom-label-proxy:1.0@sha256:${local.synthetic_digest}"
    journey_resolver = "synthetic.example.invalid/journey-resolver:1.0@sha256:${local.synthetic_digest}"
  }
  ecr_repository_arns     = ["arn:aws:ecr:${local.region}:${local.account_id}:repository/synthetic/partner-observability"]
  configuration_artifacts = local.configuration_artifacts
  alloy_proxy_secret_arns = {
    SOURCE_CREDENTIAL_MAP = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:synthetic/${local.environment}/alloy-source-map"
  }
  grafana_secret_arns = {
    GF_SECURITY_ADMIN_PASSWORD = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:synthetic/${local.environment}/grafana-admin"
  }
  query_gateway_secret_arns = {
    DATASOURCE_CREDENTIAL_MAP = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:synthetic/${local.environment}/query-map"
  }
  partners                      = local.partners
  production_deployment_enabled = false
  tags = {
    DataClass = "partner-safe-derived-observability"
    Example   = "validation-only"
  }
}

output "validation_environment" { value = var.environment }
output "synthetic_partner_count" { value = length(local.partners) }
