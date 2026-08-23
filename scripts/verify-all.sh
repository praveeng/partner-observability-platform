#!/usr/bin/env bash
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

export LC_ALL=C
export TZ=UTC
export CI=true
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/partner-observability-gradle-cache}"

readonly REQUIRED_TERRAFORM_VERSION="1.11.4"
readonly REQUIRED_GRADLE_VERSION="7.6.4"

declare -a stage_names=()
declare -a stage_results=()
failed_stages=0
stage_number=0

section() {
  printf '\n========== %s ==========\n' "$1"
}

run_stage() {
  local name="$1"
  shift
  stage_number=$((stage_number + 1))
  printf '\n[%02d] %s\n' "$stage_number" "$name"
  printf '%s\n' '----------------------------------------'
  if "$@"; then
    printf 'PASS: %s\n' "$name"
    stage_names+=("$name")
    stage_results+=("PASS")
  else
    local result=$?
    printf 'FAIL: %s (exit %d)\n' "$name" "$result" >&2
    stage_names+=("$name")
    stage_results+=("FAIL ($result)")
    failed_stages=$((failed_stages + 1))
  fi
}

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    printf 'MISSING prerequisite: %s\n' "$command_name" >&2
    return 1
  fi
  printf 'FOUND prerequisite: %s\n' "$command_name"
}

check_prerequisites() {
  local result=0
  local command_name
  for command_name in bash curl docker git java jq rg; do
    require_command "$command_name" || result=1
  done

  local terraform_bin terraform_version
  terraform_bin="${TERRAFORM_BIN:-$(command -v terraform || true)}"
  if [[ -z "$terraform_bin" || ! -x "$terraform_bin" ]]; then
    printf 'MISSING prerequisite: Terraform CLI %s (set TERRAFORM_BIN to the approved executable when it is not on PATH)\n' \
      "$REQUIRED_TERRAFORM_VERSION" >&2
    result=1
  else
    printf 'FOUND prerequisite: Terraform CLI at %s\n' "$terraform_bin"
    terraform_version="$("$terraform_bin" version -json 2>/dev/null | jq -r '.terraform_version // empty')"
    if [[ "$terraform_version" != "$REQUIRED_TERRAFORM_VERSION" ]]; then
      printf 'INVALID prerequisite: Terraform %s is required; found %s\n' \
        "$REQUIRED_TERRAFORM_VERSION" "${terraform_version:-unknown}" >&2
      result=1
    else
      printf 'FOUND prerequisite: Terraform %s\n' "$terraform_version"
    fi
    export TERRAFORM_BIN="$terraform_bin"
  fi

  if [[ ! -x ./gradlew ]]; then
    echo 'MISSING prerequisite: executable Gradle wrapper ./gradlew' >&2
    result=1
  else
    echo 'FOUND prerequisite: executable Gradle wrapper ./gradlew'
    local wrapper_distribution
    wrapper_distribution="$(sed -n 's#^distributionUrl=.*gradle-\([0-9][0-9.]*\)-bin\.zip$#\1#p' gradle/wrapper/gradle-wrapper.properties)"
    if [[ "$wrapper_distribution" != "$REQUIRED_GRADLE_VERSION" ]]; then
      printf 'INVALID prerequisite: Gradle wrapper %s is required; configured %s\n' \
        "$REQUIRED_GRADLE_VERSION" "${wrapper_distribution:-unknown}" >&2
      result=1
    else
      printf 'FOUND prerequisite: Gradle wrapper %s\n' "$wrapper_distribution"
    fi
  fi

  if command -v java >/dev/null 2>&1; then
    local java_version
    java_version="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java\.specification\.version = //p' | head -n 1)"
    if [[ "$java_version" != "17" ]]; then
      printf 'INVALID prerequisite: Java 17 is required; found %s\n' "${java_version:-unknown}" >&2
      result=1
    else
      echo 'FOUND prerequisite: Java 17'
    fi
  fi

  if command -v docker >/dev/null 2>&1; then
    docker compose version >/dev/null 2>&1 || {
      echo 'MISSING prerequisite: Docker Compose v2 plugin' >&2
      result=1
    }
    local compose_version
    compose_version="$(docker compose version --short 2>/dev/null || true)"
    if [[ "$compose_version" != 2.* ]]; then
      printf 'INVALID prerequisite: Docker Compose v2 is required; found %s\n' \
        "${compose_version:-unknown}" >&2
      result=1
    else
      printf 'FOUND prerequisite: Docker Compose %s\n' "$compose_version"
    fi
    docker info >/dev/null 2>&1 || {
      echo 'UNAVAILABLE prerequisite: Docker daemon is not reachable by the current user.' >&2
      result=1
    }
  fi

  if (( result != 0 )); then
    echo 'Install or start every prerequisite above, then rerun ./scripts/verify-all.sh.' >&2
  fi
  return "$result"
}

repository_baseline() {
  git diff --check || return $?
  git diff --cached --check || return $?
  local worktree_status
  worktree_status="$(git status --porcelain --untracked-files=all)" || return $?
  if [[ -n "$worktree_status" ]]; then
    echo 'INFO: worktree changes are present; they are treated as intentional verification inputs.'
  else
    echo 'INFO: worktree is clean.'
  fi
  mkdir -p "$GRADLE_USER_HOME"
  echo 'INFO: Gradle clean, a task-specific Gradle cache, unique Compose projects, disposable volumes, and tracked-file Terraform snapshots isolate generated state.'
}

gradle_test() {
  ./gradlew --no-daemon --rerun-tasks "$@"
}

failure_isolation_tests() {
  gradle_test :partner-observability-core:test \
    --tests '*BoundedAsyncDispatcherTest'
  gradle_test :partner-observability-test-app:test \
    --tests '*PartnerObservabilityStarterIntegrationTest.publisherFailureAndQueueSaturationNeverFailBusinessCalls' \
    --tests '*EncryptedRestTemplateFixtureIntegrationTest.publisherFailureAndHookFailureDoNotAffectEncryptedBusinessTraffic'
}

outbound_client_tests() {
  gradle_test :partner-observability-test-app:test \
    --tests '*SyntheticPartnerClientsIntegrationTest' \
    --tests '*PartnerObservabilityStarterIntegrationTest.capturesAllThreeOutboundClientsWithoutChangingTheirResults'
  gradle_test :partner-observability-spring-boot-autoconfigure:test \
    --tests '*TlsInstrumentationIntegrationTest'
}

encrypted_integration_tests() {
  gradle_test :partner-observability-test-app:test \
    --tests '*EncryptedRestTemplateFixtureIntegrationTest'
  gradle_test :partner-observability-spring-boot-autoconfigure:test \
    --tests '*PartnerPlaintextSchemaTest'
}

callback_async_tests() {
  gradle_test :partner-observability-test-app:test \
    --tests '*SyntheticAsyncLifecycleIntegrationTest' \
    --tests '*PartnerObservabilityStarterIntegrationTest'
  gradle_test :partner-observability-spring-boot-autoconfigure:test \
    --tests '*PartnerCallbackWebFilterTest'
}

payload_safety_tests() {
  gradle_test :partner-observability-core:test \
    --tests '*PayloadSafetyTest' \
    --tests '*ApplicationPayloadSafetyTest'
  gradle_test :partner-observability-test-app:test \
    --tests '*SyntheticPayloadFixturesTest' \
    --tests '*EncryptedRestTemplateFixtureIntegrationTest.removesCryptoSecretsAndExcludesLargeBase64BeforeQueueAdmission'
}

print_summary() {
  section 'SUMMARY'
  local index
  for index in "${!stage_names[@]}"; do
    printf '%-8s %s\n' "${stage_results[$index]}" "${stage_names[$index]}"
  done
  if (( failed_stages == 0 )); then
    printf '\nFINAL RESULT: PASS (%d stages)\n' "${#stage_names[@]}"
  else
    # Keep the complete summary on one stream so captured CI/local output
    # cannot reorder the final verdict ahead of individual stage rows.
    printf '\nFINAL RESULT: FAIL (%d of %d stages failed)\n' "$failed_stages" "${#stage_names[@]}"
  fi
}

trap print_summary EXIT

section 'PREFLIGHT'
run_stage 'Prerequisites (Java 17, Gradle, Docker Compose, Terraform, CLI tools)' check_prerequisites
if (( failed_stages != 0 )); then
  exit 1
fi
run_stage 'Repository input and generated-state baseline' repository_baseline

section 'BUILD / CORE'
run_stage '1. Gradle clean build' ./gradlew --no-daemon clean build
run_stage '2. Core unit tests' gradle_test :partner-observability-core:test
run_stage '3. Spring Boot starter and auto-configuration tests' gradle_test \
  :partner-observability-spring-boot-autoconfigure:test \
  :partner-observability-test-app:test
run_stage '4. Bounded queue count, byte, concurrency, and saturation tests' gradle_test \
  :partner-observability-core:test \
  --tests '*BoundedTelemetryQueueTest' \
  --tests '*BoundedAsyncDispatcherTest'
run_stage '5. Telemetry publisher and observation failure isolation tests' failure_isolation_tests

section 'OUTBOUND CLIENTS'
run_stage '6-8. RestTemplate, WebClient, and OkHttp integration tests' outbound_client_tests
run_stage '9. Encrypted integration and plaintext-boundary tests' encrypted_integration_tests

section 'CALLBACKS / ASYNC'
run_stage '10-24. Async acknowledgement, callback lifecycle, correlation, isolation, authentication, retry, failure, Base64, and PII tests' callback_async_tests

section 'PAYLOAD / LOG SAFETY'
run_stage '25-26. Payload safety and Base64/document pre-queue exclusion tests' payload_safety_tests
run_stage '27. SLF4J/Logback compatibility and unchanged logging semantics tests' gradle_test \
  :partner-observability-spring-boot-autoconfigure:test \
  --tests '*PartnerSafeLogCompatibilityTest'
run_stage '28-30. Core pre-queue secret, PII, and binary safety evidence' ./scripts/test-security.sh --core

section 'PLATFORM'
run_stage '31-33. Docker Compose startup plus Alloy and Loki health/integration' ./test/integration/run-local-data-plane.sh
run_stage '34. Prometheus health, scrape, remote-write, retention, and rule integration' ./test/integration/run-local-metrics-plane.sh
run_stage '35. Grafana health, provisioning, authentication, and datasource isolation' ./scripts/test-grafana.sh

section 'END TO END'
run_stage '36-46. Application-to-platform journeys, search, visibility, metrics, and tenant isolation' ./scripts/test-end-to-end.sh
run_stage 'Security completion gate (all disclosure and isolation attacks)' ./scripts/test-security.sh
run_stage 'Performance completion gate (all acceptance profiles)' ./scripts/test-performance.sh

section 'INFRA / CONFIG'
run_stage '47. Terraform format, validation, static policy, and mocked plan tests' ./scripts/test-terraform.sh
run_stage '48. Dashboard and provisioning validation' ./scripts/test-grafana.sh --validate-only
run_stage '49. Documentation and configuration consistency checks' ./scripts/test-docs.sh

if (( failed_stages != 0 )); then
  exit 1
fi

exit 0
