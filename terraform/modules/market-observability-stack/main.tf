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
  name_prefix = substr("${var.market}-${lower(var.environment)}-partner-observability", 0, 32)
  tags = merge(var.tags, {
    Market      = var.market
    Environment = var.environment
    System      = "partner-observability"
    ManagedBy   = "terraform"
  })
  service_log_groups = {
    alloy         = "/ecs/${local.name_prefix}/alloy"
    loki          = "/ecs/${local.name_prefix}/loki"
    prometheus    = "/ecs/${local.name_prefix}/prometheus"
    grafana       = "/ecs/${local.name_prefix}/grafana"
    query_gateway = "/ecs/${local.name_prefix}/query-gateway"
  }
  desired_stateless_count = var.environment == "PROD" ? 2 : 1
  stateless_max_count     = var.environment == "PROD" ? 6 : 2
  prometheus_size_gib     = var.prometheus_retention_size_gib == null ? (var.environment == "PROD" ? 50 : var.environment == "STAGE" ? 20 : 10) : var.prometheus_retention_size_gib
  configuration_object_arns = {
    alloy           = [var.configuration_artifacts["alloy"].object_arn, var.configuration_artifacts["alloy-proxy"].object_arn]
    loki            = [var.configuration_artifacts["loki"].object_arn]
    prometheus      = [var.configuration_artifacts["prometheus"].object_arn]
    grafana         = [var.configuration_artifacts["grafana"].object_arn]
    "query-gateway" = [var.configuration_artifacts["query-gateway"].object_arn]
  }
  runtime_secret_arns = distinct(concat(
    values(var.alloy_proxy_secret_arns),
    values(var.grafana_secret_arns),
    values(var.query_gateway_secret_arns),
    [for partner in values(var.partners) : partner.grafana_datasource_secret_arn]
  ))
  loki_bucket_arn = "arn:${var.aws_partition}:s3:::${var.loki_bucket_name}"
}

resource "terraform_data" "deployment_contract" {
  input = sha256(jsonencode({
    market      = var.market
    environment = var.environment
    partners    = var.partners
  }))

  lifecycle {
    precondition {
      condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
      error_message = "An explicit 12-digit existing AWS account ID is required; no credential discovery is performed."
    }
    precondition {
      condition     = var.environment != "DEV" || alltrue([for partner in values(var.partners) : partner.dev_mock_only])
      error_message = "Every DEV partner must be explicitly marked mock-only."
    }
    precondition {
      condition     = var.environment != "PROD" || (var.production_deployment_enabled && var.production_change_reference != null && length(var.production_change_reference) >= 3)
      error_message = "PROD remains blocked unless an external human workflow supplies explicit enablement and a change reference."
    }
    precondition {
      condition     = alltrue([for artifact in values(var.configuration_artifacts) : can(regex("^[0-9a-f]{64}$", artifact.sha256))])
      error_message = "Every generated configuration artifact must have a SHA-256 digest."
    }
  }
}

module "network" {
  source = "../observability-network"

  name_prefix                       = local.name_prefix
  vpc_id                            = var.vpc_id
  vpc_cidr                          = var.vpc_cidr
  private_subnet_ids                = var.private_subnet_ids
  public_subnet_ids                 = var.public_subnet_ids
  source_service_security_group_ids = var.source_service_security_group_ids
  scrape_target_security_group_ids  = var.scrape_target_security_group_ids
  operator_security_group_ids       = var.operator_security_group_ids
  aws_endpoint_security_group_id    = var.aws_endpoint_security_group_id
  s3_prefix_list_id                 = var.s3_prefix_list_id
  grafana_acm_certificate_arn       = var.grafana_acm_certificate_arn
  alloy_ingress_acm_certificate_arn = var.alloy_ingress_acm_certificate_arn
  grafana_tls_security_policy       = var.grafana_tls_security_policy
  alloy_tls_security_policy         = var.alloy_tls_security_policy
  grafana_ingress_ipv4_cidrs        = var.grafana_ingress_ipv4_cidrs
  grafana_ingress_ipv6_cidrs        = var.grafana_ingress_ipv6_cidrs
  waf_web_acl_arn                   = var.waf_web_acl_arn
  route53_zone_id                   = var.route53_zone_id
  grafana_hostname                  = var.grafana_hostname
  alb_access_logs_bucket            = var.alb_access_logs_bucket
  enable_deletion_protection        = var.enable_deletion_protection
  service_discovery_namespace       = "observability.${var.market}.${lower(var.environment)}.internal"
  tags                              = local.tags
}

module "identity" {
  source = "../observability-identity"

  name_prefix                = local.name_prefix
  aws_partition              = var.aws_partition
  aws_region                 = var.aws_region
  aws_account_id             = var.aws_account_id
  cloudwatch_log_group_names = values(local.service_log_groups)
  ecr_repository_arns        = var.ecr_repository_arns
  secret_arns                = local.runtime_secret_arns
  secret_kms_key_arns        = var.secret_kms_key_arns
  configuration_object_arns  = local.configuration_object_arns
  loki_bucket_arn            = local.loki_bucket_arn
  loki_object_prefix         = var.loki_object_prefix
  loki_kms_key_arn           = var.loki_kms_key_arn
  tags                       = local.tags
}

module "loki_storage" {
  source = "../loki-storage"

  name_prefix               = local.name_prefix
  bucket_name               = var.loki_bucket_name
  object_prefix             = var.loki_object_prefix
  private_subnet_ids        = var.private_subnet_ids
  storage_security_group_id = module.network.security_group_ids.stateful_storage
  loki_task_role_arn        = module.identity.task_role_arns["loki"]
  break_glass_role_arn      = var.break_glass_role_arn
  kms_key_arn               = var.loki_kms_key_arn
  tags                      = local.tags
}

module "alloy" {
  source = "../ecs-alloy-ingest"

  name_prefix                    = local.name_prefix
  aws_region                     = var.aws_region
  ecs_cluster_arn                = var.ecs_cluster_arn
  ecs_cluster_name               = var.ecs_cluster_name
  private_subnet_ids             = var.private_subnet_ids
  security_group_id              = module.network.security_group_ids.alloy
  target_group_arn               = module.network.alloy_target_group_arn
  service_discovery_namespace_id = module.network.service_discovery_namespace_id
  service_discovery_namespace    = "observability.${var.market}.${lower(var.environment)}.internal"
  execution_role_arn             = module.identity.execution_role_arn
  task_role_arn                  = module.identity.task_role_arns["alloy"]
  alloy_image                    = var.images.alloy
  proxy_image                    = var.images.ingress_proxy
  configuration_s3_uri           = var.configuration_artifacts["alloy"].s3_uri
  configuration_sha256           = var.configuration_artifacts["alloy"].sha256
  proxy_configuration_s3_uri     = var.configuration_artifacts["alloy-proxy"].s3_uri
  proxy_configuration_sha256     = var.configuration_artifacts["alloy-proxy"].sha256
  proxy_secret_arns              = var.alloy_proxy_secret_arns
  cpu                            = var.sizing["alloy"].cpu
  memory                         = var.sizing["alloy"].memory
  desired_count                  = local.desired_stateless_count
  autoscaling_min_capacity       = local.desired_stateless_count
  autoscaling_max_capacity       = local.stateless_max_count
  capacity_provider_strategy     = var.capacity_provider_strategy
  log_group_name                 = local.service_log_groups.alloy
  cloudwatch_retention_days      = var.cloudwatch_retention_days
  cloudwatch_kms_key_arn         = var.cloudwatch_kms_key_arn
  tags                           = local.tags
}

module "loki" {
  source = "../ecs-loki"

  name_prefix                    = local.name_prefix
  aws_region                     = var.aws_region
  ecs_cluster_arn                = var.ecs_cluster_arn
  private_subnet_ids             = var.private_subnet_ids
  security_group_id              = module.network.security_group_ids.loki
  service_discovery_namespace_id = module.network.service_discovery_namespace_id
  execution_role_arn             = module.identity.execution_role_arn
  task_role_arn                  = module.identity.task_role_arns["loki"]
  image                          = var.images.loki
  configuration_s3_uri           = var.configuration_artifacts["loki"].s3_uri
  configuration_sha256           = var.configuration_artifacts["loki"].sha256
  loki_bucket_name               = module.loki_storage.bucket_name
  loki_object_prefix             = var.loki_object_prefix
  efs_file_system_id             = module.loki_storage.efs_file_system_id
  efs_access_point_id            = module.loki_storage.efs_access_point_id
  cpu                            = var.sizing["loki"].cpu
  memory                         = var.sizing["loki"].memory
  capacity_provider_strategy     = var.capacity_provider_strategy
  log_group_name                 = local.service_log_groups.loki
  cloudwatch_retention_days      = var.cloudwatch_retention_days
  cloudwatch_kms_key_arn         = var.cloudwatch_kms_key_arn
  tags                           = local.tags
}

module "prometheus" {
  source = "../ecs-prometheus"

  name_prefix                    = local.name_prefix
  aws_region                     = var.aws_region
  ecs_cluster_arn                = var.ecs_cluster_arn
  private_subnet_ids             = var.private_subnet_ids
  security_group_id              = module.network.security_group_ids.prometheus
  storage_security_group_id      = module.network.security_group_ids.stateful_storage
  service_discovery_namespace_id = module.network.service_discovery_namespace_id
  execution_role_arn             = module.identity.execution_role_arn
  task_role_arn                  = module.identity.task_role_arns["prometheus"]
  image                          = var.images.prometheus
  configuration_s3_uri           = var.configuration_artifacts["prometheus"].s3_uri
  configuration_sha256           = var.configuration_artifacts["prometheus"].sha256
  retention_size_gib             = local.prometheus_size_gib
  efs_kms_key_arn                = var.efs_kms_key_arn
  cpu                            = var.sizing["prometheus"].cpu
  memory                         = var.sizing["prometheus"].memory
  capacity_provider_strategy     = var.capacity_provider_strategy
  log_group_name                 = local.service_log_groups.prometheus
  cloudwatch_retention_days      = var.cloudwatch_retention_days
  cloudwatch_kms_key_arn         = var.cloudwatch_kms_key_arn
  tags                           = local.tags
}

module "query_gateway" {
  source = "../ecs-query-gateway"

  name_prefix                    = local.name_prefix
  aws_region                     = var.aws_region
  ecs_cluster_arn                = var.ecs_cluster_arn
  ecs_cluster_name               = var.ecs_cluster_name
  private_subnet_ids             = var.private_subnet_ids
  security_group_id              = module.network.security_group_ids.query_gateway
  service_discovery_namespace_id = module.network.service_discovery_namespace_id
  service_discovery_namespace    = "observability.${var.market}.${lower(var.environment)}.internal"
  execution_role_arn             = module.identity.execution_role_arn
  task_role_arn                  = module.identity.task_role_arns["query-gateway"]
  proxy_image                    = var.images.ingress_proxy
  prom_label_proxy_image         = var.images.prom_label_proxy
  journey_resolver_image         = var.images.journey_resolver
  configuration_s3_uri           = var.configuration_artifacts["query-gateway"].s3_uri
  configuration_sha256           = var.configuration_artifacts["query-gateway"].sha256
  secret_arns                    = var.query_gateway_secret_arns
  cpu                            = var.sizing["query_gateway"].cpu
  memory                         = var.sizing["query_gateway"].memory
  desired_count                  = local.desired_stateless_count
  autoscaling_min_capacity       = local.desired_stateless_count
  autoscaling_max_capacity       = local.stateless_max_count
  capacity_provider_strategy     = var.capacity_provider_strategy
  log_group_name                 = local.service_log_groups.query_gateway
  cloudwatch_retention_days      = var.cloudwatch_retention_days
  cloudwatch_kms_key_arn         = var.cloudwatch_kms_key_arn
  tags                           = local.tags
}

module "grafana" {
  source = "../ecs-grafana"

  name_prefix                = local.name_prefix
  aws_region                 = var.aws_region
  ecs_cluster_arn            = var.ecs_cluster_arn
  private_subnet_ids         = var.private_subnet_ids
  security_group_id          = module.network.security_group_ids.grafana
  storage_security_group_id  = module.network.security_group_ids.stateful_storage
  target_group_arn           = module.network.grafana_target_group_arn
  execution_role_arn         = module.identity.execution_role_arn
  task_role_arn              = module.identity.task_role_arns["grafana"]
  image                      = var.images.grafana
  configuration_s3_uri       = var.configuration_artifacts["grafana"].s3_uri
  configuration_sha256       = var.configuration_artifacts["grafana"].sha256
  external_hostname          = var.grafana_hostname
  secret_arns                = var.grafana_secret_arns
  backup_role_arn            = var.grafana_backup_role_arn
  backup_kms_key_arn         = var.backup_kms_key_arn
  efs_kms_key_arn            = var.efs_kms_key_arn
  cpu                        = var.sizing["grafana"].cpu
  memory                     = var.sizing["grafana"].memory
  capacity_provider_strategy = var.capacity_provider_strategy
  log_group_name             = local.service_log_groups.grafana
  cloudwatch_retention_days  = var.cloudwatch_retention_days
  cloudwatch_kms_key_arn     = var.cloudwatch_kms_key_arn
  tags                       = local.tags
}

module "alerts" {
  source = "../observability-alerts"

  name_prefix      = local.name_prefix
  ecs_cluster_name = var.ecs_cluster_name
  service_names = {
    alloy         = module.alloy.service_name
    loki          = module.loki.service_name
    prometheus    = module.prometheus.service_name
    grafana       = module.grafana.service_name
    query_gateway = module.query_gateway.service_name
  }
  grafana_load_balancer_arn_suffix = module.network.grafana_alb_arn_suffix
  grafana_target_group_arn_suffix  = module.network.grafana_target_group_arn_suffix
  notification_topic_arn           = var.notification_topic_arn
  tags                             = local.tags
}

output "grafana_https_endpoint" { value = "https://${var.grafana_hostname}" }
output "alloy_private_ingress_dns_name" { value = module.network.alloy_ingress_dns_name }
output "partner_manifest_sha256" { value = terraform_data.deployment_contract.output }
output "loki_bucket_name" { value = module.loki_storage.bucket_name }
output "service_names" {
  value = {
    alloy         = module.alloy.service_name
    loki          = module.loki.service_name
    prometheus    = module.prometheus.service_name
    grafana       = module.grafana.service_name
    query_gateway = module.query_gateway.service_name
  }
}
