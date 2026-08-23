#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

terraform_bin="${TERRAFORM_BIN:-$(command -v terraform || true)}"
if [[ -z "$terraform_bin" ]]; then
  echo "NOT IMPLEMENTED: Terraform CLI is required for M8 validation." >&2
  exit 2
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
network_data=""
cleanup() {
  rm -rf "$validation_data_root"
  if [[ -n "$network_data" ]]; then
    rm -rf "$network_data"
  fi
}
trap cleanup EXIT
for validation_root in "${validation_roots[@]}"; do
  validation_name="${validation_root//\//-}"
  validation_data="$validation_data_root/$validation_name"
  mkdir -p "$validation_data"
  echo "Validating $validation_root"
  TF_DATA_DIR="$validation_data" "$terraform_bin" -chdir="$validation_root" init -backend=false -input=false -no-color >/dev/null
  TF_DATA_DIR="$validation_data" "$terraform_bin" -chdir="$validation_root" validate -no-color
done

network_data="$(mktemp -d /tmp/partner-observability-tf-network.XXXXXX)"
TF_DATA_DIR="$network_data" "$terraform_bin" -chdir=terraform/modules/observability-network init -backend=false -input=false
TF_DATA_DIR="$network_data" "$terraform_bin" -chdir=terraform/modules/observability-network test

echo "PASS: M8 Terraform format, all-root provider-schema validation, and mocked local plan policy tests."
