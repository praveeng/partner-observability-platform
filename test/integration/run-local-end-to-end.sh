#!/usr/bin/env bash
set -euo pipefail

# Requirements 36-46 traceability is documented in test/integration/README.md. Mandatory
# visibility assertions below use the Viewer-authenticated Grafana datasource proxy; direct Loki
# and direct OTLP injection are deliberately not used as acceptance evidence.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

for command_name in awk bash curl date docker java jq mktemp rg sleep tr; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "FAIL prerequisites: required command is unavailable: $command_name" >&2
    exit 1
  }
done
[[ -x ./gradlew ]] || { echo "FAIL prerequisites: Gradle wrapper is not executable" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || {
  echo "FAIL prerequisites: Docker Compose v2 is unavailable" >&2
  exit 1
}
java_major="$(java -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
[[ "$java_major" == "17" ]] || {
  echo "FAIL prerequisites: Java 17 is required (found major ${java_major:-unknown})" >&2
  exit 1
}

project_name="${E2E_PROJECT_NAME:-partner-observability-m9-${PPID}-$$}"
port_base=$((32000 + ($$ % 1000) * 7))
app_port="${LOCAL_TEST_APP_PORT:-$port_base}"
grafana_port="${LOCAL_GRAFANA_PORT:-$((port_base + 1))}"
otlp_port="${LOCAL_OTLP_PORT:-$((port_base + 2))}"
query_port="${LOCAL_QUERY_PORT:-$((port_base + 3))}"
prometheus_port="${LOCAL_PROMETHEUS_PORT:-$((port_base + 4))}"
alloy_port="${LOCAL_ALLOY_METRICS_PORT:-$((port_base + 5))}"
async_timeout="${E2E_ASYNC_TIMEOUT_SECONDS:-45}"
telemetry_timeout="${E2E_TELEMETRY_TIMEOUT_SECONDS:-75}"
metric_timeout="${E2E_METRIC_TIMEOUT_SECONDS:-90}"
compose_file="$repo_root/docker/compose.yml"
tmp_dir="$(mktemp -d /tmp/partner-observability-m9.XXXXXX)"
bootstrap_provisioning="$tmp_dir/bootstrap-provisioning"
gateway_password_file="$tmp_dir/local-gateway.htpasswd"
credentials_file="$tmp_dir/credentials.env"
gradle_home="${E2E_GRADLE_USER_HOME:-/tmp/partner-observability-gradle}"
test_app_jar="$repo_root/sure-partner-observability-test-app/build/libs/sure-partner-observability-test-app-0.1.0-SNAPSHOT.jar"
provisioning_dir="$bootstrap_provisioning"
failed=1

mkdir -p "$bootstrap_provisioning"/{alerting,dashboards,datasources,plugins} "$gradle_home"
umask 077

new_secret() { tr -d '-' </proc/sys/kernel/random/uuid; }
admin_user="local-e2e-admin"
partner_a_user="partner-a-e2e-viewer"
partner_b_user="partner-b-e2e-viewer"
admin_password="$(new_secret)"
partner_a_password="$(new_secret)"
partner_b_password="$(new_secret)"
query_a_password="$(new_secret)"
query_b_password="$(new_secret)"
sdk_a_password="$(new_secret)"
sdk_b_password="$(new_secret)"
grafana_secret_key="$(new_secret)$(new_secret)"

printf '%s:{PLAIN}%s\n' \
  sdk-partner-a "$sdk_a_password" \
  sdk-partner-b "$sdk_b_password" \
  query-partner-a "$query_a_password" \
  query-partner-b "$query_b_password" >"$gateway_password_file"
printf 'GRAFANA_URL=http://127.0.0.1:%s\nPARTNER_A_USER=%s\nPARTNER_A_PASSWORD=%s\nPARTNER_B_USER=%s\nPARTNER_B_PASSWORD=%s\n' \
  "$grafana_port" "$partner_a_user" "$partner_a_password" "$partner_b_user" "$partner_b_password" >"$credentials_file"
chmod 0755 "$tmp_dir" "$bootstrap_provisioning"
chmod 0644 "$gateway_password_file"
chmod 0600 "$credentials_file"

compose() {
  LOCAL_TEST_APP_PORT="$app_port" \
    LOCAL_TEST_APP_JAR="$test_app_jar" \
    LOCAL_GRAFANA_PORT="$grafana_port" \
    LOCAL_OTLP_PORT="$otlp_port" \
    LOCAL_QUERY_PORT="$query_port" \
    LOCAL_PROMETHEUS_PORT="$prometheus_port" \
    LOCAL_ALLOY_METRICS_PORT="$alloy_port" \
    LOCAL_GATEWAY_HTPASSWD_FILE="$gateway_password_file" \
    LOCAL_METRICS_TARGET="test-app:8080" \
    LOCAL_METRICS_SERVICE="partner-observability-test-app" \
    LOCAL_E2E_SDK_A_PASSWORD="$sdk_a_password" \
    LOCAL_E2E_SDK_B_PASSWORD="$sdk_b_password" \
    GRAFANA_PROVISIONING_DIR="$provisioning_dir" \
    GRAFANA_ADMIN_USER="$admin_user" \
    GRAFANA_ADMIN_PASSWORD="$admin_password" \
    GRAFANA_SECRET_KEY="$grafana_secret_key" \
    GRAFANA_PARTNER_A_QUERY_PASSWORD="$query_a_password" \
    GRAFANA_PARTNER_B_QUERY_PASSWORD="$query_b_password" \
    GRAFANA_PARTNER_A_ORG_ID="${org_a_id:-2}" \
    GRAFANA_PARTNER_B_ORG_ID="${org_b_id:-3}" \
    docker compose --profile grafana --profile end-to-end -p "$project_name" -f "$compose_file" "$@"
}

cleanup() {
  local status=$?
  if (( failed != 0 )); then
    echo "FAIL: application-to-platform E2E suite; bounded safe component diagnostics follow." >&2
    compose ps >&2 || true
    compose logs --no-color --tail=8 test-app alloy tenant-gateway loki prometheus grafana >&2 || true
  fi
  if [[ "${KEEP_RUNNING:-0}" == "1" ]]; then
    printf 'KEEP_RUNNING: project=%s app=http://127.0.0.1:%s Grafana=http://127.0.0.1:%s credentials=%s\n' \
      "$project_name" "$app_port" "$grafana_port" "$credentials_file"
    return "$status"
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
  return "$status"
}
trap cleanup EXIT
trap 'status=$?; printf "FAIL: command exited %s at %s:%s\n" "$status" "${BASH_SOURCE[0]}" "$LINENO" >&2' ERR

stage() { printf '\n[%s] %s\n' "$1" "$2"; }

wait_http() {
  local name="$1" url="$2" timeout="$3" output="$4"
  local deadline=$((SECONDS + timeout))
  until curl -fsS "$url" >"$output" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      echo "FAIL health: $name did not become healthy within ${timeout}s" >&2
      return 1
    fi
    sleep 1
  done
}

grafana_status() {
  local user="$1" password="$2" method="$3" path="$4"
  local data_file="${5:-}" output_file="${6:-$tmp_dir/grafana-response.json}"
  local args=(-sS -o "$output_file" -w '%{http_code}' -u "$user:$password" -X "$method")
  if [[ -n "$data_file" ]]; then
    args+=(-H 'Content-Type: application/json' --data-binary "@$data_file")
  fi
  curl "${args[@]}" "http://127.0.0.1:${grafana_port}${path}"
}

require_status() {
  local expected="$1"; shift
  local actual
  actual="$(grafana_status "$@")"
  [[ "$actual" == "$expected" ]] || {
    echo "FAIL Grafana API: expected HTTP $expected, got $actual" >&2
    return 1
  }
}

grafana_loki_query() {
  local user="$1" password="$2" expression="$3" output_file="$4"
  shift 4
  curl -fsS -u "$user:$password" "$@" --get \
    --data-urlencode "query=$expression" \
    --data-urlencode 'limit=1000' \
    --data-urlencode 'since=30m' \
    --data-urlencode 'direction=forward' \
    "http://127.0.0.1:${grafana_port}/api/datasources/proxy/uid/partner-loki/loki/api/v1/query_range" >"$output_file"
}

loki_count() { jq '[.data.result[].values[]] | length' "$1"; }

wait_loki_search() {
  local user="$1" password="$2" metadata="$3" value="$4" minimum="$5" output="$6"
  local deadline=$((SECONDS + telemetry_timeout)) count=0
  while (( SECONDS < deadline )); do
    if grafana_loki_query "$user" "$password" \
        "{service_name=\"partner-observability-test-app\"} | ${metadata}=\"${value}\"" "$output" 2>/dev/null; then
      count="$(loki_count "$output")"
      if (( count >= minimum )); then return 0; fi
    fi
    sleep 1
  done
  echo "FAIL authorized search: $metadata expected at least $minimum records, found $count within ${telemetry_timeout}s" >&2
  return 1
}

assert_loki_absent() {
  local user="$1" password="$2" metadata="$3" value="$4" output="$5"
  grafana_loki_query "$user" "$password" \
    "{service_name=\"partner-observability-test-app\"} | ${metadata}=\"${value}\"" "$output"
  [[ "$(loki_count "$output")" == "0" ]] || {
    echo "FAIL tenant isolation: foreign $metadata was visible" >&2
    return 1
  }
}

grafana_prom_query() {
  local user="$1" password="$2" expression="$3" output="$4"
  shift 4
  curl -fsS -u "$user:$password" "$@" --get --data-urlencode "query=$expression" \
    "http://127.0.0.1:${grafana_port}/api/datasources/proxy/uid/partner-prometheus/api/v1/query" >"$output"
}

wait_prom_value() {
  local user="$1" password="$2" expression="$3" output="$4"
  local deadline=$((SECONDS + metric_timeout))
  while (( SECONDS < deadline )); do
    if grafana_prom_query "$user" "$password" "$expression" "$output" 2>/dev/null \
        && jq -e '.status == "success" and (.data.result | length) > 0 and all(.data.result[]; (.value[1] != "NaN") and (.value[1] != "+Inf") and (.value[1] != "-Inf")) and ([.data.result[].value[1] | tonumber] | add) > 0' "$output" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "FAIL partner SLI query: no usable value within ${metric_timeout}s: $expression" >&2
  return 1
}

post_app() {
  local path="$1" output="$2"
  curl -fsS -X POST "http://127.0.0.1:${app_port}${path}" >"$output"
}

wait_async_run() {
  local run_id="$1" terminal_stage="$2" output="$3"
  local deadline=$((SECONDS + async_timeout))
  while (( SECONDS < deadline )); do
    if curl -fsS "http://127.0.0.1:${app_port}/fixture/async/runs/${run_id}" >"$output" 2>/dev/null \
        && jq -e --arg stage "$terminal_stage" 'any(.events[]; .stage == $stage)' "$output" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "FAIL async fixture: run $run_id did not reach $terminal_stage within ${async_timeout}s" >&2
  return 1
}

start_async() {
  local partner="$1" scenario="$2" prefix="$3" terminal="${4:-CALLBACK_RESPONSE_SENT}"
  local summary="$tmp_dir/${prefix}-summary.json" snapshot="$tmp_dir/${prefix}-snapshot.json"
  post_app "/fixture/async/${partner}/${scenario}" "$summary"
  jq -e '.acknowledgementHttpStatus == 202 and .acknowledgementReceived == true' "$summary" >/dev/null
  local run_id
  run_id="$(jq -er '.runId' "$summary")"
  wait_async_run "$run_id" "$terminal" "$snapshot"
  printf '%s\n' "$snapshot"
}

stage "STATIC" "Validate executable contracts, fixed routing, provisioning, dashboard, and Compose topology"
bash -n "$0"
jq empty grafana/dashboards/partner-operations.json
rg -q 'sdk-partner-a:partner-alpha-fixture.*alloy:4318' docker/nginx/local-gateway.conf
rg -q 'sdk-partner-b:partner-beta-fixture.*alloy:4319' docker/nginx/local-gateway.conf
rg -q 'partner-alpha-fixture' alloy/local-config.alloy
rg -q 'partner-beta-fixture' alloy/local-config.alloy
rg -q 'LOCAL_METRICS_TARGET' alloy/local-config.alloy docker/compose.yml
if rg -n -i 'X-Scope-OrgID|partner_slot.*(variable|templating)|https?://(loki|prometheus):' grafana; then
  echo "FAIL static security: Grafana contains a tenant-selector or internal-backend bypass" >&2
  exit 1
fi
compose config --format json >"$tmp_dir/compose.json"
jq -e '
  (.services["test-app"].networks | has("backend")) and
  (.services["test-app"].networks | has("edge")) and
  all(.services["test-app"].ports[]; .host_ip == "127.0.0.1") and
  (.services.loki.ports == null) and
  (.services.grafana.networks | keys == ["portal"]) and
  .networks.backend.internal == true
' "$tmp_dir/compose.json" >/dev/null
echo "PASS STATIC: app, fixed ingest routes, internal Loki, fixed Grafana datasources, and dashboard contracts are valid"

stage "BUILD" "Build the SDK, starter, autoconfiguration, and synthetic test application from source"
GRADLE_USER_HOME="$gradle_home" ./gradlew --no-daemon \
  :sure-partner-observability-core:check \
  :sure-partner-observability-spring-boot-autoconfigure:check \
  :sure-partner-observability-spring-boot-starter:check \
  :sure-partner-observability-test-app:check \
  :sure-partner-observability-test-app:bootJar
[[ -s "$test_app_jar" ]]
echo "PASS BUILD: real Java 17 application and SDK modules built and tested"

stage "PLATFORM" "Start isolated Loki, Prometheus, Alloy, query gateway, and bootstrap Grafana"
compose up -d --wait loki prometheus metrics-fixture alloy prom-label-proxy tenant-gateway grafana
wait_http Grafana "http://127.0.0.1:${grafana_port}/api/health" 60 "$tmp_dir/grafana-health.json"
jq -e '.database == "ok"' "$tmp_dir/grafana-health.json" >/dev/null
wait_http Alloy "http://127.0.0.1:${alloy_port}/-/ready" 45 "$tmp_dir/alloy-health.txt"
echo "PASS PLATFORM: Loki, Prometheus, Alloy, tenant gateway, and Grafana are healthy with bounded waits"

stage "GRAFANA-SETUP" "Create two organizations, Viewer-only users, and fixed provisioned datasources"
jq -n '{name:"PARTNER_A"}' >"$tmp_dir/org-a.json"
require_status 200 "$admin_user" "$admin_password" POST /api/orgs "$tmp_dir/org-a.json" "$tmp_dir/org-a-response.json"
org_a_id="$(jq -er '.orgId' "$tmp_dir/org-a-response.json")"
jq -n '{name:"PARTNER_B"}' >"$tmp_dir/org-b.json"
require_status 200 "$admin_user" "$admin_password" POST /api/orgs "$tmp_dir/org-b.json" "$tmp_dir/org-b-response.json"
org_b_id="$(jq -er '.orgId' "$tmp_dir/org-b-response.json")"
[[ "$org_a_id" != "$org_b_id" ]]

for spec in "a:$partner_a_user:$partner_a_password:$org_a_id" "b:$partner_b_user:$partner_b_password:$org_b_id"; do
  IFS=: read -r suffix login password org_id <<<"$spec"
  jq -n --arg login "$login" --arg password "$password" \
    --arg suffix "$suffix" '{name:("Partner " + ($suffix|ascii_upcase) + " E2E Viewer"),email:("partner-"+$suffix+"-e2e@local.invalid"),login:$login,password:$password}' \
    >"$tmp_dir/user-${suffix}.json"
  require_status 200 "$admin_user" "$admin_password" POST /api/admin/users "$tmp_dir/user-${suffix}.json" "$tmp_dir/user-${suffix}-response.json"
  user_id="$(jq -er '.id' "$tmp_dir/user-${suffix}-response.json")"
  jq -n --arg login "$login" '{loginOrEmail:$login,role:"Viewer"}' >"$tmp_dir/add-${suffix}.json"
  require_status 200 "$admin_user" "$admin_password" POST "/api/orgs/${org_id}/users" "$tmp_dir/add-${suffix}.json" "$tmp_dir/add-${suffix}-response.json"
  require_status 200 "$admin_user" "$admin_password" GET "/api/users/${user_id}/orgs" "" "$tmp_dir/orgs-${suffix}.json"
  while IFS= read -r old_org; do
    if [[ "$old_org" != "$org_id" ]]; then
      require_status 200 "$admin_user" "$admin_password" DELETE "/api/orgs/${old_org}" "" "$tmp_dir/remove-${suffix}.json"
    fi
  done < <(jq -r '.[].orgId' "$tmp_dir/orgs-${suffix}.json")
  require_status 200 "$admin_user" "$admin_password" POST "/api/users/${user_id}/using/${org_id}" "" "$tmp_dir/select-${suffix}.json"
done

compose stop grafana >/dev/null
provisioning_dir="$repo_root/grafana/provisioning"
compose up -d --wait --force-recreate grafana
wait_http Grafana "http://127.0.0.1:${grafana_port}/api/health" 60 "$tmp_dir/grafana-health.json"
for spec in "a:$partner_a_user:$partner_a_password:$org_a_id" "b:$partner_b_user:$partner_b_password:$org_b_id"; do
  IFS=: read -r suffix login password org_id <<<"$spec"
  require_status 200 "$login" "$password" GET /api/user "" "$tmp_dir/me-${suffix}.json"
  jq -e --argjson org "$org_id" '.isGrafanaAdmin == false and .orgId == $org' "$tmp_dir/me-${suffix}.json" >/dev/null
  require_status 200 "$login" "$password" GET /api/user/orgs "" "$tmp_dir/my-orgs-${suffix}.json"
  jq -e --argjson org "$org_id" 'length == 1 and .[0].orgId == $org and .[0].role == "Viewer"' "$tmp_dir/my-orgs-${suffix}.json" >/dev/null
  require_status 200 "$login" "$password" GET /api/datasources "" "$tmp_dir/datasources-${suffix}.json"
  jq -e 'length == 2 and ([.[].uid] | sort == ["partner-loki","partner-prometheus"]) and all(.[]; .readOnly == true and (.url | startswith("http://tenant-gateway:8081")))' "$tmp_dir/datasources-${suffix}.json" >/dev/null
  require_status 200 "$login" "$password" GET /api/dashboards/uid/partner-operations "" "$tmp_dir/dashboard-${suffix}.json"
  jq -e '.dashboard.uid == "partner-operations" and .dashboard.editable == false and .meta.canEdit == false and .meta.provisioned == true' "$tmp_dir/dashboard-${suffix}.json" >/dev/null
done
invalid_status="$(grafana_status "$partner_a_user" invalid-e2e-password GET /api/user)"
[[ "$invalid_status" == "401" ]]
echo "PASS GRAFANA-SETUP: both authenticated users have exactly one partner organization, Viewer role, and fixed read-only datasources"

stage "APPLICATION" "Start the built application and verify its real Actuator health endpoint"
compose up -d test-app
wait_http test-app "http://127.0.0.1:${app_port}/actuator/health" 60 "$tmp_dir/app-health.json"
jq -e '.status == "UP"' "$tmp_dir/app-health.json" >/dev/null
echo "PASS APPLICATION: sure-partner-observability-test-app is running from the source-built boot JAR"

stage "SYNC" "Execute real instrumented RestTemplate journeys for both partners"
post_app /fixture/rest/alpha/success "$tmp_dir/sync-a.json"
post_app /fixture/rest/beta/success "$tmp_dir/sync-b.json"
jq -e '.failureType == null and .partner == "ALPHA" and .httpStatus == 200' "$tmp_dir/sync-a.json" >/dev/null
jq -e '.failureType == null and .partner == "BETA" and .httpStatus == 200' "$tmp_dir/sync-b.json" >/dev/null
sync_app="SYNTHETIC-APPLICATION-COLLISION-0001"
sync_loan_a="SYNTHETIC-SYNC-LOAN-ALPHA"
sync_corr_a="SYNTHETIC-SYNC-CORRELATION-ALPHA"
sync_ref_a="SYNTHETIC-REF-ALPHA"
wait_loki_search "$partner_a_user" "$partner_a_password" application_id "$sync_app" 2 "$tmp_dir/sync-visible-a.json"
wait_loki_search "$partner_a_user" "$partner_a_password" loan_id "$sync_loan_a" 2 "$tmp_dir/sync-loan-a.json"
wait_loki_search "$partner_a_user" "$partner_a_password" original_correlation_id "$sync_corr_a" 2 "$tmp_dir/sync-corr-a.json"
wait_loki_search "$partner_a_user" "$partner_a_password" partner_reference_id "$sync_ref_a" 2 "$tmp_dir/sync-ref-a.json"
jq -e '
  [.data.result[].values[][1] | fromjson] as $lines |
  any($lines[]; .record == "PARTNER_API_REQUEST" and .apiName == "PARTNER_ALPHA_SYNC" and .direction == "OUTBOUND_TO_PARTNER" and .requestPayload.amount == 1234.56 and .requestPayload.product == "SYNTHETIC-SKU-001") and
  any($lines[]; .record == "PARTNER_API_RESPONSE" and .apiName == "PARTNER_ALPHA_SYNC" and .status == "SUCCESS" and (.latencyMs >= 0))
' "$tmp_dir/sync-visible-a.json" >/dev/null
echo "PASS SYNC: application-originated request/response records expose searchable identifiers, safe detail, status, direction, and latency"

stage "ASYNC-CALLBACK" "Execute HTTP 202 acknowledgement and real delayed callback journeys"
async_a_snapshot="$(start_async alpha callback-success async-a)"
async_b_snapshot="$(start_async beta callback-success async-b)"
async_app="$(jq -er '.identifiers.applicationId' "$async_a_snapshot")"
async_loan_a="$(jq -er '.identifiers.loanId' "$async_a_snapshot")"
async_corr_a="$(jq -er '.identifiers.originalCorrelationId' "$async_a_snapshot")"
async_ref_a="$(jq -er '.identifiers.partnerReferenceId' "$async_a_snapshot")"
async_cb_a="$(jq -er '.callbackAttempts[0].identifiers.callbackReferenceId' "$async_a_snapshot")"
async_cb_b="$(jq -er '.callbackAttempts[0].identifiers.callbackReferenceId' "$async_b_snapshot")"
jq -e '
  [.events[].stage] as $s |
  (["ASYNC_REQUEST_SENT","ASYNC_ACK_RECEIVED","CALLBACK_RECEIVED","CALLBACK_PROCESSED","CALLBACK_RESPONSE_SENT"] | all(. as $required | $s | index($required) != null)) and
  .callbackAttempts[0].processingOutcome == "SUCCESS" and .callbackAttempts[0].responseStatus == 200
' "$async_a_snapshot" >/dev/null
wait_loki_search "$partner_a_user" "$partner_a_password" callback_reference_id "$async_cb_a" 5 "$tmp_dir/async-a-loki.json"
for search in "application_id:$async_app" "loan_id:$async_loan_a" "original_correlation_id:$async_corr_a" "partner_reference_id:$async_ref_a"; do
  IFS=: read -r key value <<<"$search"
  wait_loki_search "$partner_a_user" "$partner_a_password" "$key" "$value" 5 "$tmp_dir/async-search-${key}.json"
done
jq -e '
  [.data.result[].values[] | {ts:(.[0]|tonumber), body:(.[1]|fromjson)}] | sort_by(.ts) |
  map(select(.body.record == "ASYNC_REQUEST_SENT" or .body.record == "ASYNC_ACK_RECEIVED" or .body.record == "CALLBACK_RECEIVED" or .body.record == "CALLBACK_PROCESSED" or .body.record == "CALLBACK_RESPONSE_SENT")) as $events |
  [$events[].body.record] == ["ASYNC_REQUEST_SENT","ASYNC_ACK_RECEIVED","CALLBACK_RECEIVED","CALLBACK_PROCESSED","CALLBACK_RESPONSE_SENT"] and
  ($events[0].ts < $events[1].ts and $events[1].ts <= $events[2].ts and $events[2].ts <= $events[3].ts and $events[3].ts <= $events[4].ts) and
  any($events[]; .body.record == "CALLBACK_RECEIVED" and .body.apiName == "CREDIT_DECISION_CALLBACK_ALPHA" and .body.callbackPayload.fixtureclassification == "SYNTHETIC_ONLY")
' "$tmp_dir/async-search-loan_id.json" >/dev/null
echo "PASS ASYNC-CALLBACK: outbound request, 202 ack, callback receipt, distinct processing result, response, correlation, and callback-reference search are proven"

stage "EVENT" "Verify a selected partner-safe application log becomes a correlated business event"
jq -e '
  [.data.result[].values[][1] | fromjson] |
  any(.[]; .record == "PARTNER_EVENT" and .eventName == "CALLBACK_JOURNEY_UPDATED" and .journeyStage == "CALLBACK_BUSINESS_EVENT" and .eventAttributes.eventstatus == "PROCESSED")
' "$tmp_dir/async-a-loki.json" >/dev/null
echo "PASS EVENT: an application-originated selected safe event traversed the SDK dispatcher, Alloy, tenant Loki, and authorized Grafana query path"

stage "ISOLATION" "Prove bidirectional denial and same-identifier collision isolation"
assert_loki_absent "$partner_a_user" "$partner_a_password" callback_reference_id "$async_cb_b" "$tmp_dir/a-cannot-see-b-callback.json"
assert_loki_absent "$partner_b_user" "$partner_b_password" callback_reference_id "$async_cb_a" "$tmp_dir/b-cannot-see-a-callback.json"
assert_loki_absent "$partner_a_user" "$partner_a_password" loan_id "$(jq -er '.identifiers.loanId' "$async_b_snapshot")" "$tmp_dir/a-cannot-see-b-loan.json"
assert_loki_absent "$partner_b_user" "$partner_b_password" loan_id "$async_loan_a" "$tmp_dir/b-cannot-see-a-loan.json"

wait_loki_search "$partner_a_user" "$partner_a_password" application_id "$async_app" 5 "$tmp_dir/shared-app-a.json"
wait_loki_search "$partner_b_user" "$partner_b_password" application_id "$async_app" 5 "$tmp_dir/shared-app-b.json"
jq -e 'all(.data.result[].values[][1]; (contains("_BETA") or contains("CALLBACK_BETA")) | not)' "$tmp_dir/shared-app-a.json" >/dev/null
jq -e 'all(.data.result[].values[][1]; (contains("_ALPHA") or contains("CALLBACK_ALPHA")) | not)' "$tmp_dir/shared-app-b.json" >/dev/null

collision_a_snapshot="$(start_async alpha cross-partner-callback-reference collision-a)"
collision_b_snapshot="$(start_async beta cross-partner-callback-reference collision-b)"
shared_cb="SYNTHETIC-CALLBACK-REFERENCE-COLLISION-0001"
wait_loki_search "$partner_a_user" "$partner_a_password" callback_reference_id "$shared_cb" 5 "$tmp_dir/shared-cb-a.json"
wait_loki_search "$partner_b_user" "$partner_b_password" callback_reference_id "$shared_cb" 5 "$tmp_dir/shared-cb-b.json"
jq -e 'all(.data.result[].values[][1]; (contains("_BETA") or contains("CALLBACK_BETA")) | not)' "$tmp_dir/shared-cb-a.json" >/dev/null
jq -e 'all(.data.result[].values[][1]; (contains("_ALPHA") or contains("CALLBACK_ALPHA")) | not)' "$tmp_dir/shared-cb-b.json" >/dev/null
echo "PASS ISOLATION: A/B callbacks and events are mutually denied; shared application and callback references remain tenant-pure"

stage "PAYLOAD-SAFETY" "Exercise PII, secret, OTP, card, and large Base64 through real application traffic"
post_app /fixture/rest/alpha/restricted-pii "$tmp_dir/pii-sync.json"
post_app /fixture/rest/alpha/credentials "$tmp_dir/credentials-sync.json"
post_app /fixture/rest/alpha/otp "$tmp_dir/otp-sync.json"
post_app /fixture/rest/alpha/card-data "$tmp_dir/card-sync.json"
pii_snapshot="$(start_async alpha callback-sensitive-pii pii-callback)"
secret_snapshot="$(start_async alpha callback-credentials secret-callback)"
base64_snapshot="$(start_async alpha callback-pdf-base64-5-mb base64-callback)"
pii_cb="$(jq -er '.callbackAttempts[0].identifiers.callbackReferenceId' "$pii_snapshot")"
secret_cb="$(jq -er '.callbackAttempts[0].identifiers.callbackReferenceId' "$secret_snapshot")"
base64_cb="$(jq -er '.callbackAttempts[0].identifiers.callbackReferenceId' "$base64_snapshot")"
wait_loki_search "$partner_a_user" "$partner_a_password" callback_reference_id "$pii_cb" 5 "$tmp_dir/pii-visible.json"
wait_loki_search "$partner_a_user" "$partner_a_password" callback_reference_id "$secret_cb" 5 "$tmp_dir/secret-visible.json"
wait_loki_search "$partner_a_user" "$partner_a_password" callback_reference_id "$base64_cb" 5 "$tmp_dir/base64-visible.json"
jq -e '
  [.data.result[].values[][1] | fromjson] |
  any(.[]; .record == "CALLBACK_RECEIVED" and .callbackPayload.callbackdata.phone == "[MASKED_PHONE]" and .callbackPayload.callbackdata.email == "[MASKED_EMAIL]" and .callbackPayload.callbackdata.bankaccount == "[MASKED_ACCOUNT]" and .callbackPayload.callbackdata.nationalid == "[MASKED_NATIONAL_ID]" and .callbackPayload.callbackdata.address == "[MASKED_ADDRESS]" and .callbackPayloadMaskedValues >= 5)
' "$tmp_dir/pii-visible.json" >/dev/null
jq -e '
  [.data.result[].values[][1] | fromjson] |
  any(.[]; .record == "CALLBACK_RECEIVED" and .callbackPayload.callbackdata.fixtureclassification == "SYNTHETIC_ONLY" and .callbackPayloadRemovedValues >= 1)
' "$tmp_dir/secret-visible.json" >/dev/null
jq -e '
  [.data.result[].values[][1] | fromjson] |
  any(.[]; .record == "CALLBACK_RECEIVED" and .callbackPayloadStatus == "OVERSIZE" and (.callbackPayload | not))
' "$tmp_dir/base64-visible.json" >/dev/null
grafana_loki_query "$partner_a_user" "$partner_a_password" '{service_name="partner-observability-test-app"}' "$tmp_dir/all-partner-a.json"
jq -e '
  [.data.result[].values[][1] | fromjson] |
  any(.[]; .record == "PARTNER_API_REQUEST" and .requestPayload.attributes.phone == "[MASKED_PHONE]" and .requestPayload.attributes.email == "[MASKED_EMAIL]" and .requestPayload.attributes.bankaccount == "[MASKED_ACCOUNT]" and .requestPayload.attributes.nationalid == "[MASKED_NATIONAL_ID]" and .requestPayload.attributes.address == "[MASKED_ADDRESS]" and .requestPayloadMaskedValues >= 5)
' "$tmp_dir/all-partner-a.json" >/dev/null
if rg -q -i 'SYNTHETIC_AUTHORIZATION_ONLY|SYNTHETIC_SESSION_ONLY|SYNTHETIC_API_KEY_ONLY|SYNTHETIC_PASSWORD_ONLY|SYNTHETIC_CLIENT_SECRET_ONLY|SYNTHETIC_ACCESS_TOKEN_ONLY|SYNTHETIC_ENCRYPTION_KEY_ONLY|4111111111111111|"oneTimePassword"|"verificationCode"|"cardNumber"|"cvv"' "$tmp_dir/all-partner-a.json"; then
  echo "FAIL payload safety: a prohibited synthetic value reached the partner-visible result" >&2
  exit 1
fi
if rg -q '"[A-Za-z0-9+/]{1024,}={0,2}"' "$tmp_dir/all-partner-a.json"; then
  echo "FAIL binary safety: a large Base64 value reached the partner-visible result" >&2
  exit 1
fi
echo "PASS PAYLOAD-SAFETY: PII is masked, secrets/OTP/card values are removed, and the successful 5 MiB callback exposes omission status without Base64"

stage "FAILURE-METRICS" "Generate timeout, retry, callback retry, and callback-processing failure metrics"
post_app /fixture/rest/alpha/timeout "$tmp_dir/timeout.json"
post_app /fixture/rest/alpha/retry "$tmp_dir/retry.json"
retry_snapshot="$(start_async alpha callback-retry callback-retry)"
wait_async_run "$(jq -er '.runId' "$retry_snapshot")" CALLBACK_RETRY_RECEIVED "$retry_snapshot"
failure_snapshot="$(start_async alpha callback-processing-failure callback-failure CALLBACK_PROCESSING_FAILED)"
jq -e 'any(.events[]; .stage == "CALLBACK_RETRY_RECEIVED")' "$retry_snapshot" >/dev/null
jq -e 'any(.events[]; .stage == "CALLBACK_PROCESSING_FAILED")' "$failure_snapshot" >/dev/null
echo "PASS FAILURE-METRICS: real traffic exercised timeout, outbound retry, callback retry, and processing-failure paths"

stage "METRICS-SLI" "Validate real application counters, histograms, quantiles, and throughput through Grafana"
metric_queries=(
  'sum(partner_observability_http_interactions_total{api="PARTNER_ALPHA_SYNC"})'
  'sum(partner_observability_http_interactions_total{api="PARTNER_ALPHA_SYNC",outcome="success"})'
  'sum(partner_observability_http_interactions_total{api="PARTNER_ALPHA_SYNC",result="timeout"})'
  'sum(partner_observability_outbound_retries_total{api="PARTNER_ALPHA_SYNC"})'
  'sum(partner_observability_http_duration_seconds_count{api="PARTNER_ALPHA_SYNC"})'
  'sum(partner_observability_callback_deliveries_total{api="CREDIT_DECISION_CALLBACK_ALPHA"})'
  'sum(partner_observability_callback_processing_total{api="CREDIT_DECISION_CALLBACK_ALPHA",outcome="success"})'
  'sum(partner_observability_callback_processing_total{api="CREDIT_DECISION_CALLBACK_ALPHA",outcome="technical_failure"})'
  'sum(partner_observability_callback_processing_duration_seconds_count{api="CREDIT_DECISION_CALLBACK_ALPHA"})'
)
for expression in "${metric_queries[@]}"; do
  wait_prom_value "$partner_a_user" "$partner_a_password" "$expression" "$tmp_dir/metric.json"
done

# Produce a second scrape delta so rate/histogram_quantile queries use real increasing samples.
post_app /fixture/rest/alpha/success "$tmp_dir/sync-a-second.json"
second_async_snapshot="$(start_async alpha callback-success async-a-second)"
sli_queries=(
  'sum(rate(partner_observability_http_interactions_total{api="PARTNER_ALPHA_SYNC"}[5m]))'
  'histogram_quantile(0.50,sum by (le) (rate(partner_observability_http_duration_seconds_bucket{api="PARTNER_ALPHA_SYNC"}[5m])))'
  'histogram_quantile(0.95,sum by (le) (rate(partner_observability_http_duration_seconds_bucket{api="PARTNER_ALPHA_SYNC"}[5m])))'
  'histogram_quantile(0.99,sum by (le) (rate(partner_observability_http_duration_seconds_bucket{api="PARTNER_ALPHA_SYNC"}[5m])))'
  'sum(rate(partner_observability_callback_deliveries_total{api="CREDIT_DECISION_CALLBACK_ALPHA"}[5m]))'
)
for expression in "${sli_queries[@]}"; do
  wait_prom_value "$partner_a_user" "$partner_a_password" "$expression" "$tmp_dir/sli.json"
done
wait_prom_value "$partner_b_user" "$partner_b_password" 'sum(partner_observability_http_interactions_total{api="PARTNER_BETA_SYNC"})' "$tmp_dir/metric-b.json"
jq -e 'all(.data.result[]; .metric.partner_slot == null or .metric.partner_slot == "p002")' "$tmp_dir/metric-b.json" >/dev/null
grafana_prom_query "$partner_a_user" "$partner_a_password" partner_observability_http_interactions_total "$tmp_dir/all-metrics-a.json" -H 'X-Partner-Slot: p002'
jq -e '.data.result | length > 0 and all(.[]; .metric.partner_slot == "p001")' "$tmp_dir/all-metrics-a.json" >/dev/null
echo "PASS METRICS-SLI: request/success/timeout/retry, callback success/failure, histograms, p50/p95/p99, and throughput return tenant-scoped application values"

stage "GRAFANA-QUERY" "Exercise provisioned dashboard query APIs and reject header/query tenant manipulation"
query_from="$(( $(date +%s) - 1800 ))000"
query_to="$(( $(date +%s) + 60 ))000"
jq -n --arg from "$query_from" --arg to "$query_to" --arg callback "$async_cb_a" \
  '{from:$from,to:$to,queries:[{refId:"A",datasource:{type:"loki",uid:"partner-loki"},expr:("{service_name=\"partner-observability-test-app\"} | callback_reference_id=\""+$callback+"\""),queryType:"range",maxLines:1000,intervalMs:1000}]}' \
  >"$tmp_dir/dashboard-query.json"
require_status 200 "$partner_a_user" "$partner_a_password" POST /api/ds/query "$tmp_dir/dashboard-query.json" "$tmp_dir/dashboard-query-response.json"
jq -e --arg callback "$async_cb_a" '
  any(.results[].frames[]?.data.values[][]?; type == "string" and contains($callback))
' "$tmp_dir/dashboard-query-response.json" >/dev/null

jq -n --arg from "$query_from" --arg to "$query_to" --arg callback "$async_cb_b" \
  '{from:$from,to:$to,queries:[{refId:"A",datasource:{type:"loki",uid:"partner-loki"},expr:("{service_name=\"partner-observability-test-app\"} | callback_reference_id=\""+$callback+"\""),queryType:"range",maxLines:1000,intervalMs:1000}]}' \
  >"$tmp_dir/foreign-dashboard-query.json"
foreign_status="$(curl -sS -o "$tmp_dir/foreign-dashboard-query-response.json" -w '%{http_code}' \
  -u "$partner_a_user:$partner_a_password" -H 'Content-Type: application/json' \
  -H 'X-Scope-OrgID: local-p002-91bc' -H 'X-Partner-Slot: p002' \
  --data-binary "@$tmp_dir/foreign-dashboard-query.json" "http://127.0.0.1:${grafana_port}/api/ds/query")"
[[ "$foreign_status" == "200" ]]
if ! jq -e '([.results[].frames[]?.data.values[]? | length] | add // 0) == 0' \
    "$tmp_dir/foreign-dashboard-query-response.json" >/dev/null; then
  echo "FAIL Grafana query boundary: Partner A obtained Partner B callback data" >&2
  exit 1
fi
echo "PASS GRAFANA-QUERY: Partner Operations datasource queries return real A data while foreign callback and tenant-header manipulation return none"

stage "ALLOY" "Confirm the application export was accepted by Alloy and no internal Loki endpoint is exposed"
curl -fsS "http://127.0.0.1:${alloy_port}/metrics" >"$tmp_dir/alloy-metrics.txt"
rg -q 'otelcol_receiver_accepted_log_records[^\n]* [1-9][0-9]*(\.[0-9]+)?$' "$tmp_dir/alloy-metrics.txt"
loki_container="$(compose ps -q loki)"
docker inspect "$loki_container" >"$tmp_dir/loki-inspect.json"
jq -e '.[0].HostConfig.PortBindings == null or (.[0].HostConfig.PortBindings | length == 0)' \
  "$tmp_dir/loki-inspect.json" >/dev/null || {
    echo "FAIL exposure: Loki unexpectedly has a published host port" >&2
    exit 1
  }
echo "PASS ALLOY: receiver acceptance is non-zero; all accepted evidence remained behind the fixed gateway and Grafana boundary"

failed=0
echo "PASS: requirements 36-46 application -> SDK -> Alloy -> Loki/Prometheus -> tenant gateway -> Grafana end-to-end boundary"
