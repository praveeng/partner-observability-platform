terraform {
  required_version = ">= 1.7.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80.0, < 7.0.0"
    }
  }
}

resource "aws_cloudwatch_log_group" "this" {
  name              = var.log_group_name
  retention_in_days = var.cloudwatch_retention_days
  kms_key_id        = var.cloudwatch_kms_key_arn
  tags              = var.tags
}

resource "aws_service_discovery_service" "this" {
  name = "query-gateway"
  dns_config {
    namespace_id = var.service_discovery_namespace_id
    dns_records {
      ttl  = 10
      type = "A"
    }
    routing_policy = "MULTIVALUE"
  }
  tags = var.tags
}

resource "aws_ecs_task_definition" "this" {
  family                   = "${var.name_prefix}-query-gateway"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name                   = "query-proxy"
      image                  = var.proxy_image
      essential              = true
      readonlyRootFilesystem = true
      user                   = "101:101"
      portMappings           = [{ name = "query", containerPort = var.container_port, protocol = "tcp" }]
      environment = [
        { name = "CONFIG_S3_URI", value = var.configuration_s3_uri },
        { name = "CONFIG_SHA256", value = var.configuration_sha256 },
        { name = "LOKI_URL", value = "http://loki.${var.service_discovery_namespace}:3100" },
        { name = "PROM_LABEL_PROXY_URL", value = "http://127.0.0.1:${var.prom_label_proxy_port}" },
        { name = "JOURNEY_RESOLVER_URL", value = "http://127.0.0.1:${var.journey_resolver_port}" }
      ]
      secrets = [for name, arn in var.secret_arns : { name = name, valueFrom = arn }]
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:${var.container_port}/healthz || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 30
      }
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "query-proxy"
        }
      }
    },
    {
      name                   = "prom-label-proxy"
      image                  = var.prom_label_proxy_image
      essential              = true
      readonlyRootFilesystem = true
      user                   = "65534:65534"
      portMappings           = [{ name = "prom-proxy", containerPort = var.prom_label_proxy_port, protocol = "tcp" }]
      command = [
        "-upstream=http://prometheus.${var.service_discovery_namespace}:9090",
        "-label=partner_slot",
        "-header-name=X-Partner-Slot",
        "-listen-address=0.0.0.0:${var.prom_label_proxy_port}"
      ]
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:${var.prom_label_proxy_port}/-/ready || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 15
      }
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "prom-label-proxy"
        }
      }
    },
    {
      name                   = "journey-resolver"
      image                  = var.journey_resolver_image
      essential              = true
      readonlyRootFilesystem = true
      user                   = "65534:65534"
      portMappings           = [{ name = "journey", containerPort = var.journey_resolver_port, protocol = "tcp" }]
      environment = [
        { name = "CONFIG_S3_URI", value = var.configuration_s3_uri },
        { name = "CONFIG_SHA256", value = var.configuration_sha256 },
        { name = "LOKI_URL", value = "http://loki.${var.service_discovery_namespace}:3100" },
        { name = "MAX_IN_FLIGHT", value = "20" },
        { name = "MAX_QUERY_SECONDS", value = "10" },
        { name = "MAX_RESPONSE_BYTES", value = "2097152" },
        { name = "MAX_ROUNDS", value = "3" },
        { name = "MAX_IDENTIFIERS", value = "32" },
        { name = "MAX_RECORDS_PER_ROUND", value = "500" }
      ]
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:${var.journey_resolver_port}/healthz || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 15
      }
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.this.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "journey-resolver"
        }
      }
    }
  ])
  tags = var.tags
}

resource "aws_ecs_service" "this" {
  name                               = "${var.name_prefix}-query-gateway"
  cluster                            = var.ecs_cluster_arn
  task_definition                    = aws_ecs_task_definition.this.arn
  desired_count                      = var.desired_count
  launch_type                        = length(var.capacity_provider_strategy) == 0 ? "FARGATE" : null
  platform_version                   = length(var.capacity_provider_strategy) == 0 ? var.fargate_platform_version : null
  deployment_minimum_healthy_percent = 50
  deployment_maximum_percent         = 200
  enable_execute_command             = false

  dynamic "capacity_provider_strategy" {
    for_each = var.capacity_provider_strategy
    content {
      capacity_provider = capacity_provider_strategy.value.capacity_provider
      weight            = capacity_provider_strategy.value.weight
      base              = capacity_provider_strategy.value.base
    }
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    assign_public_ip = false
    subnets          = var.private_subnet_ids
    security_groups  = [var.security_group_id]
  }
  service_registries { registry_arn = aws_service_discovery_service.this.arn }
  tags = var.tags
}

resource "aws_appautoscaling_target" "this" {
  max_capacity       = var.autoscaling_max_capacity
  min_capacity       = var.autoscaling_min_capacity
  resource_id        = "service/${var.ecs_cluster_name}/${aws_ecs_service.this.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.name_prefix}-query-gateway-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.this.resource_id
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  target_tracking_scaling_policy_configuration {
    target_value       = var.autoscaling_cpu_target
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    predefined_metric_specification { predefined_metric_type = "ECSServiceAverageCPUUtilization" }
  }
}

output "service_name" { value = aws_ecs_service.this.name }
