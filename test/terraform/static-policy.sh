#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

require_match() {
  local pattern="$1"
  shift
  rg -q "$pattern" "$@" || fail "required Terraform policy pattern missing: $pattern"
}

reject_match() {
  local pattern="$1"
  shift
  if rg -n "$pattern" "$@"; then
    fail "prohibited Terraform policy pattern present: $pattern"
  fi
}

network_tf="terraform/modules/observability-network/main.tf"
module_tree="terraform/modules"

require_match 'resource "aws_lb_listener" "grafana_https"' "$network_tf"
require_match 'port[[:space:]]*=[[:space:]]*443' "$network_tf"
require_match 'protocol[[:space:]]*=[[:space:]]*"HTTPS"' "$network_tf"
require_match 'certificate_arn[[:space:]]*=[[:space:]]*var\.grafana_acm_certificate_arn' "$network_tf"
require_match 'ssl_policy[[:space:]]*=[[:space:]]*var\.grafana_tls_security_policy' "$network_tf"
require_match 'resource "aws_wafv2_web_acl_association" "grafana"' "$network_tf"
reject_match 'port[[:space:]]*=[[:space:]]*80([^0-9]|$)' "$module_tree" --glob '*.tf'

listener_count="$(rg -n '^resource "aws_lb_listener"' "$module_tree" --glob '*.tf' | wc -l | tr -d ' ')"
[[ "$listener_count" == "2" ]] || fail "expected only Grafana HTTPS and private Alloy TLS listeners, found $listener_count"
reject_match 'resource "aws_lb_listener" "(loki|prometheus|grafana_http|alloy_http)"' "$module_tree" --glob '*.tf'

service_count="$(rg -n '^resource "aws_ecs_service" "this"' terraform/modules/ecs-* --glob '*.tf' | wc -l | tr -d ' ')"
private_count="$(rg -n 'assign_public_ip[[:space:]]*=[[:space:]]*false' terraform/modules/ecs-* --glob '*.tf' | wc -l | tr -d ' ')"
[[ "$service_count" == "5" && "$private_count" == "$service_count" ]] || fail "every one of the five ECS services must explicitly disable public IPs"
reject_match 'assign_public_ip[[:space:]]*=[[:space:]]*true' "$module_tree" --glob '*.tf'

require_match 'referenced_security_group_id[[:space:]]*=[[:space:]]*aws_security_group\.grafana_alb\.id' "$network_tf"
require_match 'referenced_security_group_id[[:space:]]*=[[:space:]]*aws_security_group\.alloy_tasks\.id' "$network_tf"
require_match 'referenced_security_group_id[[:space:]]*=[[:space:]]*aws_security_group\.query_gateway_tasks\.id' "$network_tf"
reject_match 'cidr_ipv4[[:space:]]*=[[:space:]]*"0\.0\.0\.0/0"' "$module_tree" --glob '*.tf'
reject_match 'cidr_ipv6[[:space:]]*=[[:space:]]*"::/0"' "$module_tree" --glob '*.tf'

require_match 'expiration[[:space:]]*\{[[:space:]]*days[[:space:]]*=[[:space:]]*18' terraform/modules/loki-storage/main.tf
require_match 'status[[:space:]]*=[[:space:]]*"Disabled"' terraform/modules/loki-storage/main.tf
require_match 'LOKI_RETENTION_PERIOD.*384h' terraform/modules/ecs-loki/main.tf
require_match 'storage\.tsdb\.retention\.time=16d' terraform/modules/ecs-prometheus/main.tf

encrypted_efs_count="$(rg -n 'encrypted[[:space:]]*=[[:space:]]*true' terraform/modules/loki-storage terraform/modules/ecs-prometheus terraform/modules/ecs-grafana --glob '*.tf' | wc -l | tr -d ' ')"
[[ "$encrypted_efs_count" == "3" ]] || fail "exactly three encrypted EFS state stores are required"
require_match 'aws_s3_bucket_server_side_encryption_configuration' terraform/modules/loki-storage/main.tf

reject_match 'output ".*(certificate|secret|password|private_key)' "$module_tree" --glob '*.tf'
require_match 'production_deployment_enabled' terraform/modules/market-observability-stack/main.tf
require_match 'dev_mock_only' terraform/modules/market-observability-stack/main.tf terraform/examples/shared/main.tf
require_match 'callback_ingress_evidence' terraform/modules/market-observability-stack/variables.tf terraform/examples/shared/main.tf
require_match '@sha256:\$\{local\.synthetic_digest\}' terraform/examples/shared/main.tf

wildcard_resource_count="$(rg -n 'resources[[:space:]]*=[[:space:]]*\["\*"\]' terraform/modules/observability-identity/main.tf | wc -l | tr -d ' ')"
[[ "$wildcard_resource_count" == "1" ]] || fail "only ECR GetAuthorizationToken may require wildcard IAM resource scope"
require_match 'actions[[:space:]]*=[[:space:]]*\["ecr:GetAuthorizationToken"\]' terraform/modules/observability-identity/main.tf

reject_match '(?i)(helm_release|kubernetes_|k8s_)' "$module_tree" --glob '*.tf'
reject_match '(?i)(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16})' terraform --glob '*'

echo "PASS: Terraform static HTTPS, private-task, storage, IAM, secret-reference, and onboarding policies."
