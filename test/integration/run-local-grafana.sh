#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

mode="${1:-}"
if [[ -n "$mode" && "$mode" != "--validate-only" ]]; then
  echo "ERROR: usage: $0 [--validate-only]" >&2
  exit 2
fi

for command_name in bash curl docker jq rg; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "FAIL prerequisites: required command is unavailable: $command_name" >&2
    exit 1
  }
done
docker compose version >/dev/null 2>&1 || {
  echo "FAIL prerequisites: Docker Compose v2 is unavailable" >&2
  exit 1
}

project_name="${GRAFANA_PROJECT_NAME:-partner-observability-m7-${PPID}-$$}"
port_base=$((28000 + ($$ % 2000)))
grafana_port="${LOCAL_GRAFANA_PORT:-$port_base}"
otlp_port=$((port_base + 1))
query_port=$((port_base + 2))
prometheus_port=$((port_base + 3))
alloy_port=$((port_base + 4))
compose_file="$repo_root/docker/compose.yml"
tmp_dir="$(mktemp -d /tmp/partner-observability-m7.XXXXXX)"
bootstrap_provisioning="$tmp_dir/bootstrap-provisioning"
gateway_password_file="$tmp_dir/local-gateway.htpasswd"
metrics_fixture_file="$tmp_dir/local-metrics.prom"
credentials_file="$tmp_dir/credentials.env"
failed=1
provisioning_dir="$bootstrap_provisioning"

mkdir -p \
  "$bootstrap_provisioning/alerting" \
  "$bootstrap_provisioning/dashboards" \
  "$bootstrap_provisioning/datasources" \
  "$bootstrap_provisioning/plugins"
umask 077

new_secret() {
  tr -d '-' </proc/sys/kernel/random/uuid
}

admin_user="local-grafana-admin"
partner_a_user="partner-a-viewer"
partner_b_user="partner-b-viewer"
admin_password="$(new_secret)"
partner_a_password="$(new_secret)"
partner_b_password="$(new_secret)"
query_a_password="$(new_secret)"
query_b_password="$(new_secret)"
sdk_a_password="$(new_secret)"
sdk_b_password="$(new_secret)"
grafana_secret_key="$(new_secret)$(new_secret)"

printf '%s:{PLAIN}%s\n' \
  "sdk-partner-a" "$sdk_a_password" \
  "sdk-partner-b" "$sdk_b_password" \
  "query-partner-a" "$query_a_password" \
  "query-partner-b" "$query_b_password" >"$gateway_password_file"
cp docker/nginx/local-metrics.prom "$metrics_fixture_file"
printf 'GRAFANA_URL=http://127.0.0.1:%s\nGRAFANA_ADMIN_USER=%s\nGRAFANA_ADMIN_PASSWORD=%s\nPARTNER_A_USER=%s\nPARTNER_A_PASSWORD=%s\nPARTNER_B_USER=%s\nPARTNER_B_PASSWORD=%s\n' \
  "$grafana_port" "$admin_user" "$admin_password" \
  "$partner_a_user" "$partner_a_password" \
  "$partner_b_user" "$partner_b_password" >"$credentials_file"
chmod 0755 "$tmp_dir" "$bootstrap_provisioning"
chmod 0644 "$gateway_password_file" "$metrics_fixture_file"
chmod 0600 "$credentials_file"

compose() {
  LOCAL_GRAFANA_PORT="$grafana_port" \
    LOCAL_OTLP_PORT="$otlp_port" \
    LOCAL_QUERY_PORT="$query_port" \
    LOCAL_PROMETHEUS_PORT="$prometheus_port" \
    LOCAL_ALLOY_METRICS_PORT="$alloy_port" \
    LOCAL_GATEWAY_HTPASSWD_FILE="$gateway_password_file" \
    LOCAL_METRICS_FIXTURE_FILE="$metrics_fixture_file" \
    GRAFANA_PROVISIONING_DIR="$provisioning_dir" \
    GRAFANA_ADMIN_USER="$admin_user" \
    GRAFANA_ADMIN_PASSWORD="$admin_password" \
    GRAFANA_SECRET_KEY="$grafana_secret_key" \
    GRAFANA_PARTNER_A_QUERY_PASSWORD="$query_a_password" \
    GRAFANA_PARTNER_B_QUERY_PASSWORD="$query_b_password" \
    GRAFANA_PARTNER_A_ORG_ID="${org_a_id:-2}" \
    GRAFANA_PARTNER_B_ORG_ID="${org_b_id:-3}" \
    docker compose --profile grafana -p "$project_name" -f "$compose_file" "$@"
}

cleanup() {
  if (( failed != 0 )); then
    echo "FAIL: local Grafana integration; bounded component logs follow (request bodies and secrets are not logged)." >&2
    compose logs --no-color --tail=5 grafana tenant-gateway prom-label-proxy loki prometheus alloy >&2 || true
  fi
  if [[ "${KEEP_RUNNING:-0}" == "1" ]]; then
    printf 'KEEP_RUNNING: project=%s Grafana=http://127.0.0.1:%s credentials=%s\n' \
      "$project_name" "$grafana_port" "$credentials_file"
    trap - EXIT
    return
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
}
trap cleanup EXIT
trap 'status=$?; printf "FAIL: command exited %s at %s:%s\n" "$status" "${BASH_SOURCE[0]}" "$LINENO" >&2' ERR

stage() {
  printf '\n[%s] %s\n' "$1" "$2"
}

grafana_status() {
  local user="$1"
  local password="$2"
  local method="$3"
  local path="$4"
  local data_file="${5:-}"
  local output_file="${6:-$tmp_dir/grafana-response.json}"
  local args=(-sS -o "$output_file" -w '%{http_code}' -u "$user:$password" -X "$method")
  if [[ -n "$data_file" ]]; then
    args+=(-H 'Content-Type: application/json' --data-binary "@$data_file")
  fi
  curl "${args[@]}" "http://127.0.0.1:${grafana_port}${path}"
}

require_status() {
  local expected="$1"
  shift
  local actual
  actual="$(grafana_status "$@")"
  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL Grafana API: expected HTTP $expected, got $actual" >&2
    return 1
  fi
}

require_denied_status() {
  local actual="$1"
  shift
  case "$actual" in
    401|403|404) return 0 ;;
    *) echo "FAIL authorization: expected denial, got HTTP $actual ($*)" >&2; return 1 ;;
  esac
}

wait_for_grafana() {
  local attempt
  for attempt in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:${grafana_port}/api/health" >"$tmp_dir/health.json" 2>/dev/null \
        && jq -e '.database == "ok"' "$tmp_dir/health.json" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "FAIL Grafana health: bounded 60-second wait expired" >&2
  return 1
}

new_uuid() {
  tr '[:upper:]' '[:lower:]' </proc/sys/kernel/random/uuid
}

make_event() {
  local output_file="$1"
  local event_id="$2"
  local partner_key="$3"
  local event_type="$4"
  local event_domain="$5"
  local direction="$6"
  local outcome="$7"
  local timeline_stage="$8"
  local api_id="$9"
  local body="${10}"
  local application_id="${11}"
  local loan_id="${12}"
  local original_correlation_id="${13}"
  local partner_reference_id="${14}"
  local callback_reference_id="${15}"
  local timestamp_nanos="${16}"
  local interaction_id callback_attempt_id
  interaction_id="$(new_uuid)"
  callback_attempt_id="$(new_uuid)"

  jq -n \
    --arg event_id "$event_id" \
    --arg partner_key "$partner_key" \
    --arg event_type "$event_type" \
    --arg event_domain "$event_domain" \
    --arg direction "$direction" \
    --arg outcome "$outcome" \
    --arg timeline_stage "$timeline_stage" \
    --arg api_id "$api_id" \
    --arg body "$body" \
    --arg application_id "$application_id" \
    --arg loan_id "$loan_id" \
    --arg original_correlation_id "$original_correlation_id" \
    --arg partner_reference_id "$partner_reference_id" \
    --arg callback_reference_id "$callback_reference_id" \
    --arg timestamp_nanos "$timestamp_nanos" \
    --arg interaction_id "$interaction_id" \
    --arg callback_attempt_id "$callback_attempt_id" \
    '{resourceLogs:[{resource:{attributes:[
        {key:"service.name",value:{stringValue:"synthetic-partner-service"}},
        {key:"market",value:{stringValue:"LOCAL"}},
        {key:"deployment.environment",value:{stringValue:"local"}}
      ]},scopeLogs:[{scope:{name:"partner-observability-sdk",version:"0.1.0"},logRecords:[{
        timeUnixNano:$timestamp_nanos,observedTimeUnixNano:$timestamp_nanos,severityText:"INFO",
        body:{stringValue:$body},attributes:([
          {key:"schema.version",value:{intValue:"2"}},
          {key:"partner.key",value:{stringValue:$partner_key}},
          {key:"event.type",value:{stringValue:$event_type}},
          {key:"event.domain",value:{stringValue:$event_domain}},
          {key:"direction",value:{stringValue:$direction}},
          {key:"outcome",value:{stringValue:$outcome}},
          {key:"severity",value:{stringValue:"INFO"}},
          {key:"event.id",value:{stringValue:$event_id}},
          {key:"interaction.id",value:{stringValue:$interaction_id}},
          {key:"correlation.profile.id",value:{stringValue:"synthetic-partner-journey"}},
          {key:"api.id",value:{stringValue:$api_id}},
          {key:"service.version",value:{stringValue:"0.1.0"}},
          {key:"application.id",value:{stringValue:$application_id}},
          {key:"loan.id",value:{stringValue:$loan_id}},
          {key:"original.correlation.id",value:{stringValue:$original_correlation_id}},
          {key:"partner.reference.id",value:{stringValue:$partner_reference_id}},
          {key:"callback.reference.id",value:{stringValue:$callback_reference_id}}
        ] + (if $timeline_stage == "" then [] else [{key:"timeline.stage",value:{stringValue:$timeline_stage}}] end)
          + (if ($event_type | startswith("callback_")) then [{key:"callback.attempt.id",value:{stringValue:$callback_attempt_id}}] else [] end))
      }]}]}]}' >"$output_file"
}

post_event() {
  local user="$1"
  local password="$2"
  local route="$3"
  local payload_file="$4"
  local status
  status="$(curl -sS -o "$tmp_dir/ingest-response.json" -w '%{http_code}' \
    -u "$user:$password" -H 'Content-Type: application/json' -H "X-Partner-Route: $route" \
    --data-binary "@$payload_file" "http://127.0.0.1:${otlp_port}/v1/logs")"
  [[ "$status" == "200" ]] || {
    echo "FAIL ingest: expected HTTP 200, got $status" >&2
    return 1
  }
}

grafana_loki_query() {
  local user="$1"
  local password="$2"
  local expression="$3"
  local output_file="$4"
  shift 4
  curl -fsS -u "$user:$password" "$@" --get \
    --data-urlencode "query=$expression" \
    --data-urlencode 'limit=500' \
    --data-urlencode 'since=15m' \
    --data-urlencode 'direction=forward' \
    "http://127.0.0.1:${grafana_port}/api/datasources/proxy/uid/partner-loki/loki/api/v1/query_range" >"$output_file"
}

loki_result_count() {
  jq '[.data.result[].values[]] | length' "$1"
}

wait_for_event() {
  local user="$1"
  local password="$2"
  local event_id="$3"
  local output_file="$4"
  local attempt
  for attempt in $(seq 1 50); do
    if grafana_loki_query "$user" "$password" \
        "{service_name=\"synthetic-partner-service\"} | event_id=\"$event_id\"" "$output_file" 2>/dev/null \
        && [[ "$(loki_result_count "$output_file")" == "1" ]]; then
      return 0
    fi
    sleep 0.25
  done
  echo "FAIL Loki search: event was not queryable through Grafana within the bounded wait" >&2
  return 1
}

assert_search_count() {
  local user="$1"
  local password="$2"
  local metadata_key="$3"
  local value="$4"
  local expected="$5"
  local output_file="$tmp_dir/search-${user}-${metadata_key}.json"
  grafana_loki_query "$user" "$password" \
    "{service_name=\"synthetic-partner-service\"} | ${metadata_key}=\"${value}\"" "$output_file"
  local actual
  actual="$(loki_result_count "$output_file")"
  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL search: $metadata_key expected $expected results, got $actual" >&2
    return 1
  fi
}

grafana_prom_query() {
  local user="$1"
  local password="$2"
  local expression="$3"
  local output_file="$4"
  shift 4
  curl -fsS -u "$user:$password" "$@" --get \
    --data-urlencode "query=$expression" \
    "http://127.0.0.1:${grafana_port}/api/datasources/proxy/uid/partner-prometheus/api/v1/query" >"$output_file"
}

wait_for_prom_result() {
  local user="$1"
  local password="$2"
  local expression="$3"
  local output_file="$4"
  local attempt
  for attempt in $(seq 1 50); do
    if grafana_prom_query "$user" "$password" "$expression" "$output_file" 2>/dev/null \
        && jq -e '.status == "success" and (.data.result | length) > 0 and all(.data.result[]; .value[1] != "NaN")' "$output_file" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "FAIL SLI query: no usable result within the bounded 50-second wait: $expression" >&2
  return 1
}

stage "STATIC" "Validate dashboard JSON, provisioning contracts, and network topology"
jq empty grafana/dashboards/partner-operations.json
jq -e '
  .uid == "partner-operations" and .editable == false and
  ([.templating.list[].name] | index("partner") == null and index("tenant") == null and index("partner_slot") == null) and
  ([.. | objects | .datasource?.uid? // empty] | all(. == "partner-loki" or . == "partner-prometheus")) and
  ([.panels[].title] | map(ascii_downcase) | join(" ") |
    contains("transaction search") and contains("transaction overview") and contains("timeline") and
    contains("detail") and contains("request count") and contains("availability") and
    contains("error rate") and contains("timeout rate") and contains("retry rate") and
    contains("throughput") and contains("p50") and contains("callback"))
' grafana/dashboards/partner-operations.json >/dev/null
rg -q 'application_id.*loan_id.*original_correlation_id.*partner_reference_id.*callback_reference_id' \
  grafana/dashboards/partner-operations.json
rg -q 'PARTNER_API_REQUEST.*PARTNER_API_RESPONSE.*ASYNC_REQUEST_SENT.*ASYNC_ACK_RECEIVED.*CALLBACK_RECEIVED.*CALLBACK_PROCESSED.*CALLBACK_PROCESSING_FAILED.*CALLBACK_RESPONSE_SENT' \
  grafana/dashboards/partner-operations.json
if rg -n -i 'X-Scope-OrgID|partner_slot.*(variable|templating)|https?://(loki|prometheus):' grafana; then
  echo "FAIL static security: dashboard/provisioning bypasses the fixed gateway or exposes authorization as a variable" >&2
  exit 1
fi
if rg -n 'basicAuthPassword:[[:space:]]+[^$]|password:[[:space:]]+[^$]' grafana/provisioning; then
  echo "FAIL static security: a datasource password is hard-coded" >&2
  exit 1
fi
for uid in partner-loki partner-prometheus; do
  rg -q "uid: ${uid}$" grafana/provisioning/datasources/partner-datasources.yaml
  rg -q "\"uid\": \"${uid}\"" grafana/dashboards/partner-operations.json
done

compose config --format json >"$tmp_dir/compose.json"
jq -e '
  (.services.loki.ports == null) and
  (.services["prom-label-proxy"].ports == null) and
  (.services.grafana.networks | keys == ["portal"]) and
  (.services["tenant-gateway"].networks | has("portal")) and
  (.services["tenant-gateway"].networks | has("backend")) and
  .networks.backend.internal == true and (.networks.portal.internal != true) and
  all(.services.grafana.ports[]; .host_ip == "127.0.0.1") and
  all(.services["tenant-gateway"].ports[]; .host_ip == "127.0.0.1")
' "$tmp_dir/compose.json" >/dev/null
gateway_image="$(jq -er '.services["tenant-gateway"].image' "$tmp_dir/compose.json")"
docker run --rm --add-host loki:127.0.0.1 --add-host prom-label-proxy:127.0.0.1 \
  -v "$repo_root/docker/nginx/local-gateway.conf:/etc/nginx/nginx.conf:ro" \
  -v "$gateway_password_file:/etc/nginx/local-synthetic.htpasswd:ro" \
  "$gateway_image" nginx -t
echo "PASS STATIC: one generic dashboard, fixed datasource UIDs, generated secrets, and portal-only Grafana networking"

stage "BOOTSTRAP" "Start real Loki, Prometheus, gateways, and Grafana with bounded health waits"
compose up -d --wait loki prometheus metrics-fixture alloy prom-label-proxy tenant-gateway grafana
wait_for_grafana
jq -e '.database == "ok" and (.version | length > 0)' "$tmp_dir/health.json" >/dev/null
echo "PASS HEALTH: Grafana database and HTTP API are healthy"

stage "AUTH-SETUP" "Create PARTNER_A/PARTNER_B organizations and Viewer-only local accounts"
jq -n --arg name PARTNER_A '{name:$name}' >"$tmp_dir/org-a.json"
require_status 200 "$admin_user" "$admin_password" POST /api/orgs "$tmp_dir/org-a.json" "$tmp_dir/org-a-response.json"
org_a_id="$(jq -er '.orgId | select(type == "number" and . > 1)' "$tmp_dir/org-a-response.json")"
jq -n --arg name PARTNER_B '{name:$name}' >"$tmp_dir/org-b.json"
require_status 200 "$admin_user" "$admin_password" POST /api/orgs "$tmp_dir/org-b.json" "$tmp_dir/org-b-response.json"
org_b_id="$(jq -er '.orgId | select(type == "number" and . > 1)' "$tmp_dir/org-b-response.json")"
[[ "$org_a_id" != "$org_b_id" ]] || {
  echo "FAIL organization setup: partner organizations did not receive distinct IDs" >&2
  exit 1
}

jq -n --arg login "$partner_a_user" --arg password "$partner_a_password" \
  '{name:"Partner A Synthetic Viewer",email:"partner-a-viewer@local.invalid",login:$login,password:$password}' >"$tmp_dir/user-a.json"
require_status 200 "$admin_user" "$admin_password" POST /api/admin/users "$tmp_dir/user-a.json" "$tmp_dir/user-a-response.json"
user_a_id="$(jq -er '.id' "$tmp_dir/user-a-response.json")"
jq -n --arg login "$partner_b_user" --arg password "$partner_b_password" \
  '{name:"Partner B Synthetic Viewer",email:"partner-b-viewer@local.invalid",login:$login,password:$password}' >"$tmp_dir/user-b.json"
require_status 200 "$admin_user" "$admin_password" POST /api/admin/users "$tmp_dir/user-b.json" "$tmp_dir/user-b-response.json"
user_b_id="$(jq -er '.id' "$tmp_dir/user-b-response.json")"

for spec in "$org_a_id:$user_a_id:$partner_a_user:a" "$org_b_id:$user_b_id:$partner_b_user:b"; do
  IFS=: read -r org_id user_id login suffix <<<"$spec"
  jq -n --arg login "$login" '{loginOrEmail:$login,role:"Viewer"}' >"$tmp_dir/add-org-user.json"
  require_status 200 "$admin_user" "$admin_password" POST "/api/orgs/${org_id}/users" \
    "$tmp_dir/add-org-user.json" "$tmp_dir/add-org-user-response.json"
  require_status 200 "$admin_user" "$admin_password" GET "/api/users/${user_id}/orgs" "" "$tmp_dir/user-${suffix}-orgs.json"
  while IFS= read -r current_org_id; do
    if [[ "$current_org_id" != "$org_id" ]]; then
      require_status 200 "$admin_user" "$admin_password" DELETE "/api/orgs/${current_org_id}" \
        "" "$tmp_dir/remove-bootstrap-org-response.json"
    fi
  done < <(jq -r '.[].orgId' "$tmp_dir/user-${suffix}-orgs.json")
  jq -n '{role:"Viewer"}' >"$tmp_dir/viewer-role.json"
  role_status="$(grafana_status "$admin_user" "$admin_password" PATCH "/api/orgs/${org_id}/users/${user_id}" "$tmp_dir/viewer-role.json")"
  [[ "$role_status" == "200" ]]
  require_status 200 "$admin_user" "$admin_password" GET "/api/users/${user_id}/orgs" "" "$tmp_dir/user-${suffix}-orgs.json"
  jq -e --argjson org "$org_id" 'length == 1 and .[0].orgId == $org and .[0].role == "Viewer"' \
    "$tmp_dir/user-${suffix}-orgs.json" >/dev/null
  require_status 200 "$admin_user" "$admin_password" POST "/api/users/${user_id}/using/${org_id}" \
    "" "$tmp_dir/select-org-response.json"
done

stage "PROVISION" "Restart Grafana with file-provisioned fixed datasources and generic dashboards"
compose stop grafana >/dev/null
provisioning_dir="$repo_root/grafana/provisioning"
compose up -d --wait --force-recreate grafana
wait_for_grafana

for spec in "$partner_a_user:$partner_a_password:$org_a_id" "$partner_b_user:$partner_b_password:$org_b_id"; do
  IFS=: read -r user password org_id <<<"$spec"
  require_status 200 "$user" "$password" GET /api/user "" "$tmp_dir/user.json"
  jq -e --argjson org "$org_id" '.isGrafanaAdmin == false and .orgId == $org' "$tmp_dir/user.json" >/dev/null
  require_status 200 "$user" "$password" GET /api/user/orgs "" "$tmp_dir/user-orgs.json"
  jq -e --argjson org "$org_id" 'length == 1 and .[0].orgId == $org and .[0].role == "Viewer"' "$tmp_dir/user-orgs.json" >/dev/null
  require_status 200 "$user" "$password" GET /api/datasources "" "$tmp_dir/datasources.json"
  jq -e '
    length == 2 and
    ([.[].uid] | sort == ["partner-loki","partner-prometheus"]) and
    all(.[]; (.access == "proxy") and (.readOnly == true) and (.url | startswith("http://tenant-gateway:8081"))) and
    all(.[]; (.secureJsonData | not) and (.basicAuthPassword | not))
  ' "$tmp_dir/datasources.json" >/dev/null
  if rg -q --fixed-strings "$query_a_password" "$tmp_dir/datasources.json" || rg -q --fixed-strings "$query_b_password" "$tmp_dir/datasources.json"; then
    echo "FAIL datasource secrecy: secure provisioning material was returned to a Viewer" >&2
    exit 1
  fi
  require_status 200 "$user" "$password" GET /api/dashboards/uid/partner-operations "" "$tmp_dir/dashboard.json"
  jq -e '.dashboard.uid == "partner-operations" and .dashboard.editable == false and .meta.canEdit == false and .meta.provisioned == true' "$tmp_dir/dashboard.json" >/dev/null
done
echo "PASS PROVISION: two isolated organizations each have two read-only gateway datasources and the same provisioned dashboard source"

stage "AUTHZ" "Prove authentication, Viewer role, org isolation, session logout, and administration denial"
invalid_status="$(grafana_status "$partner_a_user" "invalid-local-synthetic-password" GET /api/user)"
[[ "$invalid_status" == "401" ]]

jq -n --arg user "$partner_a_user" --arg password "$partner_a_password" '{user:$user,password:$password}' >"$tmp_dir/login.json"
login_status="$(curl -sS -o "$tmp_dir/login-response.json" -w '%{http_code}' -c "$tmp_dir/session.cookies" \
  -H 'Content-Type: application/json' --data-binary "@$tmp_dir/login.json" "http://127.0.0.1:${grafana_port}/login")"
[[ "$login_status" == "200" ]]
curl -fsS -b "$tmp_dir/session.cookies" "http://127.0.0.1:${grafana_port}/api/user" >"$tmp_dir/session-user.json"
logout_status="$(curl -sS -o /dev/null -w '%{http_code}' -b "$tmp_dir/session.cookies" -c "$tmp_dir/session.cookies" \
  "http://127.0.0.1:${grafana_port}/logout")"
[[ "$logout_status" == "200" || "$logout_status" == "302" ]]
post_logout_status="$(curl -sS -o /dev/null -w '%{http_code}' -b "$tmp_dir/session.cookies" "http://127.0.0.1:${grafana_port}/api/user")"
[[ "$post_logout_status" == "401" ]]

jq -n '{name:"forbidden",type:"loki",url:"http://loki:3100",access:"proxy"}' >"$tmp_dir/forbidden-datasource.json"
for spec in "$partner_a_user:$partner_a_password:$org_b_id" "$partner_b_user:$partner_b_password:$org_a_id"; do
  IFS=: read -r user password foreign_org <<<"$spec"
  status="$(grafana_status "$user" "$password" POST /api/datasources "$tmp_dir/forbidden-datasource.json")"
  require_denied_status "$status" "datasource administration"
  status="$(grafana_status "$user" "$password" GET /api/org/users)"
  require_denied_status "$status" "organization user administration"
  status="$(grafana_status "$user" "$password" POST "/api/user/using/${foreign_org}")"
  require_denied_status "$status" "organization switching"
  status="$(grafana_status "$user" "$password" GET /api/admin/users)"
  require_denied_status "$status" "server administration"
  status="$(grafana_status "$user" "$password" GET /api/datasources/uid/internal-loki)"
  require_denied_status "$status" "internal datasource UID guessing"
  explore_result="$(curl -sS -o /dev/null -w '%{http_code} %{url_effective}' -L --max-redirs 3 \
    -u "$user:$password" "http://127.0.0.1:${grafana_port}/explore")"
  read -r explore_status explore_url <<<"$explore_result"
  [[ "$explore_status" == "200" && "$explore_url" != *"/explore"* ]] || {
    echo "FAIL authorization: Explore was not redirected to a safe page" >&2
    exit 1
  }
done

gateway_direct_status="$(curl -sS -o /dev/null -w '%{http_code}' -u "$partner_a_user:$partner_a_password" \
  "http://127.0.0.1:${query_port}/loki/api/v1/labels")"
require_denied_status "$gateway_direct_status" "direct gateway authentication with Grafana credentials"
gateway_bad_secret_status="$(curl -sS -o /dev/null -w '%{http_code}' -u 'query-partner-a:invalid-local-synthetic-password' \
  "http://127.0.0.1:${query_port}/loki/api/v1/labels")"
require_denied_status "$gateway_bad_secret_status" "direct gateway authentication with an invalid datasource password"
echo "PASS AUTHZ: local login/session works, invalid login fails, Viewers cannot switch orgs, edit resources, use Explore, or authenticate directly to the gateway"

if [[ "$mode" == "--validate-only" ]]; then
  failed=0
  echo "PASS VALIDATE-ONLY: real Grafana provisioning, health, authentication, organization, role, datasource, dashboard, and secret-boundary validation"
  exit 0
fi

stage "SEED" "Load synthetic A/B journeys, exact identifier collisions, safe detail, and hostile disclosure candidates"
base_seconds=$(( $(date +%s) - 120 ))
event_file="$tmp_dir/event.json"
app_a="APP-A-001"
loan_a="LOAN-A-001"
correlation_a="CORR-A-001"
partner_ref_a="PA-REF-001"
callback_ref_a="PA-CB-001"
app_b="APP-B-001"
loan_b="LOAN-B-001"
correlation_b="CORR-B-001"
partner_ref_b="PB-REF-001"
callback_ref_b="PB-CB-001"
shared_app="SHARED-APP-001"
detail_event_id=""
barrier_event_id=""

timeline_specs=(
  "outbound_api_request|API|OUTBOUND_TO_PARTNER|UNKNOWN|ASYNC_REQUEST_SENT|submit-application|PARTNER_API_REQUEST|REQUEST_SENT"
  "async_acknowledgement|ASYNC|OUTBOUND_TO_PARTNER|SUCCESS|ASYNC_ACK_RECEIVED|submit-application|ASYNC_ACK_RECEIVED|ACCEPTED"
  "callback_request|CALLBACK|INBOUND_FROM_PARTNER|UNKNOWN|CALLBACK_RECEIVED|application-callback|CALLBACK_RECEIVED|RECEIVED"
  "callback_processing_event|CALLBACK|INBOUND_FROM_PARTNER|SUCCESS|CALLBACK_PROCESSED|application-callback|CALLBACK_PROCESSED|PROCESSED"
  "callback_response|CALLBACK|INBOUND_FROM_PARTNER|SUCCESS|CALLBACK_RESPONSE_SENT|application-callback|CALLBACK_RESPONSE_SENT|SENT"
)
timeline_index=0
for spec in "${timeline_specs[@]}"; do
  IFS='|' read -r event_type event_domain direction outcome timeline_stage api_id display_record status <<<"$spec"
  event_id="$(new_uuid)"
  (( timeline_index += 1 ))
  timestamp_nanos="$((base_seconds + timeline_index))000000000"
  body="$(jq -cn \
    --arg record "$display_record" --arg stage "$timeline_stage" --arg eventId "$event_id" \
    --arg applicationId "$app_a" --arg loanId "$loan_a" --arg correlationId "$correlation_a" \
    --arg partnerReferenceId "$partner_ref_a" --arg callbackReferenceId "$callback_ref_a" \
    --arg apiName "$api_id" --arg direction "$direction" --arg status "$status" \
    '{record:$record,timelineStage:$stage,eventId:$eventId,applicationId:$applicationId,loanId:$loanId,correlationId:$correlationId,partnerReferenceId:$partnerReferenceId,callbackReferenceId:$callbackReferenceId,apiName:$apiName,direction:$direction,status:$status,latencyMs:125,payload:{safeStatus:"accepted",product:"SYNTHETIC_PRODUCT"},binaryOmission:{omitted:true,category:"BASE64",declaredSizeBytes:10485760},retry:{attempt:1},errorCode:null}')"
  make_event "$event_file" "$event_id" partner-a "$event_type" "$event_domain" "$direction" "$outcome" "$timeline_stage" "$api_id" "$body" \
    "$app_a" "$loan_a" "$correlation_a" "$partner_ref_a" "$callback_ref_a" "$timestamp_nanos"
  post_event sdk-partner-a "$sdk_a_password" partner-a "$event_file"
  if [[ "$timeline_stage" == "CALLBACK_RECEIVED" ]]; then detail_event_id="$event_id"; fi
  barrier_event_id="$event_id"
done

partner_b_event_id="$(new_uuid)"
body="$(jq -cn --arg eventId "$partner_b_event_id" --arg applicationId "$app_b" --arg loanId "$loan_b" --arg partnerReferenceId "$partner_ref_b" --arg callbackReferenceId "$callback_ref_b" \
  '{record:"PARTNER_API_RESPONSE",timelineStage:"PARTNER_API_RESPONSE",eventId:$eventId,applicationId:$applicationId,loanId:$loanId,partnerReferenceId:$partnerReferenceId,callbackReferenceId:$callbackReferenceId,status:"SUCCESS",payload:{safeStatus:"accepted"}}')"
make_event "$event_file" "$partner_b_event_id" partner-b outbound_api_response API OUTBOUND_TO_PARTNER SUCCESS "" submit-application "$body" \
  "$app_b" "$loan_b" "$correlation_b" "$partner_ref_b" "$callback_ref_b" "$((base_seconds + 10))000000000"
post_event sdk-partner-b "$sdk_b_password" partner-b "$event_file"

for partner in a b; do
  collision_id="$(new_uuid)"
  if [[ "$partner" == "a" ]]; then
    route=partner-a; sdk_user=sdk-partner-a; sdk_password="$sdk_a_password"; own_canary=partner-a-collision; own_loan="SHARED-LOAN-A"; own_corr="SHARED-CORR-A"; own_ref="SHARED-REF-A"; own_cb="SHARED-CB-A"
  else
    route=partner-b; sdk_user=sdk-partner-b; sdk_password="$sdk_b_password"; own_canary=partner-b-collision; own_loan="SHARED-LOAN-B"; own_corr="SHARED-CORR-B"; own_ref="SHARED-REF-B"; own_cb="SHARED-CB-B"
  fi
  body="$(jq -cn --arg canary "$own_canary" --arg applicationId "$shared_app" '{record:"PARTNER_EVENT",canary:$canary,applicationId:$applicationId,status:"VISIBLE"}')"
  make_event "$event_file" "$collision_id" "$route" partner_business_event BUSINESS OUTBOUND_TO_PARTNER SUCCESS "" journey-updated "$body" \
    "$shared_app" "$own_loan" "$own_corr" "$own_ref" "$own_cb" "$((base_seconds + 20))000000000"
  post_event "$sdk_user" "$sdk_password" "$route" "$event_file"
  if [[ "$partner" == "b" ]]; then barrier_event_id="$collision_id"; fi
done

hostile_event_id="$(new_uuid)"
hostile_body='{"record":"partner_business_event","Authorization":"Bearer LOCAL_SYNTHETIC_FORBIDDEN","syntheticJwt":"eyJzeW50aGV0aWMiOiJmb3JiaWRkZW4ifQ.eyJ0ZXN0Ijoib25seSJ9.synthetic","otp":"654321","cardValue":"4111111111111111","phone":"+1 202 555 0199","email":"unsafe.person@example.test","bankAccount":"SYNTHETIC-ACCOUNT-5432","nationalId":"SYNTHETIC-NATIONAL-6789","largeBase64":"U1lOVEhFVElDX0ZPUkJJRERFTl9CQVNFNjRfRklYVFVSRQ=="}'
make_event "$event_file" "$hostile_event_id" partner-a partner_business_event BUSINESS OUTBOUND_TO_PARTNER SUCCESS "" hostile-fixture "$hostile_body" \
  "HOSTILE-APP-A" "HOSTILE-LOAN-A" "HOSTILE-CORR-A" "HOSTILE-REF-A" "HOSTILE-CB-A" "$((base_seconds + 21))000000000"
post_event sdk-partner-a "$sdk_a_password" partner-a "$event_file"
wait_for_event "$partner_b_user" "$partner_b_password" "$barrier_event_id" "$tmp_dir/barrier.json"
echo "PASS SEED: synthetic journeys reached Loki through Alloy; hostile candidates were submitted only to the defense-in-depth path"

stage "SEARCH" "Prove exact A/B searches and same-identifier tenant isolation through Grafana"
for key_value in \
  "application_id:$app_a" "loan_id:$loan_a" "original_correlation_id:$correlation_a" \
  "partner_reference_id:$partner_ref_a" "callback_reference_id:$callback_ref_a"; do
  IFS=: read -r key value <<<"$key_value"
  assert_search_count "$partner_a_user" "$partner_a_password" "$key" "$value" 5
done
assert_search_count "$partner_a_user" "$partner_a_password" application_id "$app_b" 0
assert_search_count "$partner_b_user" "$partner_b_password" application_id "$app_b" 1
assert_search_count "$partner_b_user" "$partner_b_password" application_id "$app_a" 0

grafana_loki_query "$partner_a_user" "$partner_a_password" \
  "{service_name=\"synthetic-partner-service\"} | application_id=\"$shared_app\"" "$tmp_dir/shared-a.json"
grafana_loki_query "$partner_b_user" "$partner_b_password" \
  "{service_name=\"synthetic-partner-service\"} | application_id=\"$shared_app\"" "$tmp_dir/shared-b.json"
jq -e '([.data.result[].values[][1]] | length) == 1 and all(.data.result[].values[][1]; contains("partner-a-collision") and (contains("partner-b-collision") | not))' "$tmp_dir/shared-a.json" >/dev/null
jq -e '([.data.result[].values[][1]] | length) == 1 and all(.data.result[].values[][1]; contains("partner-b-collision") and (contains("partner-a-collision") | not))' "$tmp_dir/shared-b.json" >/dev/null

grafana_loki_query "$partner_a_user" "$partner_a_password" \
  "{service_name=\"synthetic-partner-service\"} | event_id=\"$partner_b_event_id\"" "$tmp_dir/header-spoof.json" \
  -H 'X-Scope-OrgID: local-p002-91bc' -H 'x-scope-orgid: local-p002-91bc'
[[ "$(loki_result_count "$tmp_dir/header-spoof.json")" == "0" ]]
echo "PASS SEARCH: required identifier fields and SHARED-APP-001 remain fixed to the authenticated partner tenant"

stage "TIMELINE-DETAIL" "Prove ordered lifecycle facts, safe detail, omission metadata, and prohibited-content absence"
grafana_loki_query "$partner_a_user" "$partner_a_password" \
  "{service_name=\"synthetic-partner-service\"} | application_id=\"$app_a\"" "$tmp_dir/timeline.json"
jq -e '
  [.data.result[].values[] | {timestamp:(.[0] | tonumber), record:(.[1] | fromjson | .record)}]
  | sort_by(.timestamp) as $records
  | ($records | length) == 5 and
    ([$records[].record] == [
      "PARTNER_API_REQUEST", "ASYNC_ACK_RECEIVED", "CALLBACK_RECEIVED",
      "CALLBACK_PROCESSED", "CALLBACK_RESPONSE_SENT"
    ]) and
    ($records[0].timestamp < $records[1].timestamp and
     $records[1].timestamp < $records[2].timestamp and
     $records[2].timestamp < $records[3].timestamp and
     $records[3].timestamp < $records[4].timestamp)
' "$tmp_dir/timeline.json" >/dev/null
grafana_loki_query "$partner_a_user" "$partner_a_password" \
  "{service_name=\"synthetic-partner-service\"} | event_id=\"$detail_event_id\"" "$tmp_dir/detail.json"
jq -e '
  [.data.result[].values[][1] | fromjson][0] as $d |
  $d.record == "CALLBACK_RECEIVED" and $d.apiName == "application-callback" and
  $d.direction == "INBOUND_FROM_PARTNER" and $d.applicationId == "APP-A-001" and
  $d.loanId == "LOAN-A-001" and $d.partnerReferenceId == "PA-REF-001" and
  $d.callbackReferenceId == "PA-CB-001" and $d.payload.safeStatus == "accepted" and
  $d.binaryOmission.omitted == true and $d.binaryOmission.category == "BASE64" and
  $d.retry.attempt == 1 and $d.latencyMs == 125
' "$tmp_dir/detail.json" >/dev/null
assert_search_count "$partner_a_user" "$partner_a_password" event_id "$hostile_event_id" 0
grafana_loki_query "$partner_a_user" "$partner_a_password" '{service_name="synthetic-partner-service"}' "$tmp_dir/all-a.json"
if rg -q -i 'Authorization|eyJzeW50aGV0aWMi|654321|4111111111111111|\+1 202 555 0199|unsafe\.person@example\.test|SYNTHETIC-ACCOUNT-5432|SYNTHETIC-NATIONAL-6789|U1lOVEhFVElDX0ZPUkJJRERFTl9CQVNFNjQ' "$tmp_dir/all-a.json"; then
  echo "FAIL disclosure: a prohibited synthetic fixture reached the partner-visible Grafana result" >&2
  exit 1
fi
echo "PASS TIMELINE-DETAIL: distinct callback facts are ordered, partner-safe detail is usable, and prohibited content is absent"

stage "BYPASS" "Exercise datasource API, PromQL, header, label, dashboard-edit, and UID bypasses"
jq -n --argjson dashboard "$(jq '.dashboard' "$tmp_dir/dashboard.json")" '{dashboard:$dashboard,overwrite:true}' >"$tmp_dir/dashboard-write.json"
dashboard_write_status="$(grafana_status "$partner_a_user" "$partner_a_password" POST /api/dashboards/db "$tmp_dir/dashboard-write.json")"
require_denied_status "$dashboard_write_status" "saved dashboard edit"

query_from="$((base_seconds - 60))000"
query_to="$(( $(date +%s) + 60 ))000"
for spec in "$detail_event_id:APP-A-001:own" "$partner_b_event_id:APP-B-001:foreign"; do
  IFS=: read -r candidate_event_id candidate_application suffix <<<"$spec"
  jq -n --arg from "$query_from" --arg to "$query_to" --arg event_id "$candidate_event_id" \
    '{from:$from,to:$to,queries:[{refId:"A",datasource:{type:"loki",uid:"partner-loki"},expr:("{service_name=\"synthetic-partner-service\"} | event_id=\"" + $event_id + "\""),queryType:"range",maxLines:100,intervalMs:1000}]}' \
    >"$tmp_dir/ds-query-${suffix}.json"
  require_status 200 "$partner_a_user" "$partner_a_password" POST /api/ds/query \
    "$tmp_dir/ds-query-${suffix}.json" "$tmp_dir/ds-query-${suffix}-response.json"
  if [[ "$suffix" == "own" ]]; then
    rg -q --fixed-strings "$candidate_application" "$tmp_dir/ds-query-${suffix}-response.json"
  elif rg -q --fixed-strings "$candidate_application" "$tmp_dir/ds-query-${suffix}-response.json"; then
    echo "FAIL datasource query API: Partner A received Partner B data" >&2
    exit 1
  fi
done

jq -n --arg from "$query_from" --arg to "$query_to" \
  '{from:$from,to:$to,queries:[{refId:"A",datasource:{type:"prometheus",uid:"partner-prometheus"},expr:"partner_observability_http_interactions_total",instant:true,range:false,format:"time_series",intervalMs:15000,maxDataPoints:100}]}' \
  >"$tmp_dir/ds-query-prom.json"
ds_prom_status="$(curl -sS -o "$tmp_dir/ds-query-prom-response.json" -w '%{http_code}' \
  -u "$partner_a_user:$partner_a_password" -H 'Content-Type: application/json' \
  -H 'X-Partner-Slot: p002' -H 'X-Scope-OrgID: local-p002-91bc' \
  --data-binary "@$tmp_dir/ds-query-prom.json" "http://127.0.0.1:${grafana_port}/api/ds/query")"
[[ "$ds_prom_status" == "200" ]]
rg -q 'partner_slot[^[:alnum:]]+p001' "$tmp_dir/ds-query-prom-response.json"
if rg -q 'partner_slot[^[:alnum:]]+p002' "$tmp_dir/ds-query-prom-response.json"; then
  echo "FAIL datasource query API: Partner A received Partner B metrics" >&2
  exit 1
fi

conflict_expression='partner_observability_http_interactions_total{partner_slot="p002"}'
encoded_conflict="$(jq -rn --arg value "$conflict_expression" '$value | @uri')"
conflict_status="$(curl -sS -o "$tmp_dir/prom-conflict.json" -w '%{http_code}' -u "$partner_a_user:$partner_a_password" \
  "http://127.0.0.1:${grafana_port}/api/datasources/proxy/uid/partner-prometheus/api/v1/query?query=${encoded_conflict}")"
[[ "$conflict_status" == "400" ]]
regex_expression='partner_observability_http_interactions_total{partner_slot=~"p00.*"}'
encoded_regex="$(jq -rn --arg value "$regex_expression" '$value | @uri')"
regex_status="$(curl -sS -o "$tmp_dir/prom-regex.json" -w '%{http_code}' -u "$partner_a_user:$partner_a_password" \
  "http://127.0.0.1:${grafana_port}/api/datasources/proxy/uid/partner-prometheus/api/v1/query?query=${encoded_regex}")"
[[ "$regex_status" == "200" ]]
jq -e '.status == "success" and (.data.result | length) > 0 and all(.data.result[]; .metric.partner_slot == "p001")' \
  "$tmp_dir/prom-regex.json" >/dev/null

grafana_prom_query "$partner_a_user" "$partner_a_password" partner_observability_http_interactions_total "$tmp_dir/prom-a.json" \
  -H 'X-Partner-Slot: p002' -H 'X-Scope-OrgID: local-p002-91bc'
jq -e '.data.result | length > 0 and all(.[]; .metric.partner_slot == "p001")' "$tmp_dir/prom-a.json" >/dev/null
grafana_prom_query "$partner_b_user" "$partner_b_password" partner_observability_http_interactions_total "$tmp_dir/prom-b.json"
jq -e '.data.result | length > 0 and all(.[]; .metric.partner_slot == "p002")' "$tmp_dir/prom-b.json" >/dev/null

curl -fsS -u "$partner_a_user:$partner_a_password" \
  "http://127.0.0.1:${grafana_port}/api/datasources/proxy/uid/partner-prometheus/api/v1/label/partner_slot/values" >"$tmp_dir/slots-a.json"
curl -fsS -u "$partner_b_user:$partner_b_password" \
  "http://127.0.0.1:${grafana_port}/api/datasources/proxy/uid/partner-prometheus/api/v1/label/partner_slot/values" >"$tmp_dir/slots-b.json"
jq -e '.data == ["p001"]' "$tmp_dir/slots-a.json" >/dev/null
jq -e '.data == ["p002"]' "$tmp_dir/slots-b.json" >/dev/null

unsupported_status="$(curl -sS -o /dev/null -w '%{http_code}' -u "$partner_a_user:$partner_a_password" \
  "http://127.0.0.1:${grafana_port}/api/datasources/proxy/uid/partner-prometheus/api/v1/status/config")"
require_denied_status "$unsupported_status" "unsupported Prometheus endpoint"
echo "PASS BYPASS: datasource proxy attacks cannot change tenant/slot, widen PromQL, enumerate foreign slots, edit dashboards, or reach unsupported/internal paths"

stage "SLI" "Generate increasing synthetic counters and validate all dashboard SLI query families"
wait_for_prom_result "$partner_a_user" "$partner_a_password" 'sum(partner_observability_http_interactions_total)' "$tmp_dir/prom-initial.json"
awk 'BEGIN {OFS=" "} /^#/ || NF < 2 {print; next} {$NF = $NF + 10; print}' "$metrics_fixture_file" >"$tmp_dir/metrics-next.prom"
cp "$tmp_dir/metrics-next.prom" "$metrics_fixture_file"

sli_expressions=(
  'sum(partner_observability_http_interactions_total)'
  'avg(partner_observability:outbound_availability:ratio5m)'
  'avg(partner_observability:outbound_error_rate:ratio5m)'
  'avg(partner_observability:outbound_timeout_rate:ratio5m)'
  'avg(partner_observability:outbound_retry_rate:ratio5m)'
  'sum(partner_observability:outbound_throughput_per_second:rate5m)'
  'avg(partner_observability:outbound_latency_seconds:p50_5m)'
  'avg(partner_observability:outbound_latency_seconds:p95_5m)'
  'avg(partner_observability:outbound_latency_seconds:p99_5m)'
  'sum(partner_observability:callback_throughput_per_second:rate5m)'
  'avg(partner_observability:callback_processing_success:ratio5m)'
  'sum(partner_observability:callback_retry:rate5m)'
  'avg(partner_observability:callback_processing_latency_seconds:p50_5m)'
  'avg(partner_observability:callback_processing_latency_seconds:p95_5m)'
  'avg(partner_observability:callback_processing_latency_seconds:p99_5m)'
)
for expression in "${sli_expressions[@]}"; do
  wait_for_prom_result "$partner_a_user" "$partner_a_password" "$expression" "$tmp_dir/sli.json"
done
for expression in 'sum(partner_observability_http_interactions_total)' 'avg(partner_observability:outbound_latency_seconds:p95_5m)'; do
  wait_for_prom_result "$partner_b_user" "$partner_b_password" "$expression" "$tmp_dir/sli-b.json"
done
echo "PASS SLI: request count, availability/success, error, timeout, retry, throughput, p50/p95/p99, and callback SLI queries return partner-scoped values"

stage "AUDIT" "Confirm successful and denied gateway decisions are recorded without payload bodies"
compose logs --no-color tenant-gateway >"$tmp_dir/gateway-audit.log"
rg -q 'query-partner-a 400 GET /prometheus/api/v1/query' "$tmp_dir/gateway-audit.log"
rg -q 'query-partner-a 200 GET /(loki|prometheus)/api/v1/' "$tmp_dir/gateway-audit.log"
if rg -q 'APP-A-001|APP-B-001|PA-REF-001|PB-REF-001' "$tmp_dir/gateway-audit.log"; then
  echo "FAIL audit safety: transaction identifiers were written to gateway audit logs" >&2
  exit 1
fi
echo "PASS AUDIT: gateway audit records actor/status/path only and omit query values and payloads"

failed=0
echo "PASS: local M7 Grafana authentication, organization, fixed datasource, dashboard, isolation, search, timeline, detail, SLI, and bypass boundary"
