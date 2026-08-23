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
  service_names = toset(["alloy", "loki", "prometheus", "grafana", "query-gateway"])
  log_resources = [for name in var.cloudwatch_log_group_names : "arn:${var.aws_partition}:logs:${var.aws_region}:${var.aws_account_id}:log-group:${name}:*"]
}

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "execution" {
  statement {
    sid       = "WriteNamedLogGroups"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = local.log_resources
  }
  statement {
    sid       = "ReadPinnedImageRepositories"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
    resources = var.ecr_repository_arns
  }
  statement {
    sid       = "GetEcrAuthorizationToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  dynamic "statement" {
    for_each = length(var.secret_arns) == 0 ? [] : [1]
    content {
      sid       = "ReadNamedRuntimeSecrets"
      actions   = ["secretsmanager:GetSecretValue", "ssm:GetParameter", "ssm:GetParameters"]
      resources = var.secret_arns
    }
  }
  dynamic "statement" {
    for_each = length(var.secret_kms_key_arns) == 0 ? [] : [1]
    content {
      sid       = "DecryptNamedSecretKeys"
      actions   = ["kms:Decrypt"]
      resources = var.secret_kms_key_arns
      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["secretsmanager.${var.aws_region}.amazonaws.com", "ssm.${var.aws_region}.amazonaws.com"]
      }
    }
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "${var.name_prefix}-execution"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

resource "aws_iam_role" "task" {
  for_each           = local.service_names
  name               = "${var.name_prefix}-${each.value}"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "artifact_read" {
  for_each = local.service_names
  statement {
    sid       = "ReadValidatedConfigurationArtifact"
    actions   = ["s3:GetObject"]
    resources = var.configuration_object_arns[each.value]
  }
}

resource "aws_iam_role_policy" "artifact_read" {
  for_each = local.service_names
  name     = "${var.name_prefix}-${each.value}-configuration"
  role     = aws_iam_role.task[each.value].id
  policy   = data.aws_iam_policy_document.artifact_read[each.value].json
}

data "aws_iam_policy_document" "loki_storage" {
  statement {
    sid       = "ListLokiTelemetryBucket"
    actions   = ["s3:GetBucketLocation", "s3:ListBucket", "s3:ListBucketVersions"]
    resources = [var.loki_bucket_arn]
  }
  statement {
    sid       = "ManageLokiTelemetryObjects"
    actions   = ["s3:AbortMultipartUpload", "s3:DeleteObject", "s3:GetObject", "s3:ListMultipartUploadParts", "s3:PutObject"]
    resources = ["${var.loki_bucket_arn}/${var.loki_object_prefix}/*"]
  }
  dynamic "statement" {
    for_each = var.loki_kms_key_arn == null ? [] : [1]
    content {
      sid       = "UseNamedLokiStorageKey"
      actions   = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey"]
      resources = [var.loki_kms_key_arn]
      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["s3.${var.aws_region}.amazonaws.com"]
      }
    }
  }
}

resource "aws_iam_role_policy" "loki_storage" {
  name   = "${var.name_prefix}-loki-storage"
  role   = aws_iam_role.task["loki"].id
  policy = data.aws_iam_policy_document.loki_storage.json
}

output "execution_role_arn" { value = aws_iam_role.execution.arn }
output "task_role_arns" { value = { for name, role in aws_iam_role.task : name => role.arn } }
