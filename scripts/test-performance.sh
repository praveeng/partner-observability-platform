#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

readonly manifest="test/performance/profiles.json"
readonly compose_base="docker/compose.yml"
readonly compose_perf="test/performance/compose.performance.yml"
readonly k6_image="grafana/k6:0.49.0@sha256:8cd78f9d0de5f50bc8821cceecf356d5d9e839e6611c226a3fcf13c591080fbd"
mode="${PERF_MODE:-full}"
case "$mode" in full|smoke) ;; *) echo 'FAIL: PERF_MODE must be full or smoke.' >&2; exit 1 ;; esac
if [[ "$mode" == "full" ]]; then echo 'FULL PERFORMANCE MODE'; else echo 'SMOKE MODE — NOT RELEASE EVIDENCE'; fi

for required in awk bash curl date docker git java jq python3 rg sha256sum; do
  command -v "$required" >/dev/null 2>&1 || { echo "FAIL prerequisite: missing $required" >&2; exit 1; }
done
[[ -x ./gradlew ]] || { echo 'FAIL prerequisite: ./gradlew is not executable.' >&2; exit 1; }
[[ -f "$manifest" ]] || { echo "FAIL prerequisite: missing $manifest" >&2; exit 1; }
docker compose version >/dev/null
docker info >/dev/null
docker image inspect "$k6_image" >/dev/null 2>&1 || {
  echo "FAIL prerequisite: pinned K6 image is unavailable; run: docker pull grafana/k6:0.49.0" >&2
  exit 1
}
docker run --rm "$k6_image" version >/dev/null
java_major="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java\.specification\.version = //p' | head -n 1)"
[[ "$java_major" == "17" ]] || { echo "FAIL prerequisite: Java 17 required; found ${java_major:-unknown}." >&2; exit 1; }
[[ "${SPRING_PROFILES_ACTIVE:-local}" == "local" ]] || {
  echo 'FAIL configuration: B003 must run only with SPRING_PROFILES_ACTIVE=local.' >&2
  exit 1
}
export SPRING_PROFILES_ACTIVE=local
./scripts/validate-performance-profiles.sh

host_cpus="$(nproc)"
host_memory="$(awk '/MemAvailable/ {printf "%.0f", $2 * 1024}' /proc/meminfo)"
docker_cpus="$(docker info --format '{{.NCPU}}')"
docker_memory="$(docker info --format '{{.MemTotal}}')"
if [[ "$mode" == "full" ]] && {
  (( host_cpus < 8 )) || (( host_memory < 12884901888 )) ||
  (( docker_cpus < 8 )) || (( docker_memory < 12884901888 ));
}; then
  printf 'BLOCKED_INSUFFICIENT_LOCAL_RESOURCES: host=%s CPUs/%s bytes, Docker=%s CPUs/%s bytes; require 8 CPUs and 12 GiB.\n' \
    "$host_cpus" "$host_memory" "$docker_cpus" "$docker_memory" >&2
  exit 2
fi

run_id="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short=12 HEAD)-$$}"
[[ "$run_id" =~ ^[A-Za-z0-9._-]+$ ]] || { echo 'FAIL: RUN_ID must be an opaque filename-safe token.' >&2; exit 1; }
raw_root="$repo_root/test/performance/evidence/$run_id"
mkdir -p "$raw_root"
chmod 0700 "$raw_root"
bootstrap_provisioning="$raw_root/.bootstrap-provisioning"
mkdir -p "$bootstrap_provisioning"/{alerting,dashboards,datasources,plugins}
provisioning_dir="$bootstrap_provisioning"
grafana_partner_a_org_id=2
grafana_partner_b_org_id=3
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
git_commit="$(git rev-parse HEAD)"
workload_hash="$(sha256sum "$manifest" test/performance/k6/common.js test/performance/k6/profile.js | sha256sum | awk '{print $1}')"
project_name="sure-observability-b003-${PPID}-$$"
port_base=$((34000 + ($$ % 500) * 8))
app_port="$port_base"; reactive_port=$((port_base + 1)); grafana_port=$((port_base + 2))
otlp_port=$((port_base + 3)); query_port=$((port_base + 4)); prometheus_port=$((port_base + 5)); alloy_port=$((port_base + 6))
test_app_url="http://127.0.0.1:$app_port"; reactive_url="http://127.0.0.1:$reactive_port"
failed=0; stack_started=0; sdk_enabled=true; payloads_enabled=true; capture_mode=FULL_SANITIZED; jfr_prefix=initial
reactive_element_delay=625ms
[[ "$mode" == smoke ]] && reactive_element_delay=25ms

compose() {
  PERF_EVIDENCE_DIR="$raw_root" PERF_SDK_ENABLED="$sdk_enabled" PERF_PAYLOADS_ENABLED="$payloads_enabled" \
  PERF_CAPTURE_MODE="$capture_mode" PERF_JFR_PREFIX="$jfr_prefix" PERF_REACTIVE_ELEMENT_DELAY="$reactive_element_delay" \
  LOCAL_TEST_APP_PORT="$app_port" \
  LOCAL_REACTIVE_TEST_APP_PORT="$reactive_port" LOCAL_TEST_APP_JAR="$repo_root/sure-partner-observability-test-app/build/libs/sure-partner-observability-test-app-0.1.0-SNAPSHOT.jar" \
  LOCAL_REACTIVE_TEST_APP_JAR="$repo_root/sure-partner-observability-reactive-test-app/build/libs/sure-partner-observability-reactive-test-app-0.1.0-SNAPSHOT.jar" \
  LOCAL_GRAFANA_PORT="$grafana_port" LOCAL_OTLP_PORT="$otlp_port" LOCAL_QUERY_PORT="$query_port" \
  LOCAL_PROMETHEUS_PORT="$prometheus_port" LOCAL_ALLOY_METRICS_PORT="$alloy_port" \
  LOCAL_GATEWAY_HTPASSWD_FILE="$repo_root/docker/nginx/local-synthetic.htpasswd" \
  LOCAL_METRICS_TARGET="test-app:8080" LOCAL_METRICS_SERVICE="partner-observability-test-app" \
  LOCAL_E2E_SDK_A_PASSWORD="local-synthetic-sdk-a" LOCAL_E2E_SDK_B_PASSWORD="local-synthetic-sdk-b" \
  LOCAL_QUERY_A_PASSWORD="local-synthetic-query-a" LOCAL_QUERY_B_PASSWORD="local-synthetic-query-b" \
  GRAFANA_PROVISIONING_DIR="$provisioning_dir" GRAFANA_ADMIN_USER="local-performance-admin" \
  GRAFANA_ADMIN_PASSWORD="local-synthetic-performance-admin" GRAFANA_SECRET_KEY="local-synthetic-performance-secret-key" \
  GRAFANA_PARTNER_A_QUERY_PASSWORD="local-synthetic-query-a" GRAFANA_PARTNER_B_QUERY_PASSWORD="local-synthetic-query-b" \
  GRAFANA_PARTNER_A_ORG_ID="$grafana_partner_a_org_id" GRAFANA_PARTNER_B_ORG_ID="$grafana_partner_b_org_id" \
  docker compose --profile grafana --profile end-to-end --profile performance -p "$project_name" \
    -f "$compose_base" -f "$compose_perf" "$@"
}

cleanup() {
  local status=$?
  if (( failed != 0 || status != 0 )); then
    echo "B003 evidence retained at $raw_root" >&2
    if (( stack_started != 0 )); then compose ps >&2 || true; fi
  fi
  if [[ "${KEEP_RUNNING:-0}" == "1" ]]; then
    printf 'KEEP_RUNNING=1: project=%s test-app=%s reactive=%s evidence=%s\n' "$project_name" "$test_app_url" "$reactive_url" "$raw_root"
  elif (( stack_started != 0 )); then compose down -v --remove-orphans >/dev/null 2>&1 || true; fi
  return "$status"
}
trap cleanup EXIT

wait_http() {
  local label="$1" url="$2" timeout="$3" deadline
  deadline=$((SECONDS + timeout))
  until curl -fsS "$url" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then echo "FAIL health: $label unavailable after ${timeout}s" >&2; return 1; fi
    sleep 1
  done
}

wait_internal_http() {
  local label="$1" url="$2" timeout="$3" deadline
  deadline=$((SECONDS + timeout))
  until compose exec -T tenant-gateway wget -qO- "$url" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then echo "FAIL health: $label unavailable after ${timeout}s" >&2; return 1; fi
    sleep 1
  done
}

k6() {
  docker run --rm --network host --user "$(id -u):$(id -g)" -v "$repo_root/test/performance:/work:ro" \
    -v "$raw_root:/evidence" "$k6_image" "$@"
}

alloy_metric_sum() {
  local metric="$1" component_pattern="$2"
  curl -fsS "http://127.0.0.1:$alloy_port/metrics" | awk -v metric="$metric" -v component="$component_pattern" '
    index($0, metric "{") == 1 && $0 ~ component { total += $NF }
    END { printf "%.0f", total + 0 }
  '
}

restart_apps() {
  sdk_enabled="$1"; capture_mode="$2"; jfr_prefix="$3"; payloads_enabled=true
  compose up -d --force-recreate --no-deps test-app reactive-test-app >/dev/null
  wait_http test-app "$test_app_url/actuator/health" 90
  wait_http reactive-test-app "$reactive_url/actuator/health" 90
}

reset_fixtures() {
  curl -fsS -X POST "$test_app_url/fixture/performance/reset" >/dev/null
  curl -fsS -X POST -H 'X-Synthetic-Callback-Key: local-synthetic-reactive-callback-key' \
    "$reactive_url/fixture/reactive/metrics/reset" >/dev/null
}

run_k6_phase() {
  local profile="$1" phase="$2" seconds="$3" rate="$4" concurrency="$5" output="$6"
  local measure_start="${7:-0}" measure_duration="${8:-$seconds}"
  local effective_rate="$rate" effective_concurrency="$concurrency"
  if [[ "$mode" == "smoke" ]]; then
    effective_rate=$(( rate > 0 ? (rate < 10 ? rate : 10) : 0 ))
    effective_concurrency=$(( concurrency > 0 ? (concurrency < 10 ? concurrency : 10) : 0 ))
  fi
  k6 run --quiet -e RUN_ID="$run_id" -e PERF_PROFILE="$profile" -e PERF_EXECUTION=composite \
    -e PERF_PHASE="$phase" -e PERF_DURATION_SECONDS="$seconds" -e PERF_ARRIVAL_RATE="$effective_rate" \
    -e PERF_MODE="$mode" \
    -e PERF_MEASURE_START_AFTER_SECONDS="$measure_start" -e PERF_MEASURE_DURATION_SECONDS="$measure_duration" \
    -e PERF_CONCURRENCY="$effective_concurrency" -e TEST_APP_BASE_URL="$test_app_url" \
    -e REACTIVE_TEST_APP_BASE_URL="$reactive_url" -e QUERY_BASE_URL="$test_app_url" \
    -e SEED_RECORD_COUNT="$([[ "$mode" == "full" ]] && echo 500000 || echo 700)" \
    -e K6_SUMMARY_PATH="/evidence/$output" /work/k6/profile.js
}

service_running() { [[ "$(compose ps --status running -q "$1" | wc -l)" -eq 1 ]]; }

restore_service() {
  local service="$1" deadline=$((SECONDS + 120))
  compose start "$service" >/dev/null
  until service_running "$service"; do
    (( SECONDS < deadline )) || { echo "FAIL restore: $service did not restart" >&2; return 1; }
    sleep 1
  done
  case "$service" in
    alloy) wait_internal_http alloy http://alloy:12345/-/ready 120 ;;
    loki) wait_internal_http loki http://loki:3100/ready 120 ;;
    prometheus) wait_internal_http prometheus http://prometheus:9090/-/ready 120 ;;
    grafana) wait_http grafana "http://127.0.0.1:$grafana_port/api/health" 120 ;;
  esac
}

saturation_schedule() {
  local seconds="$1" evidence="$2"
  # Five mapped outage cases share this one approved top-level profile. Reserve one third of the
  # measured window for mandatory health restoration between cases; no case is a separate profile.
  local segment=$((seconds * 2 / 15)) first=1
  (( segment > 0 )) || segment=1
  printf '[' >"$evidence"
  outage_entry() {
    local name="$1" confirmed="$2" restored="$3" before_health="$4" after_health="$5" before_callbacks="$6" after_callbacks="$7"
    local reactive_status="$8"
    (( first == 1 )) || printf ',' >>"$evidence"; first=0
    jq -cn --arg name "$name" --argjson confirmed "$confirmed" --argjson restored "$restored" \
      --argjson beforeHealth "$before_health" --argjson afterHealth "$after_health" \
      --argjson beforeCallbacks "$before_callbacks" --argjson afterCallbacks "$after_callbacks" \
      --argjson reactiveStatus "$reactive_status" \
      '{scenario:$name,unavailableConfirmed:$confirmed,restored:$restored,
        captureAttemptsDelta:($afterHealth.captureAttempts-$beforeHealth.captureAttempts),
        enqueuedDelta:($afterHealth.enqueued-$beforeHealth.enqueued),
        dropsDelta:($afterHealth.totalDrops-$beforeHealth.totalDrops),
        callbacksReceivedDelta:($afterCallbacks.callbacksReceived-$beforeCallbacks.callbacksReceived),
        callbacksProcessedDelta:($afterCallbacks.callbacksProcessed-$beforeCallbacks.callbacksProcessed),
        callbackFailuresDelta:($afterCallbacks.callbackProcessingFailures-$beforeCallbacks.callbackProcessingFailures),
        callbackResponsesDelta:($afterCallbacks.callbackResponsesSent-$beforeCallbacks.callbackResponsesSent),
        reactiveCallbackHttpStatus:$reactiveStatus}' >>"$evidence"
  }
  probe_reactive_callback() {
    local status
    status="$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' -X POST \
      -H 'Content-Type: application/json' \
      -H 'X-Synthetic-Callback-Key: local-synthetic-reactive-callback-key' \
      --data '{"fixtureClassification":"SYNTHETIC_ONLY","marker":"SYNTHETIC-OUTAGE-PROBE"}' \
      "$reactive_url/fixture/reactive/callback/alpha?completion=inline" || true)"
    [[ "$status" =~ ^[0-9]{3}$ ]] && printf '%s' "$status" || printf '0'
  }
  local before_health after_health before_callbacks after_callbacks
  before_health="$(curl -fsS "$test_app_url/fixture/performance/health")"
  before_callbacks="$(curl -fsS "$test_app_url/fixture/performance/callbacks")"
  curl -fsS -X POST "$test_app_url/fixture/performance/publisher/pause" >/dev/null
  sleep "$segment"
  curl -fsS -X POST "$test_app_url/fixture/performance/publisher/release" >/dev/null
  after_health="$(curl -fsS "$test_app_url/fixture/performance/health")"
  after_callbacks="$(curl -fsS "$test_app_url/fixture/performance/callbacks")"
  outage_entry queue-saturation true true "$before_health" "$after_health" "$before_callbacks" "$after_callbacks" \
    "$(probe_reactive_callback)"
  local service sleep_for confirmed
  for service in alloy loki prometheus grafana; do
    # Snapshot after the previous dependency has been restored so each delta is attributable to
    # this outage window rather than recovery traffic from the preceding case.
    before_health="$(curl -fsS "$test_app_url/fixture/performance/health")"
    before_callbacks="$(curl -fsS "$test_app_url/fixture/performance/callbacks")"
    sleep_for="$segment"
    compose stop "$service" >/dev/null; confirmed=false
    if ! service_running "$service"; then confirmed=true; fi
    sleep "$sleep_for"
    local reactive_status
    reactive_status="$(probe_reactive_callback)"
    after_health="$(curl -fsS "$test_app_url/fixture/performance/health")"
    after_callbacks="$(curl -fsS "$test_app_url/fixture/performance/callbacks")"
    restore_service "$service"
    outage_entry "$service-unavailable" "$confirmed" true "$before_health" "$after_health" "$before_callbacks" "$after_callbacks" \
      "$reactive_status"
  done
  printf ']\n' >>"$evidence"
}

capture_json() { curl -fsS "$1" >"$2" || printf '{}\n' >"$2"; }

capture_diagnostics() {
  local output="$1" since="$2" jfr_prefix="$3" log_file="$raw_root/.diagnostic-log" oom=false leak_warnings=0 sensitive=false
  local loki_binary_matches=0 loki_payload_scan_measured=true
  local loki_test_app_records=0 loki_reactive_records=0 loki_service_scan_measured=true
  compose logs --no-color --since "$since" test-app reactive-test-app >"$log_file" 2>/dev/null || true
  rg -q 'OutOfMemoryError|Killed process.*java' "$log_file" && oom=true || true
  leak_warnings="$(rg -c 'LEAK: ByteBuf|DataBuffer.*leak|event loop.*blocked' "$log_file" || true)"; [[ -n "$leak_warnings" ]] || leak_warnings=0
  rg -q 'Authorization: Bearer|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|SYNTHETIC_FORBIDDEN_BASE64_BODY' "$log_file" && sensitive=true || true
  local username password marker response matches
  for username in query-partner-a query-partner-b; do
    [[ "$username" == query-partner-a ]] && password=local-synthetic-query-a || password=local-synthetic-query-b
    for marker in 'JVBERi0xLjcKJVNZTlRIRVRJQy1GSVhUVVJFCg' '/9j/4FNZTlRIRVRJQw'; do
      if ! response="$(curl -fsS -u "$username:$password" -G \
        --data-urlencode "query={service_name=\"partner-observability-test-app\"} |= \"$marker\"" \
        "http://127.0.0.1:$query_port/loki/api/v1/query_range" 2>/dev/null)"; then
        loki_payload_scan_measured=false
        continue
      fi
      if ! matches="$(jq -er '[.data.result[]?.values[]?] | length' <<<"$response" 2>/dev/null)"; then
        loki_payload_scan_measured=false
        continue
      fi
      loki_binary_matches=$((loki_binary_matches + matches))
    done
    local service_name start_nanos end_nanos
    start_nanos="$(date -u -d "$since" +%s)000000000"
    end_nanos="$(date -u +%s)000000000"
    for service_name in partner-observability-test-app partner-observability-reactive-test-app; do
      if ! response="$(curl -fsS -u "$username:$password" -G \
        --data-urlencode "query={service_name=\"$service_name\"}" \
        --data-urlencode "start=$start_nanos" --data-urlencode "end=$end_nanos" \
        --data-urlencode 'limit=10' "http://127.0.0.1:$query_port/loki/api/v1/query_range" 2>/dev/null)"; then
        loki_service_scan_measured=false
        continue
      fi
      if ! matches="$(jq -er '[.data.result[]?.values[]?] | length' <<<"$response" 2>/dev/null)"; then
        loki_service_scan_measured=false
        continue
      fi
      if [[ "$service_name" == partner-observability-test-app ]]; then
        loki_test_app_records=$((loki_test_app_records + matches))
      else
        loki_reactive_records=$((loki_reactive_records + matches))
      fi
    done
  done
  jq -n --argjson oom "$oom" --argjson leaks "$leak_warnings" --argjson sensitive "$sensitive" \
    --argjson lokiBinary "$loki_binary_matches" \
    --argjson lokiPayloadScanMeasured "$loki_payload_scan_measured" \
    --argjson lokiTestAppRecords "$loki_test_app_records" \
    --argjson lokiReactiveRecords "$loki_reactive_records" \
    --argjson lokiServiceScanMeasured "$loki_service_scan_measured" \
    --argjson jfr "$([[ -n "$(find "$raw_root" -maxdepth 1 -name "$jfr_prefix-*.jfr" -size +0c -print -quit)" ]] && echo true || echo false)" \
    '{oomDetected:$oom,dataBufferLeakWarnings:$leaks,sensitiveDataLeakDetected:$sensitive,
      lokiBinaryPayloadMatches:$lokiBinary,lokiPayloadScanMeasured:$lokiPayloadScanMeasured,
      lokiTestApplicationRecords:$lokiTestAppRecords,lokiReactiveApplicationRecords:$lokiReactiveRecords,
      lokiServiceScanMeasured:$lokiServiceScanMeasured,
      jfrAvailable:$jfr}' >"$output"
  : >"$log_file"
}

capture_containers() {
  local output="$1" oom=false restarts=0 unhealthy=false service id state
  for service in test-app reactive-test-app mock-partner alloy loki prometheus grafana; do
    id="$(compose ps -q "$service" 2>/dev/null || true)"; [[ -n "$id" ]] || continue
    state="$(docker inspect "$id" --format '{{json .State}}')"
    [[ "$(jq -r '.OOMKilled' <<<"$state")" == true ]] && oom=true
    restarts=$((restarts + $(docker inspect "$id" --format '{{.RestartCount}}')))
    [[ "$(jq -r '.Health.Status // "none"' <<<"$state")" == unhealthy ]] && unhealthy=true
  done
  jq -n --argjson oom "$oom" --argjson restarts "$restarts" --argjson unhealthy "$unhealthy" \
    '{oomKilled:$oom,totalRestarts:$restarts,unhealthyDetected:$unhealthy}' >"$output"
}

run_repetition() {
  local profile="$1" repetition="$2" baseline="$3" baseline_result="$4"
  local profile_json duration warmup cooldown rate concurrency capture rep_root prefix profile_workload_hash
  profile_json="$(jq -c --arg id "$profile" '.profiles[] | select(.id==$id)' "$manifest")"
  profile_workload_hash="$(printf '%s' "$profile_json:$workload_hash" | sha256sum | awk '{print $1}')"
  duration="$(jq -r '.durationSeconds' <<<"$profile_json")"; warmup="$(jq -r '.warmupSeconds' <<<"$profile_json")"
  cooldown="$(jq -r '.cooldownSeconds' <<<"$profile_json")"; rate="$(jq -r '.arrivalRatePerSecond // 0' <<<"$profile_json")"
  concurrency="$(jq -r '.concurrency // .virtualUsers // 0' <<<"$profile_json")"
  if [[ "$mode" == "smoke" ]]; then
    duration=10; warmup=3; cooldown=3
    # Exercise all seven deterministic query-mix buckets in mechanics-only mode.
    [[ "$profile" == journey-query ]] && duration=20
  fi
  capture=FULL_SANITIZED; [[ "$profile" == metadata ]] && capture=METADATA_ONLY
  [[ "$profile" == disabled || "$baseline" == true ]] && capture=METADATA_ONLY
  prefix="$profile-r$repetition-$([[ "$baseline" == true ]] && echo baseline || echo enabled)"
  if [[ "$baseline" == true ]]; then rep_root="$raw_root/baselines/$profile/repetition-$repetition"; restart_apps false "$capture" "$prefix"
  else rep_root="$raw_root/$profile/repetition-$repetition"; restart_apps "$([[ "$profile" == disabled ]] && echo false || echo true)" "$capture" "$prefix"; fi
  mkdir -p "$rep_root"; reset_fixtures
  jq --arg profile "$profile" --argjson repetition "$repetition" --argjson baseline "$baseline" \
    '.profiles[] | select(.id==$profile) | . + {repetition:$repetition,isMatchedBaseline:$baseline}' "$manifest" >"$rep_root/profile-config.json"
  local stop_file="$rep_root/.stop-collector" samples_file="$rep_root/resource-samples.ndjson"
  rm -f "$stop_file"
  local collector_pid='' k6_pid='' start_epoch end_epoch actual start_iso end_iso k6_status=0 schedule_pid=''
  local combined=false
  if [[ "$mode" == full && ( "$profile" == reactive || "$profile" == callback-webflux ) ]]; then
    combined=true
    run_k6_phase "$profile" combined "$((warmup + duration))" "$rate" "$concurrency" \
      "${rep_root#$raw_root/}/k6-summary.json" "$warmup" "$duration" &
    k6_pid=$!
    sleep "$warmup"
  else
    capture_json "$test_app_url/fixture/performance/health" "$rep_root/health-before.json"
    run_k6_phase "$profile" warmup "$warmup" "$rate" "$concurrency" \
      "${rep_root#$raw_root/}/k6-warmup-summary.json" >/dev/null
    # Let fixture callbacks with the approved two-second deferred completion settle before reset.
    sleep 3
    reset_fixtures
  fi
  capture_json "$test_app_url/fixture/performance/health" "$rep_root/health-before.json"
  capture_json "$test_app_url/fixture/performance/callbacks" "$rep_root/callbacks-before.json"
  capture_json "$reactive_url/fixture/reactive/metrics" "$rep_root/reactive-before.json"

  python3 test/performance/helpers/collect_metrics.py --output "$samples_file" --app-url "$test_app_url" \
    --reactive-url "$reactive_url" --project "$project_name" --interval 5 --stop-file "$stop_file" &
  collector_pid=$!
  start_epoch="$(date +%s%N)"; start_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [[ "$profile" == saturation && "$baseline" == false ]]; then saturation_schedule "$duration" "$rep_root/outage-evidence.json" & schedule_pid=$!; fi
  set +e
  if [[ "$combined" == true ]]; then
    wait "$k6_pid"; k6_status=$?
  else
    run_k6_phase "$profile" measured "$duration" "$rate" "$concurrency" "${rep_root#$raw_root/}/k6-summary.json"
    k6_status=$?
  fi
  set -e
  end_epoch="$(date +%s%N)"; end_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  actual="$(awk -v start="$start_epoch" -v end="$end_epoch" 'BEGIN {printf "%.3f", (end-start)/1000000000}')"
  if [[ -n "$schedule_pid" ]]; then
    if [[ "$mode" == full ]] && kill -0 "$schedule_pid" 2>/dev/null; then
      echo 'FAIL saturation outage schedule exceeded the measured workload window.' >&2
      k6_status=1
    fi
    wait "$schedule_pid" || k6_status=1
  fi
  sleep "$cooldown"; touch "$stop_file"; wait "$collector_pid" || true
  capture_json "$test_app_url/fixture/performance/health" "$rep_root/health-after.json"
  capture_json "$test_app_url/fixture/performance/timings" "$rep_root/timings.json"
  capture_json "$test_app_url/fixture/performance/records" "$rep_root/records.json"
  capture_json "$test_app_url/fixture/performance/callbacks" "$rep_root/callbacks.json"
  capture_json "$reactive_url/fixture/reactive/metrics" "$rep_root/reactive.json"
  capture_containers "$rep_root/containers.json"
  compose stop test-app reactive-test-app >/dev/null
  capture_diagnostics "$rep_root/diagnostics.json" "$start_iso" "$prefix"
  cp "$raw_root/environment.json" "$rep_root/environment.json"

  local evaluator=(python3 test/performance/helpers/evaluate_repetition.py --manifest "$manifest" --profile-id "$profile"
    --repetition "$repetition" --summary "$rep_root/k6-summary.json" --samples "$samples_file" --timings "$rep_root/timings.json"
    --health-before "$rep_root/health-before.json" --health-after "$rep_root/health-after.json" --records "$rep_root/records.json"
    --callbacks-before "$rep_root/callbacks-before.json" --callbacks "$rep_root/callbacks.json"
    --reactive-before "$rep_root/reactive-before.json" --reactive "$rep_root/reactive.json"
    --containers "$rep_root/containers.json" --environment "$rep_root/environment.json"
    --jfr-file "$raw_root/$prefix-$([[ "$profile" == reactive || "$profile" == callback-webflux ]] && echo reactive-test-app || echo test-app).jfr"
    --diagnostics "$rep_root/diagnostics.json" --outage "$rep_root/outage-evidence.json" \
    --output "$rep_root/result.json" --run-id "$run_id" --git-commit "$git_commit"
    --started-at "$start_iso" --ended-at "$end_iso" --execution-id composite --workload-hash "$profile_workload_hash"
    --actual-duration "$actual" --mode "$mode")
  [[ "$baseline" == true ]] && evaluator+=(--baseline)
  [[ -n "$baseline_result" ]] && evaluator+=(--baseline-result "$baseline_result")
  "${evaluator[@]}"; jq empty "$rep_root/result.json"
  if (( k6_status != 0 )); then echo "FAIL K6: profile=$profile repetition=$repetition baseline=$baseline" >&2; failed=1; fi
  if [[ "$mode" == full ]] && ! jq -e '.passed == true' "$rep_root/result.json" >/dev/null; then
    echo "FAIL verdict: profile=$profile repetition=$repetition baseline=$baseline" >&2; failed=1
  fi
}

seed_journeys() {
  local count=500000; [[ "$mode" == smoke ]] && count=700
  local evidence="$raw_root/journey-seed"; mkdir -p "$evidence"
  local receiver_before filtered_before sent_before failed_before
  receiver_before="$(alloy_metric_sum otelcol_receiver_accepted_log_records_total 'partner_(a|b)')"
  filtered_before="$(alloy_metric_sum otelcol_processor_filter_logs_filtered_total 'partner_(a|b)')"
  sent_before="$(alloy_metric_sum otelcol_exporter_sent_log_records_total 'partner_(a|b)')"
  failed_before="$(alloy_metric_sum otelcol_exporter_send_failed_log_records_total 'partner_(a|b)')"
  k6 run --quiet --summary-export /evidence/journey-seed/k6-summary.json -e RUN_ID="$run_id" \
    -e SEED_RECORD_COUNT="$count" -e SEED_SERVICE_NAME=partner-observability-performance-journey-seed \
    -e OTLP_ENDPOINT="http://127.0.0.1:$otlp_port/v1/logs" /work/k6/seed-journeys.js
  jq -e --argjson expected "$count" \
    '(.metrics.records_seeded.count == $expected) and ((.metrics.dropped_iterations.count // 0) == 0)' \
    "$evidence/k6-summary.json" >/dev/null
  local deadline=$((SECONDS + 300)) receiver_after filtered_after sent_after failed_after
  while true; do
    receiver_after="$(alloy_metric_sum otelcol_receiver_accepted_log_records_total 'partner_(a|b)')"
    filtered_after="$(alloy_metric_sum otelcol_processor_filter_logs_filtered_total 'partner_(a|b)')"
    sent_after="$(alloy_metric_sum otelcol_exporter_sent_log_records_total 'partner_(a|b)')"
    failed_after="$(alloy_metric_sum otelcol_exporter_send_failed_log_records_total 'partner_(a|b)')"
    if (( sent_after - sent_before >= count || filtered_after > filtered_before || failed_after > failed_before )); then break; fi
    (( SECONDS < deadline )) || break
    sleep 2
  done
  local receiver_delta=$((receiver_after - receiver_before)) filtered_delta=$((filtered_after - filtered_before))
  local sent_delta=$((sent_after - sent_before)) failed_delta=$((failed_after - failed_before))
  local collision="SYNTHETIC-COLLISION-${run_id}-00000000" alpha_records=0 beta_records=0 response
  # Export completion precedes read-path visibility by a small, nondeterministic interval. Wait for
  # the same deliberately colliding identifier through both fixed tenant credentials; never accept
  # visibility in just one tenant as sufficient seed evidence.
  deadline=$((SECONDS + 300))
  while true; do
    response="$(curl -fsS -u query-partner-a:local-synthetic-query-a -G \
      --data-urlencode "query={service_name=\"partner-observability-performance-journey-seed\"} | application_id=\"$collision\"" \
      --data-urlencode 'since=384h' --data-urlencode 'limit=20' "http://127.0.0.1:$query_port/loki/api/v1/query_range")"
    alpha_records="$(jq '[.data.result[].values[]] | length' <<<"$response")"
    response="$(curl -fsS -u query-partner-b:local-synthetic-query-b -G \
      --data-urlencode "query={service_name=\"partner-observability-performance-journey-seed\"} | application_id=\"$collision\"" \
      --data-urlencode 'since=384h' --data-urlencode 'limit=20' "http://127.0.0.1:$query_port/loki/api/v1/query_range")"
    beta_records="$(jq '[.data.result[].values[]] | length' <<<"$response")"
    (( alpha_records > 0 && beta_records > 0 )) && break
    (( SECONDS < deadline )) || break
    sleep 2
  done
  jq -n --argjson records "$count" --arg runId "$run_id" \
    --argjson receiver "$receiver_delta" --argjson filtered "$filtered_delta" --argjson sent "$sent_delta" \
    --argjson failed "$failed_delta" --argjson alpha "$alpha_records" --argjson beta "$beta_records" \
    '{runId:$runId,recordsScheduled:$records,retentionDays:16,tenantCount:2,
      ageMixPercent:{last24Hours:50,days1To8:30,days8To16:20},sameIdentifierCollisionPercent:10,
      receiverAcceptedDelta:$receiver,securityFilteredDelta:$filtered,exporterSentDelta:$sent,
      exporterFailedDelta:$failed,collisionMarkerRecords:{alpha:$alpha,beta:$beta},
      passed:($receiver == $records and $filtered == 0 and $sent == $records and $failed == 0 and $alpha > 0 and $beta > 0)}' \
    >"$evidence/result.json"
  jq -e '.passed == true' "$evidence/result.json" >/dev/null || {
    echo 'FAIL: journey seed was not completely retained and queryable in both isolated tenants.' >&2
    return 1
  }
}

echo '[BUILD] Building the two local applications used by B003.'
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/partner-observability-gradle}" ./gradlew --no-daemon \
  :sure-partner-observability-test-app:bootJar :sure-partner-observability-reactive-test-app:bootJar

project_version="$(./gradlew -q properties | awk '/^version:/ {print $2; exit}')"
compose_images="$(compose config --images | sort -u | jq -Rsc 'split("\n") | map(select(length > 0))')"

jq -n --arg runId "$run_id" --arg repo sure-partner-observability --arg gitCommit "$git_commit" \
  --arg branch "$(git branch --show-current)" --arg java "$(java -version 2>&1 | head -n 1)" \
  --arg gradle "$(./gradlew --version | awk '/^Gradle / {print $2; exit}')" --arg docker "$(docker version --format '{{.Server.Version}}')" \
  --arg compose "$(docker compose version --short)" --arg k6 "$(docker run --rm "$k6_image" version 2>&1 | head -n 1)" \
  --arg os "$(uname -srvmo)" --arg wsl "$(uname -r | rg -qi microsoft && uname -r || echo NOT_APPLICABLE)" \
  --arg springProfile local --arg mode "$mode" --arg workloadHash "$workload_hash" \
  --arg projectVersion "$project_version" --argjson composeImages "$compose_images" --argjson cpu "$host_cpus" \
  --argjson memory "$host_memory" --argjson dockerCpu "$docker_cpus" --argjson dockerMemory "$docker_memory" \
  '{runId:$runId,repository:$repo,gitCommit:$gitCommit,branch:$branch,projectVersion:$projectVersion,
    testApplicationVersion:$projectVersion,javaVersion:$java,gradleVersion:$gradle,dockerVersion:$docker,
    dockerComposeVersion:$compose,k6Version:$k6,operatingSystem:$os,wsl:$wsl,logicalCpu:$cpu,
    availableMemoryBytes:$memory,dockerLogicalCpu:$dockerCpu,dockerMemoryBytes:$dockerMemory,
    springProfile:$springProfile,mode:$mode,workloadHash:$workloadHash,
    deterministicWorkloadSeed:"ITERATION_ORDINAL_V1",jvmOptions:["-Xms512m","-Xmx1024m",
    "-XX:MaxMetaspaceSize=256m","-XX:+UseG1GC","-XX:+HeapDumpOnOutOfMemoryError"],
    jfr:{settings:"profile",maxSizeBytes:268435456,dumpOnExit:true},
    containerImages:{k6:"grafana/k6:0.49.0@sha256:8cd78f9d0de5f50bc8821cceecf356d5d9e839e6611c226a3fcf13c591080fbd",compose:$composeImages}}' >"$raw_root/environment.json"

echo '[STACK] Starting the existing local Alloy/Loki/Prometheus/Grafana topology and test fixtures.'
compose up -d loki prometheus alloy tenant-gateway grafana mock-partner test-app reactive-test-app
stack_started=1
wait_http test-app "$test_app_url/actuator/health" 120; wait_http reactive-test-app "$reactive_url/actuator/health" 120
wait_http grafana "http://127.0.0.1:$grafana_port/api/health" 120
grafana_partner_a_org_id="$(curl -fsS -u local-performance-admin:local-synthetic-performance-admin \
  -H 'Content-Type: application/json' -d '{"name":"PARTNER_A"}' "http://127.0.0.1:$grafana_port/api/orgs" | jq -er '.orgId')"
grafana_partner_b_org_id="$(curl -fsS -u local-performance-admin:local-synthetic-performance-admin \
  -H 'Content-Type: application/json' -d '{"name":"PARTNER_B"}' "http://127.0.0.1:$grafana_port/api/orgs" | jq -er '.orgId')"
[[ "$grafana_partner_a_org_id" != "$grafana_partner_b_org_id" ]]
provisioning_dir="$repo_root/grafana/provisioning"
compose up -d --force-recreate --no-deps grafana >/dev/null
wait_http grafana "http://127.0.0.1:$grafana_port/api/health" 120
wait_internal_http prometheus http://prometheus:9090/-/ready 120
wait_internal_http alloy http://alloy:12345/-/ready 120

repetitions="$(jq -r '.repetitions' "$manifest")"; [[ "$mode" == smoke ]] && repetitions=1
mapfile -t profiles < <(jq -r '.profiles[].id' "$manifest")
for profile in "${profiles[@]}"; do
  echo "[PROFILE] $profile"
  # Isolated seed streams avoid collision with application streams. Seed immediately before the
  # query profile because the approved suite is long enough for startup-seeded edge records to age
  # past the 16-day boundary before the final query repetition.
  [[ "$profile" != journey-query ]] || seed_journeys
  for repetition in $(seq 1 "$repetitions"); do
    if [[ "$profile" == disabled || "$profile" == journey-query ]]; then
      run_repetition "$profile" "$repetition" false '' || failed=1
    else
      run_repetition "$profile" "$repetition" true '' || failed=1
      baseline_result="$raw_root/baselines/$profile/repetition-$repetition/result.json"
      run_repetition "$profile" "$repetition" false "$baseline_result" || failed=1
    fi
  done
  if [[ "$mode" == full && "$profile" != disabled && "$profile" != journey-query ]]; then
    mapfile -t baseline_results < <(find "$raw_root/baselines/$profile" -path '*/result.json' | sort)
    if ! python3 test/performance/helpers/validate_baseline.py --manifest "$manifest" \
        --output "$raw_root/baselines/$profile/baseline-stability.json" "${baseline_results[@]}"; then
      echo "INCONCLUSIVE_ENVIRONMENT_UNSTABLE: $profile matched baseline failed stability limits." >&2; failed=1
    fi
  fi
done

ended_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
python3 test/performance/helpers/aggregate_results.py --manifest "$manifest" --run-root "$raw_root" --run-id "$run_id" \
  --git-commit "$git_commit" --mode "$mode" --started-at "$started_at" --ended-at "$ended_at"
jq empty "$raw_root/aggregate-result.json"
if [[ "$mode" == smoke ]]; then echo "SMOKE MODE COMPLETE — NOT B003 RELEASE EVIDENCE: $raw_root/aggregate-result.json"; exit "$failed"; fi
if ! jq -e '.overallPassed == true and .mode == "full" and .springProfile == "local" and .profilesExpected == 9 and (.profilesPassed | length) == 9 and (.missingMandatoryScenarios | length) == 0' "$raw_root/aggregate-result.json" >/dev/null; then failed=1; fi
if (( failed != 0 )); then echo "B003 FAIL: retained evidence: $raw_root/aggregate-result.json" >&2; exit 1; fi

safe_root="$repo_root/test/performance/results/$run_id"; mkdir -p "$safe_root/profiles"
jq '{runId,repository,gitCommit,mode,springProfile,startedAt,endedAt,profilesExpected,profilesExecuted,profilesPassed,profilesFailed,mandatoryScenariosExpected,mandatoryScenariosAsserted,missingMandatoryScenarios,thresholdsFailed,journeySeed,detailedEvidenceRoot,overallPassed,failureReasons}' \
  "$raw_root/aggregate-result.json" >"$safe_root/aggregate-result.json"
for profile in "${profiles[@]}"; do
  jq '{runId,profileId,mode,springProfile,configuredDurationSeconds,warmupSeconds,cooldownSeconds,loadConfiguration,repetitionsExpected,repetitionsExecuted,hardSafetyPassedAllRepetitions,quantitativeVerdicts,manifestThresholdResults,scenarioAssertions,repetitionEvidence,passed,failureReasons}' \
    "$raw_root/$profile/result.json" >"$safe_root/profiles/$profile.json"
done
sha256sum "$safe_root/aggregate-result.json" "$safe_root"/profiles/*.json >"$safe_root/SHA256SUMS"
echo "B003 PASS: full evidence=$raw_root sanitized-result=$safe_root"
