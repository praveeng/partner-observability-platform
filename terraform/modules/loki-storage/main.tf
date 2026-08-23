terraform {
  required_version = ">= 1.7.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.80.0, < 7.0.0"
    }
  }
}

resource "aws_s3_bucket" "loki" {
  bucket        = var.bucket_name
  force_destroy = false
  tags          = var.tags
}

resource "aws_s3_bucket_public_access_block" "loki" {
  bucket                  = aws_s3_bucket.loki.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "loki" {
  bucket = aws_s3_bucket.loki.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_versioning" "loki" {
  bucket = aws_s3_bucket.loki.id
  versioning_configuration { status = "Disabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "loki" {
  bucket = aws_s3_bucket.loki.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = var.kms_key_arn == null ? "AES256" : "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = var.kms_key_arn != null
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "loki" {
  bucket = aws_s3_bucket.loki.id
  rule {
    id     = "partner-telemetry-18-day-backstop"
    status = "Enabled"
    filter { prefix = "${var.object_prefix}/" }
    expiration { days = 18 }
    abort_incomplete_multipart_upload { days_after_initiation = 1 }
  }
}

data "aws_iam_policy_document" "loki" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.loki.arn,
      "${aws_s3_bucket.loki.arn}/*"
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid     = "DenyTelemetryAccessOutsideApprovedRoles"
    effect  = "Deny"
    actions = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket"]
    resources = [
      aws_s3_bucket.loki.arn,
      "${aws_s3_bucket.loki.arn}/${var.object_prefix}/*"
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = compact([var.loki_task_role_arn, var.break_glass_role_arn])
    }
  }
}

resource "aws_s3_bucket_policy" "loki" {
  bucket = aws_s3_bucket.loki.id
  policy = data.aws_iam_policy_document.loki.json
}

resource "aws_efs_file_system" "loki" {
  encrypted        = true
  kms_key_id       = var.kms_key_arn
  throughput_mode  = "bursting"
  performance_mode = "generalPurpose"
  creation_token   = "${var.name_prefix}-loki"
  lifecycle_policy { transition_to_ia = "AFTER_7_DAYS" }
  lifecycle_policy { transition_to_primary_storage_class = "AFTER_1_ACCESS" }
  tags = merge(var.tags, { Name = "${var.name_prefix}-loki" })
}

resource "aws_efs_mount_target" "loki" {
  for_each        = toset(var.private_subnet_ids)
  file_system_id  = aws_efs_file_system.loki.id
  subnet_id       = each.value
  security_groups = [var.storage_security_group_id]
}

resource "aws_efs_access_point" "loki" {
  file_system_id = aws_efs_file_system.loki.id
  posix_user {
    gid = 10001
    uid = 10001
  }
  root_directory {
    path = "/loki"
    creation_info {
      owner_gid   = 10001
      owner_uid   = 10001
      permissions = "0750"
    }
  }
  tags = var.tags
}

output "bucket_name" { value = aws_s3_bucket.loki.id }
output "bucket_arn" { value = aws_s3_bucket.loki.arn }
output "efs_file_system_id" { value = aws_efs_file_system.loki.id }
output "efs_access_point_id" { value = aws_efs_access_point.loki.id }
