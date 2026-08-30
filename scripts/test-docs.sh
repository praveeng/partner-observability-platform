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
for shell_file in scripts/*.sh test/integration/*.sh; do
  bash -n "$shell_file"
done

for (( requirement_id = 1; requirement_id <= 49; requirement_id++ )); do
  rg -q "^\| ${requirement_id} \|" docs/acceptance-criteria.md || {
    echo "ERROR: acceptance-criteria.md has no completion-gate mapping for requirement $requirement_id" >&2
    exit 1
  }
done

for required_command in \
  './gradlew --no-daemon clean build' \
  './scripts/test-security.sh' \
  './scripts/test-enterprise-naming.sh' \
  './scripts/validate-profiles.sh' \
  './scripts/test-enterprise-infrastructure-contract.sh' \
  './scripts/test-performance.sh' \
  './scripts/test-grafana.sh' \
  './scripts/test-end-to-end.sh'; do
  rg -q -F "$required_command" scripts/verify-all.sh || {
    echo "ERROR: verify-all.sh does not invoke mandatory command: $required_command" >&2
    exit 1
  }
done

rg -q 'JavaLanguageVersion\.of\(17\)' build.gradle
rg -q "org\.springframework\.boot.*2\.7\.18" build.gradle
rg -q 'lokiRetentionHours:[[:space:]]*384' docs/enterprise-infrastructure/infrastructure-contract.yaml
rg -q 'retention_period:[[:space:]]*24h' loki/local-config.yaml
rg -q -- '--storage\.tsdb\.retention\.time=16d' docker/compose.yml
rg -q 'auth_enabled:[[:space:]]*true' loki/local-config.yaml
rg -q 'enterprise Terraform is outside this repository' scripts/verify-all.sh
if rg -n 'grafana/(alloy|loki):|prom/prometheus:|nginx:[0-9]' test/integration --glob '*.sh'; then
  echo 'ERROR: integration validators must use the digest-pinned image resolved from Compose, not a mutable tag.' >&2
  exit 1
fi

if rg -n -i '(helm_release|kubernetes_|apiVersion:[[:space:]]*(apps/|v1))' \
    sure-partner-observability-* alloy loki prometheus grafana docker \
    --glob '!*.md' --glob '!**/build/**'; then
  echo 'ERROR: prohibited Kubernetes/Helm implementation artifact found.' >&2
  exit 1
fi

echo 'PASS: documentation mapping, state JSON, shell syntax, version, retention, tenancy, and deployment-model consistency.'
