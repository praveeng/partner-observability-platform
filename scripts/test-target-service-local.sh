#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/lib/target-partner-service.sh
source "$repo_root/scripts/lib/target-partner-service.sh"

for path in \
  "$repo_root/scripts/lib/target-partner-service.sh" \
  "$repo_root/scripts/prepare-target-service-test-fixtures.sh" \
  "$repo_root/scripts/render-target-local-gateway.sh" \
  "$repo_root/scripts/test-target-service-local.sh" \
  "$repo_root/test/integration/run-local-service-end-to-end.sh"; do
  bash -n "$path"
done

tmp_dir="$(mktemp -d /tmp/partner-target-resolution.XXXXXX)"
cleanup() { rm -rf "$tmp_dir"; }
trap cleanup EXIT
mkdir -p "$tmp_dir/SureWebServices/sure-partner-observability"
mkdir -p "$tmp_dir/SureWebServices/sure-nbfc-selected"
mkdir -p "$tmp_dir/SureWebServices/sure-nbfc-foreign"
mkdir -p "$tmp_dir/outside/sure-nbfc-linked"
ln -s "$tmp_dir/outside/sure-nbfc-linked" "$tmp_dir/SureWebServices/sure-nbfc-linked"

SUREWEBSERVICES_ROOT="$tmp_dir/SureWebServices"
TARGET_PARTNER_SERVICE="sure-nbfc-selected"
export SUREWEBSERVICES_ROOT TARGET_PARTNER_SERVICE
resolve_target_partner_service "$tmp_dir/SureWebServices/sure-partner-observability"
[[ "$RESOLVED_TARGET_PARTNER_SERVICE" == "sure-nbfc-selected" ]]
[[ "$RESOLVED_TARGET_PARTNER_SERVICE_PATH" == "$tmp_dir/SureWebServices/sure-nbfc-selected" ]]

if resolve_target_partner_service "$tmp_dir/SureWebServices/sure-partner-observability" "sure-nbfc-foreign" 2>/dev/null; then
  echo 'FAIL: conflicting env/CLI target was accepted' >&2
  exit 1
fi
for invalid in 'sure-nbfc-*' '../sure-nbfc-selected' 'sure-nbfc-selected/sibling' 'sure-partner-observability'; do
  TARGET_PARTNER_SERVICE="$invalid"
  export TARGET_PARTNER_SERVICE
  if resolve_target_partner_service "$tmp_dir/SureWebServices/sure-partner-observability" 2>/dev/null; then
    printf 'FAIL: unsafe target was accepted: %s\n' "$invalid" >&2
    exit 1
  fi
done
TARGET_PARTNER_SERVICE=sure-nbfc-linked
export TARGET_PARTNER_SERVICE
if resolve_target_partner_service "$tmp_dir/SureWebServices/sure-partner-observability" 2>/dev/null; then
  echo 'FAIL: target symlink escaping the workspace root was accepted' >&2
  exit 1
fi

TARGET_PARTNER_SERVICE=sure-nbfc-missing \
SUREWEBSERVICES_ROOT="$tmp_dir/SureWebServices" \
  "$repo_root/test/integration/run-local-service-end-to-end.sh" \
  >"$tmp_dir/missing.stdout" 2>"$tmp_dir/missing.stderr" && {
    echo 'FAIL: missing selected service did not fail' >&2
    exit 1
  }
grep -F 'exact target does not exist' "$tmp_dir/missing.stderr" >/dev/null

if python3 -c 'import yaml' >/dev/null 2>&1; then
  python3 "$repo_root/test/partner-contracts/tools/test_openapi_fixture_generator.py"
else
  echo 'INFO: PyYAML is absent; target-only generator execution is deferred until TARGET_SERVICE mode.'
fi

rendered="$tmp_dir/rendered-gateway.conf"
TARGET_LOCAL_PARTNER_KEY=partner-selected-fixture \
TARGET_LOCAL_SDK_USERNAME=sdk-selected \
  "$repo_root/scripts/render-target-local-gateway.sh" "$rendered"
grep -F '"sdk-selected:partner-selected-fixture" "alloy:4318";' "$rendered" >/dev/null
if grep -F 'sure-nbfc-foreign' "$rendered" >/dev/null; then
  echo 'FAIL: gateway renderer incorporated an unselected service' >&2
  exit 1
fi
echo 'PASS: explicit target resolution and target-only OpenAPI fixture generation'
