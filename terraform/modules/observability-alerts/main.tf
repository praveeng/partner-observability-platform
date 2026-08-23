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
  alarm_actions = var.notification_topic_arn == null ? [] : [var.notification_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "cpu_review" {
  for_each            = var.service_names
  alarm_name          = "${var.name_prefix}-${each.key}-cpu-review"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/ECS"
  period              = 300
  statistic           = "Average"
  threshold           = 70
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  dimensions = {
    ClusterName = var.ecs_cluster_name
    ServiceName = each.value
  }
  tags = var.tags
}
resource "aws_cloudwatch_metric_alarm" "memory_critical" {
  for_each            = var.service_names
  alarm_name          = "${var.name_prefix}-${each.key}-memory-critical"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 2
  metric_name         = "MemoryUtilization"
  namespace           = "AWS/ECS"
  period              = 300
  statistic           = "Average"
  threshold           = 85
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  dimensions = {
    ClusterName = var.ecs_cluster_name
    ServiceName = each.value
  }
  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "grafana_unhealthy" {
  alarm_name          = "${var.name_prefix}-grafana-unhealthy-target"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "UnHealthyHostCount"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Maximum"
  threshold           = 0
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions
  dimensions = {
    LoadBalancer = var.grafana_load_balancer_arn_suffix
    TargetGroup  = var.grafana_target_group_arn_suffix
  }
  tags = var.tags
}
