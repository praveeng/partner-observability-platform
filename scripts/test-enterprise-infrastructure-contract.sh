#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

required_documents=(
  README.md requirements.md profile-model.md ecs-requirements.md network-requirements.md
  security-requirements.md iam-requirements.md loki-s3-requirements.md alloy-requirements.md
  prometheus-requirements.md grafana-base-requirements.md grafana-deployment-boundary.md
  github-actions-contract.md terraform-integration-guide.md stage-requirements.md prod-requirements.md
  infrastructure-contract.yaml legacy-terraform-classification.md
)

contract_root="docs/enterprise-infrastructure"
for document in "${required_documents[@]}"; do
  [[ -f "$contract_root/$document" ]] || fail "missing enterprise infrastructure contract document: $document"
done

[[ ! -d terraform ]] || fail 'repository-owned enterprise Terraform directory is prohibited'
if find . -type f \
    \( -name '*.tf' -o -name '*.tfvars' -o -name '*.tfplan' -o -name '.terraform.lock.hcl' \) \
    -not -path './.git/*' -print -quit | grep -q .; then
  fail 'enterprise Terraform implementation/state/plan files are prohibited in this repository'
fi

contract="$contract_root/infrastructure-contract.yaml"
for required_line in \
  'infrastructureOwner: central-enterprise-terraform-repository' \
  'awsActionsAllowedFromThisRepository: false' \
  'LOCAL: false' 'DEV: false' 'STAGE: true' 'PROD: true' \
  'springProfilesDefined: []' 'databaseRequired: false' \
  'externalIngress: HTTPS_443_ONLY' 'lokiRetentionHours: 384' \
  'prometheusRetentionDays: 16'; do
  rg -q -F "$required_line" "$contract" || fail "machine contract missing: $required_line"
done

rg -q 'AWS enterprise Terraform does not belong to this repository' AGENTS.md ||
  fail 'AGENTS.md lacks the permanent enterprise infrastructure ownership rule'
rg -q 'NO DATABASE REQUIRED FOR CURRENT ARCHITECTURE' "$contract_root/requirements.md" ||
  fail 'database determination is missing'
rg -q 'no Spring profile definitions' "$contract_root/profile-model.md" ||
  fail 'actual Spring profile discovery is not documented'
rg -q 'centralized enterprise Terraform repository is changed' "$contract_root/github-actions-contract.md" ||
  fail 'manual infrastructure-before-GHA sequence is missing'

enum_file='sure-partner-observability-core/src/main/java/com/samsung/sure/partner/observability/core/context/DeploymentEnvironment.java'
for environment in DEV STAGE PROD; do
  rg -q "^[[:space:]]*${environment}[,]?$" "$enum_file" ||
    fail "runtime environment enum is missing $environment"
done
if rg -n -i 'spring[._-]profiles|profiles[._-]active|activate[._-]on-profile|@Profile|SPRING_PROFILES_ACTIVE' \
    sure-partner-observability-* docker scripts test --glob '!**/build/**' \
    --glob '!test-enterprise-infrastructure-contract.sh'; then
  fail 'Spring profiles were introduced without a separate application configuration decision'
fi

rg -q 'local-synthetic:[[:space:]]*true' sure-partner-observability-test-app/src/main/resources/application.yml ||
  fail 'LOCAL_SYNTHETIC test guard changed unexpectedly'
rg -q 'environment:[[:space:]]*DEV' sure-partner-observability-test-app/src/main/resources/application.yml ||
  fail 'local test application environment changed unexpectedly'

terraform_word='terra''form'
execution_pattern="${terraform_word}[[:space:]]+(apply|plan|init|validate|fmt|test|destroy|import)"
if rg -n -i "$execution_pattern" scripts --glob '!test-enterprise-infrastructure-contract.sh'; then
  fail 'an active script still executes enterprise Terraform'
fi

echo 'PASS: Stage/Prod enterprise infrastructure requirements, centralized Terraform ownership, profile model, and local/DEV independence are consistent.'
