terraform {
  required_version = ">= 1.7.0, < 2.0.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 5.80.0, < 7.0.0" }
  }
}

resource "aws_cloudwatch_log_group" "this" {
  name              = var.log_group_name
  retention_in_days = var.cloudwatch_retention_days
  kms_key_id        = var.cloudwatch_kms_key_arn
  tags              = var.tags
}

resource "aws_service_discovery_service" "this" {
  name = "loki"
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
  family                   = "${var.name_prefix}-loki"
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

  volume {
    name = "loki-state"
    efs_volume_configuration {
      file_system_id     = var.efs_file_system_id
      transit_encryption = "ENABLED"
      authorization_config {
        access_point_id = var.efs_access_point_id
        iam             = "DISABLED"
      }
    }
  }

  container_definitions = jsonencode([{
    name                   = "loki"
    image                  = var.image
    essential              = true
    readonlyRootFilesystem = true
    user                   = "10001:10001"
    portMappings           = [{ name = "loki", containerPort = var.container_port, protocol = "tcp" }]
    mountPoints            = [{ sourceVolume = "loki-state", containerPath = "/loki", readOnly = false }]
    environment = [
      { name = "CONFIG_S3_URI", value = var.configuration_s3_uri },
      { name = "CONFIG_SHA256", value = var.configuration_sha256 },
      { name = "LOKI_S3_BUCKET", value = var.loki_bucket_name },
      { name = "LOKI_S3_PREFIX", value = var.loki_object_prefix },
      { name = "LOKI_RETENTION_PERIOD", value = "384h" },
      { name = "LOKI_RETENTION_DELETE_DELAY", value = "2h" }
    ]
    command = [
      "-config.file=/etc/loki/loki.yml",
      "-compactor.retention-enabled=true",
      "-compactor.retention-delete-delay=2h",
      "-limits.retention-period=384h"
    ]
    healthCheck = {
      command     = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:${var.container_port}/ready || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "loki"
      }
    }
  }])
  tags = var.tags
}

resource "aws_ecs_service" "this" {
  name                               = "${var.name_prefix}-loki"
  cluster                            = var.ecs_cluster_arn
  task_definition                    = aws_ecs_task_definition.this.arn
  desired_count                      = 1
  launch_type                        = length(var.capacity_provider_strategy) == 0 ? "FARGATE" : null
  platform_version                   = length(var.capacity_provider_strategy) == 0 ? var.fargate_platform_version : null
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  enable_execute_command             = false
  health_check_grace_period_seconds  = 60

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

output "service_name" { value = aws_ecs_service.this.name }
output "task_definition_arn" { value = aws_ecs_task_definition.this.arn }
