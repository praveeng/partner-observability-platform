#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

for command_name in jq rg; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "ERROR: required command is unavailable: $command_name" >&2
    exit 1
  }
done

git diff --check
git diff --cached --check
jq empty .agent-state/status.json
for shell_file in scripts/*.sh test/integration/*.sh test/terraform/*.sh; do
  bash -n "$shell_file"
done

for requirement_id in $(seq 1 49); do
  rg -q "^\| ${requirement_id} \|" docs/acceptance-criteria.md || {
    echo "ERROR: acceptance-criteria.md has no completion-gate mapping for requirement $requirement_id" >&2
    exit 1
  }
done

for required_command in \
  './gradlew --no-daemon clean build' \
  './scripts/test-security.sh' \
  './scripts/test-performance.sh' \
  './scripts/test-terraform.sh' \
  './scripts/test-grafana.sh' \
  './scripts/test-end-to-end.sh'; do
  rg -q -F "$required_command" scripts/verify-all.sh || {
    echo "ERROR: verify-all.sh does not invoke mandatory command: $required_command" >&2
    exit 1
  }
done

rg -q 'JavaLanguageVersion\.of\(17\)' build.gradle
rg -q "org\.springframework\.boot.*2\.7\.18" build.gradle
rg -q 'LOKI_RETENTION_PERIOD.*384h' terraform/modules/ecs-loki/main.tf
rg -q 'retention_period:[[:space:]]*24h' loki/local-config.yaml
rg -q -- '--storage\.tsdb\.retention\.time=16d' docker/compose.yml
rg -q 'auth_enabled:[[:space:]]*true' loki/local-config.yaml

if rg -n -i '(helm_release|kubernetes_|apiVersion:[[:space:]]*(apps/|v1))' \
    partner-observability-* alloy loki prometheus grafana terraform docker \
    --glob '!*.md' --glob '!**/build/**'; then
  echo 'ERROR: prohibited Kubernetes/Helm implementation artifact found.' >&2
  exit 1
fi

echo 'PASS: documentation mapping, state JSON, shell syntax, version, retention, tenancy, and deployment-model consistency.'
