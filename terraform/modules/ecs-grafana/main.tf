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

resource "aws_efs_file_system" "this" {
  encrypted        = true
  kms_key_id       = var.efs_kms_key_arn
  throughput_mode  = "bursting"
  performance_mode = "generalPurpose"
  creation_token   = "${var.name_prefix}-grafana"
  lifecycle_policy { transition_to_ia = "AFTER_7_DAYS" }
  lifecycle_policy { transition_to_primary_storage_class = "AFTER_1_ACCESS" }
  tags = merge(var.tags, { Name = "${var.name_prefix}-grafana" })
}

resource "aws_efs_mount_target" "this" {
  for_each        = toset(var.private_subnet_ids)
  file_system_id  = aws_efs_file_system.this.id
  subnet_id       = each.value
  security_groups = [var.storage_security_group_id]
}

resource "aws_efs_access_point" "this" {
  file_system_id = aws_efs_file_system.this.id
  posix_user {
    gid = 472
    uid = 472
  }
  root_directory {
    path = "/grafana"
    creation_info {
      owner_gid   = 472
      owner_uid   = 472
      permissions = "0750"
    }
  }
  tags = var.tags
}

resource "aws_backup_vault" "this" {
  name        = "${var.name_prefix}-grafana"
  kms_key_arn = var.backup_kms_key_arn
  tags        = var.tags
}

resource "aws_backup_plan" "this" {
  name = "${var.name_prefix}-grafana-daily"
  rule {
    rule_name         = "daily-seven-day-retention"
    target_vault_name = aws_backup_vault.this.name
    schedule          = var.backup_schedule
    lifecycle { delete_after = 7 }
  }
  tags = var.tags
}

resource "aws_backup_selection" "this" {
  iam_role_arn = var.backup_role_arn
  name         = "${var.name_prefix}-grafana-efs"
  plan_id      = aws_backup_plan.this.id
  resources    = [aws_efs_file_system.this.arn]
}

resource "aws_ecs_task_definition" "this" {
  family                   = "${var.name_prefix}-grafana"
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
    name = "grafana-state"
    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.this.id
      transit_encryption = "ENABLED"
      authorization_config {
        access_point_id = aws_efs_access_point.this.id
        iam             = "DISABLED"
      }
    }
  }

  container_definitions = jsonencode([{
    name                   = "grafana"
    image                  = var.image
    essential              = true
    readonlyRootFilesystem = true
    user                   = "472:472"
    portMappings           = [{ name = "grafana", containerPort = var.container_port, protocol = "tcp" }]
    mountPoints            = [{ sourceVolume = "grafana-state", containerPath = "/var/lib/grafana", readOnly = false }]
    environment = [
      { name = "CONFIG_S3_URI", value = var.configuration_s3_uri },
      { name = "CONFIG_SHA256", value = var.configuration_sha256 },
      { name = "GF_SERVER_ROOT_URL", value = "https://${var.external_hostname}" },
      { name = "GF_SERVER_PROTOCOL", value = "http" },
      { name = "GF_USERS_ALLOW_SIGN_UP", value = "false" },
      { name = "GF_AUTH_ANONYMOUS_ENABLED", value = "false" },
      { name = "GF_SECURITY_COOKIE_SECURE", value = "true" },
      { name = "GF_SECURITY_COOKIE_SAMESITE", value = "strict" },
      { name = "GF_SECURITY_DISABLE_GRAVATAR", value = "true" },
      { name = "GF_EXPLORE_ENABLED", value = "false" }
    ]
    secrets = [for name, arn in var.secret_arns : { name = name, valueFrom = arn }]
    healthCheck = {
      command     = ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:${var.container_port}/api/health || exit 1"]
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
        awslogs-stream-prefix = "grafana"
      }
    }
  }])
  tags = var.tags
}

resource "aws_ecs_service" "this" {
  name                               = "${var.name_prefix}-grafana"
  cluster                            = var.ecs_cluster_arn
  task_definition                    = aws_ecs_task_definition.this.arn
  desired_count                      = 1
  launch_type                        = length(var.capacity_provider_strategy) == 0 ? "FARGATE" : null
  platform_version                   = length(var.capacity_provider_strategy) == 0 ? var.fargate_platform_version : null
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  enable_execute_command             = false
  health_check_grace_period_seconds  = 90

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
  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "grafana"
    container_port   = var.container_port
  }
  tags = var.tags
}

output "service_name" { value = aws_ecs_service.this.name }
output "efs_file_system_arn" { value = aws_efs_file_system.this.arn }
