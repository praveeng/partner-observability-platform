#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/lib/target-partner-service.sh
source "$repo_root/scripts/lib/target-partner-service.sh"

service_arg=""
readonly output_root="$repo_root/test/partner-contracts/generated"
while (( $# > 0 )); do
  case "$1" in
    --service)
      (( $# >= 2 )) || { echo 'TARGET_SERVICE_ERROR: --service requires a value' >&2; exit 2; }
      service_arg="$2"
      shift 2
      ;;
    --help)
      echo 'Usage: TARGET_PARTNER_SERVICE=sure-nbfc-name [SUREWEBSERVICES_ROOT=/path] ./scripts/prepare-target-service-test-fixtures.sh [--service sure-nbfc-name]'
      exit 0
      ;;
    *)
      printf 'TARGET_SERVICE_ERROR: unsupported argument: %s\n' "$1" >&2
      exit 2
      ;;
  esac
done

for command_name in bash python3; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'TARGET_SERVICE_ERROR: missing prerequisite: %s\n' "$command_name" >&2
    exit 1
  }
done
python3 -c 'import yaml' >/dev/null 2>&1 || {
  echo 'TARGET_SERVICE_ERROR: Python package PyYAML is required for target-only OpenAPI parsing' >&2
  exit 1
}

resolve_target_partner_service "$repo_root" "$service_arg"
mapping_file="$repo_root/test/partner-contracts/mappings/$RESOLVED_TARGET_PARTNER_SERVICE/coverage.json"
args=(
  "$repo_root/test/partner-contracts/tools/openapi_fixture_generator.py"
  --service-name "$RESOLVED_TARGET_PARTNER_SERVICE"
  --service-root "$RESOLVED_TARGET_PARTNER_SERVICE_PATH"
  --output-root "$output_root"
  --capabilities "$repo_root/test/partner-contracts/generic-capabilities.json"
)
if [[ -f "$mapping_file" ]]; then
  args+=(--mapping "$mapping_file")
fi

printf 'TARGET_SERVICE=%s\nTARGET_SERVICE_PATH=%s\n' \
  "$RESOLVED_TARGET_PARTNER_SERVICE" "$RESOLVED_TARGET_PARTNER_SERVICE_PATH"
python3 "${args[@]}"
