#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

for command_name in git jq rg; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "MISSING prerequisite: required Terraform validation command is unavailable: $command_name" >&2
    exit 1
  }
done

terraform_bin="${TERRAFORM_BIN:-$(command -v terraform || true)}"
if [[ -z "$terraform_bin" ]]; then
  echo "MISSING prerequisite: Terraform CLI 1.11.4 is required for M8 validation." >&2
  exit 1
fi

readonly required_terraform_version="1.11.4"
readonly required_aws_provider_version="6.61.0"
terraform_version="$("$terraform_bin" version -json 2>/dev/null | jq -r '.terraform_version // empty')"
if [[ "$terraform_version" != "$required_terraform_version" ]]; then
  echo "INVALID prerequisite: Terraform $required_terraform_version is required; found ${terraform_version:-unknown}." >&2
  exit 1
fi

plugin_cache="${TF_PLUGIN_CACHE_DIR:-/tmp/partner-observability-terraform-plugin-cache}"
mkdir -p "$plugin_cache"
export TF_PLUGIN_CACHE_DIR="$plugin_cache"
export AWS_EC2_METADATA_DISABLED=true
export AWS_SDK_LOAD_CONFIG=0

"$terraform_bin" fmt -check -recursive terraform
./test/terraform/static-policy.sh

validation_roots=(
  terraform/examples/dev
  terraform/examples/stage
  terraform/examples/prod
  terraform/examples/shared
  terraform/modules/ecs-alloy-ingest
  terraform/modules/ecs-grafana
  terraform/modules/ecs-loki
  terraform/modules/ecs-prometheus
  terraform/modules/ecs-query-gateway
  terraform/modules/loki-storage
  terraform/modules/market-observability-stack
  terraform/modules/observability-alerts
  terraform/modules/observability-identity
  terraform/modules/observability-network
)

validation_data_root="$(mktemp -d /tmp/partner-observability-tf-validation.XXXXXX)"
validation_tree="$validation_data_root/worktree"
network_data=""
cleanup() {
  rm -rf "$validation_data_root"
  if [[ -n "$network_data" ]]; then
    rm -rf "$network_data"
  fi
}
trap cleanup EXIT

# Validate a tracked-file snapshot so ignored .terraform directories and lock
# files left by earlier local runs cannot affect provider selection or results.
while IFS= read -r -d '' tracked_file; do
  target_file="$validation_tree/$tracked_file"
  mkdir -p "$(dirname "$target_file")"
  cp -- "$tracked_file" "$target_file"
done < <(git ls-files -z --cached --others --exclude-standard terraform)

for validation_root in "${validation_roots[@]}"; do
  validation_name="${validation_root//\//-}"
  validation_data="$validation_data_root/$validation_name"
  mkdir -p "$validation_data"
  echo "Validating $validation_root"
  snapshot_root="$validation_tree/$validation_root"
  TF_DATA_DIR="$validation_data" "$terraform_bin" -chdir="$snapshot_root" init -backend=false -input=false -no-color >/dev/null
  rg -q "version[[:space:]]*=[[:space:]]*\"${required_aws_provider_version//./\\.}\"" \
    "$snapshot_root/.terraform.lock.hcl" || {
      echo "ERROR: $validation_root did not resolve approved AWS provider $required_aws_provider_version." >&2
      exit 1
    }
  TF_DATA_DIR="$validation_data" "$terraform_bin" -chdir="$snapshot_root" validate -no-color
done

network_data="$(mktemp -d /tmp/partner-observability-tf-network.XXXXXX)"
network_root="$validation_tree/terraform/modules/observability-network"
TF_DATA_DIR="$network_data" "$terraform_bin" -chdir="$network_root" init -backend=false -input=false
TF_DATA_DIR="$network_data" "$terraform_bin" -chdir="$network_root" test

echo "PASS: M8 Terraform format, all-root provider-schema validation, and mocked local plan policy tests."
