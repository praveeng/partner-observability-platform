#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/lib/target-partner-service.sh
source "$repo_root/scripts/lib/target-partner-service.sh"

service_arg=""
while (( $# > 0 )); do
  case "$1" in
    --service)
      (( $# >= 2 )) || { echo 'TARGET_SERVICE_ERROR: --service requires a value' >&2; exit 2; }
      service_arg="$2"
      shift 2
      ;;
    --help)
      echo 'Usage: TARGET_PARTNER_SERVICE=sure-nbfc-name [SUREWEBSERVICES_ROOT=/path] ./test/integration/run-local-service-end-to-end.sh [--service sure-nbfc-name]'
      exit 0
      ;;
    *)
      printf 'TARGET_SERVICE_ERROR: unsupported argument: %s\n' "$1" >&2
      exit 2
      ;;
  esac
done

for command_name in bash chmod date find git grep head jq java mkdir mktemp python3 sed tr; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'TARGET_SERVICE_ERROR: missing prerequisite: %s\n' "$command_name" >&2
    exit 1
  }
done
java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
[[ "$java_major" == "17" ]] || { echo 'TARGET_SERVICE_ERROR: Java 17 is required' >&2; exit 1; }

resolve_target_partner_service "$repo_root" "$service_arg"
export TARGET_PARTNER_SERVICE="$RESOLVED_TARGET_PARTNER_SERVICE"
export SUREWEBSERVICES_ROOT="$RESOLVED_SUREWEBSERVICES_ROOT"

for command_name in curl docker rg; do
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'TARGET_SERVICE_ERROR: missing local E2E prerequisite: %s\n' "$command_name" >&2
    exit 1
  }
done
docker compose version >/dev/null 2>&1 || {
  echo 'TARGET_SERVICE_ERROR: Docker Compose v2 is required for the local platform' >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo 'TARGET_SERVICE_ERROR: Docker daemon is unavailable for the local platform' >&2
  exit 1
}

"$repo_root/scripts/prepare-target-service-test-fixtures.sh"

contract="$repo_root/test/partner-contracts/mappings/$TARGET_PARTNER_SERVICE/local-integration.json"
[[ -f "$contract" ]] || {
  printf 'TARGET_SERVICE_NOT_READY: reviewed local integration contract is missing: %s\n' "$contract" >&2
  exit 4
}

contract_error="$(jq -r --arg service "$TARGET_PARTNER_SERVICE" '
  if .schemaVersion != 1 then "schemaVersion must be 1"
  elif .service != $service then "service must equal the exact selected target"
  elif (.springApplicationRoot | type) != "string" then "springApplicationRoot is required"
  elif (.gradle.wrapper | type) != "string" then "gradle.wrapper is required"
  elif (.gradle.buildTasks | type) != "array" or (.gradle.buildTasks | length) == 0 then "gradle.buildTasks is required"
  elif (.gradle.dependencyTasks | type) != "array" or (.gradle.dependencyTasks | length) == 0 then "gradle.dependencyTasks is required"
  elif (.localRoute.partnerKey | type) != "string" then "localRoute.partnerKey is required"
  elif (.localRoute.sdkUsername | type) != "string" then "localRoute.sdkUsername is required"
  elif (.adapterScript | type) != "string" then "adapterScript is required"
  else "" end
' "$contract")"
[[ -z "$contract_error" ]] || { printf 'TARGET_SERVICE_ERROR: %s\n' "$contract_error" >&2; exit 2; }

safe_relative() {
  local value="$1"
  [[ -n "$value" && "$value" != /* && "$value" != *'..'* && "$value" != *'*'* && "$value" != *'?'* ]]
}
spring_root="$(jq -r '.springApplicationRoot' "$contract")"
gradle_wrapper="$(jq -r '.gradle.wrapper' "$contract")"
adapter_script="$(jq -r '.adapterScript' "$contract")"
local_partner_key="$(jq -r '.localRoute.partnerKey' "$contract")"
local_sdk_username="$(jq -r '.localRoute.sdkUsername' "$contract")"
for relative in "$spring_root" "$gradle_wrapper" "$adapter_script"; do
  safe_relative "$relative" || { echo 'TARGET_SERVICE_ERROR: contract paths must be safe relative paths' >&2; exit 2; }
done

application_resources="$RESOLVED_TARGET_PARTNER_SERVICE_PATH/$spring_root/src/main/resources"
for name in application.properties application-local.properties application-dev.properties application-stage.properties application-prod.properties; do
  [[ -f "$application_resources/$name" ]] || {
    printf 'TARGET_SERVICE_NOT_READY: canonical Spring properties file is missing: %s\n' "$application_resources/$name" >&2
    exit 4
  }
done
if find "$application_resources" -maxdepth 1 -type f \( -name 'application.yml' -o -name 'application.yaml' -o -name 'application-*.yml' -o -name 'application-*.yaml' \) -print -quit | grep -q .; then
  echo 'TARGET_SERVICE_NOT_READY: Spring application YAML is prohibited' >&2
  exit 4
fi

wrapper="$RESOLVED_TARGET_PARTNER_SERVICE_PATH/$gradle_wrapper"
adapter="$RESOLVED_TARGET_PARTNER_SERVICE_PATH/$adapter_script"
[[ -x "$wrapper" ]] || { echo 'TARGET_SERVICE_NOT_READY: selected Gradle wrapper is not executable' >&2; exit 4; }
[[ -x "$adapter" ]] || { echo 'TARGET_SERVICE_NOT_READY: selected local E2E adapter is not executable' >&2; exit 4; }
[[ "$local_partner_key" =~ ^[a-z0-9][a-z0-9._-]{0,127}$ ]] || { echo 'TARGET_SERVICE_ERROR: invalid localRoute.partnerKey' >&2; exit 2; }
[[ "$local_sdk_username" =~ ^[a-z0-9][a-z0-9._-]{0,127}$ ]] || { echo 'TARGET_SERVICE_ERROR: invalid localRoute.sdkUsername' >&2; exit 2; }

mapfile -t build_tasks < <(jq -r '.gradle.buildTasks[]' "$contract")
mapfile -t dependency_tasks < <(jq -r '.gradle.dependencyTasks[]' "$contract")
for task in "${build_tasks[@]}" "${dependency_tasks[@]}"; do
  [[ "$task" =~ ^[-A-Za-z0-9_:.,=]+$ ]] || { echo 'TARGET_SERVICE_ERROR: unsafe Gradle task/option token' >&2; exit 2; }
done

evidence_dir="$repo_root/test/partner-contracts/evidence/${TARGET_SERVICE_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$TARGET_PARTNER_SERVICE}"
mkdir -p "$evidence_dir"
dependency_output="$evidence_dir/gradle-dependencies.txt"
runtime_dir="$(mktemp -d /tmp/partner-target-runtime.XXXXXX)"
cleanup() { rm -rf "$runtime_dir"; }
trap cleanup EXIT
gateway_config="$runtime_dir/local-target-gateway.conf"
gateway_password_file="$runtime_dir/local-target-gateway.htpasswd"
local_sdk_password="$(tr -d '-' </proc/sys/kernel/random/uuid)"
TARGET_LOCAL_PARTNER_KEY="$local_partner_key" \
TARGET_LOCAL_SDK_USERNAME="$local_sdk_username" \
  "$repo_root/scripts/render-target-local-gateway.sh" "$gateway_config"
printf '%s:{PLAIN}%s\n' "$local_sdk_username" "$local_sdk_password" >"$gateway_password_file"
chmod 0600 "$gateway_password_file"

printf '[TARGET BUILD] %s through composite source dependency\n' "$TARGET_PARTNER_SERVICE"
(cd "$RESOLVED_TARGET_PARTNER_SERVICE_PATH" && \
  "$wrapper" --no-daemon --include-build "$repo_root" "${dependency_tasks[@]}") >"$dependency_output"
if ! grep -q 'sure-partner-observability-spring-boot-starter' "$dependency_output"; then
  echo 'TARGET_SERVICE_NOT_READY: runtime dependency evidence does not contain sure-partner-observability-spring-boot-starter' >&2
  exit 4
fi
(cd "$RESOLVED_TARGET_PARTNER_SERVICE_PATH" && \
  "$wrapper" --no-daemon --include-build "$repo_root" "${build_tasks[@]}")

result_file="$evidence_dir/result.json"
printf '[TARGET E2E] exact service=%s profile=local\n' "$TARGET_PARTNER_SERVICE"
SPRING_PROFILES_ACTIVE=local \
TARGET_PARTNER_SERVICE="$TARGET_PARTNER_SERVICE" \
SUREWEBSERVICES_ROOT="$SUREWEBSERVICES_ROOT" \
PARTNER_OBSERVABILITY_PLATFORM_ROOT="$repo_root" \
PARTNER_OBSERVABILITY_COMPOSE_FILE="$repo_root/docker/compose.yml" \
PARTNER_OBSERVABILITY_CONTRACT_DIR="$repo_root/test/partner-contracts/generated/$TARGET_PARTNER_SERVICE" \
PARTNER_OBSERVABILITY_RESULT_FILE="$result_file" \
LOCAL_GATEWAY_CONFIG_FILE="$gateway_config" \
LOCAL_GATEWAY_HTPASSWD_FILE="$gateway_password_file" \
LOCAL_TARGET_PARTNER_KEY="$local_partner_key" \
LOCAL_TARGET_SDK_USERNAME="$local_sdk_username" \
LOCAL_TARGET_SDK_PASSWORD="$local_sdk_password" \
"$adapter"

[[ -f "$result_file" ]] || { echo 'TARGET_SERVICE_NOT_READY: target adapter did not produce result.json' >&2; exit 4; }
jq -e --arg service "$TARGET_PARTNER_SERVICE" '
  .schemaVersion == 1 and
  .service == $service and
  .springProfile == "local" and
  .targetOnly == true and
  .otherServicesTouched == [] and
  .checks.build == true and
  .checks.localProfileStartup == true and
  .checks.mockPartner == true and
  .checks.outboundJourneys == true and
  .checks.callbacks == true and
  .checks.telemetry == true and
  .checks.metrics == true and
  .checks.grafana == true and
  .checks.queryAuthorization == true and
  .checks.piiMasking == true and
  .checks.binaryOmission == true and
  .checks.noExternalPartnerTraffic == true
' "$result_file" >/dev/null || {
  echo 'TARGET_SERVICE_NOT_READY: target adapter result is incomplete or failed' >&2
  exit 4
}

echo "PASS: selected real-service local E2E: $TARGET_PARTNER_SERVICE"
