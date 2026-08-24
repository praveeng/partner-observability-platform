#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

for command_name in curl docker jq rg; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "ERROR: required command is unavailable: $command_name" >&2
    exit 1
  fi
done

project_name="partner-observability-m5-${PPID}-$$"
port_base=$((22000 + ($$ % 8000)))
otlp_port="$port_base"
query_port=$((port_base + 1))
metrics_port=$((port_base + 2))
tmp_dir="$(mktemp -d /tmp/partner-observability-m5.XXXXXX)"
compose_file="$repo_root/docker/compose.yml"
failed=1

compose() {
  LOCAL_OTLP_PORT="$otlp_port" \
    LOCAL_QUERY_PORT="$query_port" \
    LOCAL_ALLOY_METRICS_PORT="$metrics_port" \
    GRAFANA_ADMIN_PASSWORD="unused-${project_name}" \
    GRAFANA_SECRET_KEY="unused-secret-key-${project_name}" \
    GRAFANA_PARTNER_A_QUERY_PASSWORD="unused-a-${project_name}" \
    GRAFANA_PARTNER_B_QUERY_PASSWORD="unused-b-${project_name}" \
    docker compose -p "$project_name" -f "$compose_file" "$@"
}

cleanup() {
  if (( failed != 0 )); then
    echo "Local data-plane test failed; component logs follow without request bodies." >&2
    compose logs --no-color --tail=120 alloy loki tenant-gateway >&2 || true
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

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
  local application_id="${11:-}"
  local callback_reference_id="${12:-}"
  local original_correlation_id="${13:-}"
  local partner_reference_id="${14:-}"
  local external_transaction_id="${15:-}"
  local loan_id="${16:-}"
  local request_id="${17:-}"
  local correlation_id="${18:-}"
  local schema_version="${19:-2}"
  local interaction_id callback_attempt_id timestamp_nanos
  interaction_id="$(new_uuid)"
  callback_attempt_id="$(new_uuid)"
  timestamp_nanos="$(date +%s%N)"

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
    --arg callback_reference_id "$callback_reference_id" \
    --arg original_correlation_id "$original_correlation_id" \
    --arg partner_reference_id "$partner_reference_id" \
    --arg external_transaction_id "$external_transaction_id" \
    --arg loan_id "$loan_id" \
    --arg request_id "$request_id" \
    --arg correlation_id "$correlation_id" \
    --arg schema_version "$schema_version" \
    --arg interaction_id "$interaction_id" \
    --arg callback_attempt_id "$callback_attempt_id" \
    --arg timestamp_nanos "$timestamp_nanos" \
    '{
      resourceLogs: [{
        resource: {attributes: [
          {key: "service.name", value: {stringValue: "synthetic-partner-service"}},
          {key: "market", value: {stringValue: "LOCAL"}},
          {key: "deployment.environment", value: {stringValue: "LOCAL_SYNTHETIC"}},
          {key: "unapproved.resource", value: {stringValue: "must-be-removed"}}
        ]},
        scopeLogs: [{
          scope: {name: "partner-observability-sdk", version: "0.1.0"},
          logRecords: [{
            timeUnixNano: $timestamp_nanos,
            observedTimeUnixNano: $timestamp_nanos,
            severityText: "INFO",
            body: {stringValue: $body},
            attributes: ([
              {key: "schema.version", value: {intValue: $schema_version}},
              {key: "partner.key", value: {stringValue: $partner_key}},
              {key: "event.type", value: {stringValue: $event_type}},
              {key: "event.domain", value: {stringValue: $event_domain}},
              {key: "direction", value: {stringValue: $direction}},
              {key: "outcome", value: {stringValue: $outcome}},
              {key: "severity", value: {stringValue: "INFO"}},
              {key: "event.id", value: {stringValue: $event_id}},
              {key: "interaction.id", value: {stringValue: $interaction_id}},
              {key: "correlation.profile.id", value: {stringValue: "synthetic-async-journey"}},
              {key: "api.id", value: {stringValue: $api_id}},
              {key: "service.version", value: {stringValue: "0.1.0"}},
              {key: "unknown.metadata", value: {stringValue: "must-be-removed"}}
            ]
            + (if $timeline_stage == "" then [] else [{key: "timeline.stage", value: {stringValue: $timeline_stage}}] end)
            + (if ($event_type | startswith("callback_")) then [{key: "callback.attempt.id", value: {stringValue: $callback_attempt_id}}] else [] end)
            + (if $application_id == "" then [] else [{key: "application.id", value: {stringValue: $application_id}}] end)
            + (if $callback_reference_id == "" then [] else [{key: "callback.reference.id", value: {stringValue: $callback_reference_id}}] end)
            + (if $original_correlation_id == "" then [] else [{key: "original.correlation.id", value: {stringValue: $original_correlation_id}}] end)
            + (if $partner_reference_id == "" then [] else [{key: "partner.reference.id", value: {stringValue: $partner_reference_id}}] end)
            + (if $external_transaction_id == "" then [] else [{key: "external.transaction.id", value: {stringValue: $external_transaction_id}}] end)
            + (if $loan_id == "" then [] else [{key: "loan.id", value: {stringValue: $loan_id}}] end)
            + (if $request_id == "" then [] else [{key: "request.id", value: {stringValue: $request_id}}] end)
            + (if $correlation_id == "" then [] else [{key: "correlation.id", value: {stringValue: $correlation_id}}] end))
          }]
        }]
      }]
    }' >"$output_file"
}

post_event() {
  local user="$1"
  local password="$2"
  local route="$3"
  local payload_file="$4"
  shift 4
  curl -sS -o "$tmp_dir/post-response.json" -w '%{http_code}' \
    -u "$user:$password" \
    -H 'Content-Type: application/json' \
    -H "X-Partner-Route: $route" \
    "$@" \
    --data-binary "@$payload_file" \
    "http://127.0.0.1:${otlp_port}/v1/logs"
}

query_to_file() {
  local user="$1"
  local password="$2"
  local logql="$3"
  local output_file="$4"
  shift 4
  curl -fsS \
    -u "$user:$password" \
    "$@" \
    --get \
    --data-urlencode "query=$logql" \
    --data-urlencode 'limit=500' \
    --data-urlencode 'since=10m' \
    "http://127.0.0.1:${query_port}/loki/api/v1/query_range" >"$output_file"
}

result_count() {
  jq '[.data.result[].values[]] | length' "$1"
}

wait_for_event() {
  local user="$1"
  local password="$2"
  local event_id="$3"
  local output_file="$4"
  local attempt
  for attempt in {1..40}; do
    query_to_file "$user" "$password" \
      "{service_name=\"synthetic-partner-service\"} | event_id=\"$event_id\"" "$output_file"
    if [[ "$(result_count "$output_file")" == "1" ]]; then
      return 0
    fi
    sleep 0.25
  done
  echo "ERROR: expected synthetic event was not queryable" >&2
  return 1
}

assert_event_count() {
  local user="$1"
  local password="$2"
  local event_id="$3"
  local expected="$4"
  local output_file="$tmp_dir/query-${user}-${event_id}.json"
  query_to_file "$user" "$password" \
    "{service_name=\"synthetic-partner-service\"} | event_id=\"$event_id\"" "$output_file"
  local actual
  actual="$(result_count "$output_file")"
  if [[ "$actual" != "$expected" ]]; then
    echo "ERROR: fixed-tenant event assertion failed for $user (expected $expected, got $actual)" >&2
    return 1
  fi
}

assert_post_status() {
  local expected="$1"
  shift
  local actual
  actual="$(post_event "$@")"
  if [[ "$actual" != "$expected" ]]; then
    echo "ERROR: ingress status assertion failed (expected $expected, got $actual)" >&2
    return 1
  fi
}

event_file="$tmp_dir/event.json"
run_tag="m5-$PPID-$$"
shared_application_id="shared-app-$run_tag"
shared_callback_reference_id="shared-callback-$run_tag"
shared_original_correlation_id="original-correlation-$run_tag"
shared_partner_reference_id="partner-reference-$run_tag"
shared_external_transaction_id="external-transaction-$run_tag"
shared_loan_id="loan-$run_tag"
shared_request_id="request-$run_tag"

echo "Validating Compose, Alloy, Loki, and gateway configuration."
compose config >/dev/null
compose config --format json >"$tmp_dir/compose.json"
jq -e '
  (.services.loki.ports == null) and
  all(.services.alloy.ports[]; .host_ip == "127.0.0.1") and
  all(.services["tenant-gateway"].ports[]; .host_ip == "127.0.0.1") and
  .networks.backend.internal == true
' "$tmp_dir/compose.json" >/dev/null
alloy_image="$(jq -er '.services.alloy.image' "$tmp_dir/compose.json")"
loki_image="$(jq -er '.services.loki.image' "$tmp_dir/compose.json")"
gateway_image="$(jq -er '.services["tenant-gateway"].image' "$tmp_dir/compose.json")"
docker run --rm -v "$repo_root/alloy/local-config.alloy:/etc/alloy/config.alloy:ro" \
  "$alloy_image" validate /etc/alloy/config.alloy
docker run --rm -v "$repo_root/loki/local-config.yaml:/etc/loki/local-config.yaml:ro" \
  "$loki_image" -config.file=/etc/loki/local-config.yaml -verify-config=true
docker run --rm \
  --add-host loki:127.0.0.1 \
  -v "$repo_root/docker/nginx/local-gateway.conf:/etc/nginx/nginx.conf:ro" \
  -v "$repo_root/docker/nginx/local-synthetic.htpasswd:/etc/nginx/local-synthetic.htpasswd:ro" \
  "$gateway_image" nginx -t

echo "Starting isolated LOCAL_SYNTHETIC Alloy/Loki stack."
compose up -d --wait

# Exercise every schema-2 record type and every requested callback/async lifecycle concept.
declare -A event_ids
record_specs=(
  "outbound_api_request|API|OUTBOUND_TO_PARTNER|UNKNOWN|ASYNC_REQUEST_SENT|submit-application"
  "outbound_api_response|API|OUTBOUND_TO_PARTNER|SUCCESS||submit-application"
  "async_acknowledgement|ASYNC|OUTBOUND_TO_PARTNER|SUCCESS|ASYNC_ACK_RECEIVED|submit-application"
  "partner_business_event|BUSINESS|OUTBOUND_TO_PARTNER|SUCCESS||journey-updated"
  "callback_request|CALLBACK|INBOUND_FROM_PARTNER|UNKNOWN|CALLBACK_RECEIVED|application-callback"
  "callback_processing_event|CALLBACK|INBOUND_FROM_PARTNER|SUCCESS|CALLBACK_PROCESSED|application-callback"
  "callback_response|CALLBACK|INBOUND_FROM_PARTNER|SUCCESS|CALLBACK_RESPONSE_SENT|application-callback"
  "callback_request|CALLBACK|INBOUND_FROM_PARTNER|UNKNOWN|CALLBACK_RETRY_RECEIVED|application-callback"
)

for spec in "${record_specs[@]}"; do
  IFS='|' read -r event_type event_domain direction outcome timeline_stage api_id <<<"$spec"
  event_id="$(new_uuid)"
  event_ids["$event_type-$timeline_stage"]="$event_id"
  body="$(jq -cn --arg record "$event_type" --arg stage "$timeline_stage" --arg canary "partner-a-$run_tag" '{record:$record,stage:$stage,canary:$canary,payload:{safeStatus:"accepted"}}')"
  make_event "$event_file" "$event_id" partner-a "$event_type" "$event_domain" "$direction" "$outcome" "$timeline_stage" "$api_id" "$body" \
    "$shared_application_id" "$shared_callback_reference_id" "$shared_original_correlation_id" \
    "$shared_partner_reference_id" "$shared_external_transaction_id" "$shared_loan_id" "$shared_request_id"
  assert_post_status 200 sdk-partner-a local-synthetic-sdk-a partner-a "$event_file"
done

# The architecture accepts N-1 SDK names during migration. These are the
# architecture-approved wire equivalents of PARTNER_API_REQUEST,
# PARTNER_API_RESPONSE, and PARTNER_EVENT.
legacy_correlation_id="legacy-correlation-$run_tag"
legacy_specs=(
  "partner_api_request|API|UNKNOWN"
  "partner_api_response|API|SUCCESS"
  "partner_event|BUSINESS|SUCCESS"
)
for spec in "${legacy_specs[@]}"; do
  IFS='|' read -r event_type event_domain outcome <<<"$spec"
  event_id="$(new_uuid)"
  body="$(jq -cn --arg record "$event_type" --arg canary "partner-a-legacy-$run_tag" '{record:$record,canary:$canary}')"
  make_event "$event_file" "$event_id" partner-a "$event_type" "$event_domain" OUTBOUND_TO_PARTNER "$outcome" "" legacy-api "$body" \
    "" "" "" "" "" "" "" "$legacy_correlation_id" 1
  assert_post_status 200 sdk-partner-a local-synthetic-sdk-a partner-a "$event_file"
done

partner_a_outbound_id="${event_ids[outbound_api_request-ASYNC_REQUEST_SENT]}"
partner_a_callback_id="${event_ids[callback_request-CALLBACK_RECEIVED]}"
partner_a_barrier_id="${event_ids[callback_request-CALLBACK_RETRY_RECEIVED]}"
wait_for_event query-partner-a local-synthetic-query-a "$partner_a_barrier_id" "$tmp_dir/barrier-a.json"

# Partner B deliberately collides on application and callback reference identifiers.
partner_b_outbound_id="$(new_uuid)"
body="$(jq -cn --arg canary "partner-b-$run_tag" '{record:"outbound_api_request",canary:$canary,payload:{safeStatus:"accepted"}}')"
make_event "$event_file" "$partner_b_outbound_id" partner-b outbound_api_request API OUTBOUND_TO_PARTNER UNKNOWN ASYNC_REQUEST_SENT submit-application "$body" \
  "$shared_application_id" "$shared_callback_reference_id" "$shared_original_correlation_id" "$shared_partner_reference_id" "$shared_external_transaction_id" "$shared_loan_id" "$shared_request_id"
assert_post_status 200 sdk-partner-b local-synthetic-sdk-b partner-b "$event_file"

partner_b_callback_id="$(new_uuid)"
body="$(jq -cn --arg canary "partner-b-$run_tag" '{record:"callback_request",canary:$canary,payload:{safeStatus:"accepted"}}')"
make_event "$event_file" "$partner_b_callback_id" partner-b callback_request CALLBACK INBOUND_FROM_PARTNER UNKNOWN CALLBACK_RECEIVED application-callback "$body" \
  "$shared_application_id" "$shared_callback_reference_id" "$shared_original_correlation_id" "$shared_partner_reference_id" "$shared_external_transaction_id" "$shared_loan_id" "$shared_request_id"
assert_post_status 200 sdk-partner-b local-synthetic-sdk-b partner-b "$event_file"
wait_for_event query-partner-b local-synthetic-query-b "$partner_b_callback_id" "$tmp_dir/barrier-b.json"

# Partner C proves the third synthetic route is configured and has no fallback behavior.
partner_c_id="$(new_uuid)"
body="$(jq -cn --arg canary "partner-c-$run_tag" '{record:"partner_business_event",canary:$canary}')"
make_event "$event_file" "$partner_c_id" partner-c partner_business_event BUSINESS OUTBOUND_TO_PARTNER SUCCESS "" journey-updated "$body"
assert_post_status 200 sdk-partner-c local-synthetic-sdk-c partner-c "$event_file"
wait_for_event query-partner-c local-synthetic-query-c "$partner_c_id" "$tmp_dir/barrier-c.json"

echo "Proving outbound, callback, identifier-collision, and fixed-query tenant isolation."
assert_event_count query-partner-a local-synthetic-query-a "$partner_a_outbound_id" 1
assert_event_count query-partner-b local-synthetic-query-b "$partner_a_outbound_id" 0
assert_event_count query-partner-a local-synthetic-query-a "$partner_a_callback_id" 1
assert_event_count query-partner-b local-synthetic-query-b "$partner_a_callback_id" 0
assert_event_count query-partner-b local-synthetic-query-b "$partner_b_outbound_id" 1
assert_event_count query-partner-a local-synthetic-query-a "$partner_b_outbound_id" 0
assert_event_count query-partner-b local-synthetic-query-b "$partner_b_callback_id" 1
assert_event_count query-partner-a local-synthetic-query-a "$partner_b_callback_id" 0

query_to_file query-partner-a local-synthetic-query-a \
  "{service_name=\"synthetic-partner-service\"} | application_id=\"$shared_application_id\"" "$tmp_dir/shared-app-a.json"
query_to_file query-partner-b local-synthetic-query-b \
  "{service_name=\"synthetic-partner-service\"} | application_id=\"$shared_application_id\"" "$tmp_dir/shared-app-b.json"
jq -e --arg own "partner-a-$run_tag" --arg other "partner-b-$run_tag" \
  '([.data.result[].values[][1]] | length) > 0 and all(.data.result[].values[][1]; contains($own) and (contains($other) | not))' \
  "$tmp_dir/shared-app-a.json" >/dev/null
jq -e --arg own "partner-b-$run_tag" --arg other "partner-a-$run_tag" \
  '([.data.result[].values[][1]] | length) > 0 and all(.data.result[].values[][1]; contains($own) and (contains($other) | not))' \
  "$tmp_dir/shared-app-b.json" >/dev/null

query_to_file query-partner-a local-synthetic-query-a \
  "{service_name=\"synthetic-partner-service\"} | callback_reference_id=\"$shared_callback_reference_id\"" "$tmp_dir/shared-callback-a.json"
query_to_file query-partner-b local-synthetic-query-b \
  "{service_name=\"synthetic-partner-service\"} | callback_reference_id=\"$shared_callback_reference_id\"" "$tmp_dir/shared-callback-b.json"
jq -e --arg own "partner-a-$run_tag" --arg other "partner-b-$run_tag" \
  '([.data.result[].values[][1]] | length) > 0 and all(.data.result[].values[][1]; contains($own) and (contains($other) | not))' \
  "$tmp_dir/shared-callback-a.json" >/dev/null
jq -e --arg own "partner-b-$run_tag" --arg other "partner-a-$run_tag" \
  '([.data.result[].values[][1]] | length) > 0 and all(.data.result[].values[][1]; contains($own) and (contains($other) | not))' \
  "$tmp_dir/shared-callback-b.json" >/dev/null

# The same fixed-tenant gateway overwrites client tenant headers on queries.
query_to_file query-partner-b local-synthetic-query-b \
  "{service_name=\"synthetic-partner-service\"} | event_id=\"$partner_a_outbound_id\"" \
  "$tmp_dir/query-header-spoof.json" -H 'X-Scope-OrgID: local-p001-7f3a'
[[ "$(result_count "$tmp_dir/query-header-spoof.json")" == "0" ]]

missing_query_status="$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${query_port}/loki/api/v1/labels")"
if [[ "$missing_query_status" != "403" ]]; then
  echo "ERROR: missing query identity did not fail closed with 403 (got $missing_query_status)" >&2
  exit 1
fi
unmapped_query_status="$(curl -sS -o /dev/null -w '%{http_code}' -u sdk-partner-a:local-synthetic-sdk-a "http://127.0.0.1:${query_port}/loki/api/v1/labels")"
if [[ "$unmapped_query_status" != "403" ]]; then
  echo "ERROR: unmapped query identity was not rejected with 403 (got $unmapped_query_status)" >&2
  exit 1
fi

echo "Proving missing, conflicting, body-spoofed, and tenant-header-spoofed ingest routes fail closed."
missing_route_id="$(new_uuid)"
body='{"record":"callback_request","canary":"missing-route"}'
make_event "$event_file" "$missing_route_id" partner-a callback_request CALLBACK INBOUND_FROM_PARTNER UNKNOWN CALLBACK_RECEIVED application-callback "$body"
assert_post_status 403 sdk-partner-a local-synthetic-sdk-a "" "$event_file"

wrong_route_id="$(new_uuid)"
make_event "$event_file" "$wrong_route_id" partner-a outbound_api_request API OUTBOUND_TO_PARTNER UNKNOWN ASYNC_REQUEST_SENT submit-application '{"record":"outbound_api_request"}'
assert_post_status 403 sdk-partner-b local-synthetic-sdk-b partner-a "$event_file"

body_spoof_id="$(new_uuid)"
body="$(jq -cn --arg canary "partner-a-body-spoof-$run_tag" '{record:"callback_request",partnerId:"partner-b",canary:$canary}')"
make_event "$event_file" "$body_spoof_id" partner-a callback_request CALLBACK INBOUND_FROM_PARTNER UNKNOWN CALLBACK_RECEIVED application-callback "$body"
assert_post_status 200 sdk-partner-a local-synthetic-sdk-a partner-a "$event_file"
wait_for_event query-partner-a local-synthetic-query-a "$body_spoof_id" "$tmp_dir/body-spoof-a.json"
assert_event_count query-partner-b local-synthetic-query-b "$body_spoof_id" 0
jq -e 'all(.data.result[].values[][1]; (contains("partnerId") or contains("partner-b")) | not)' "$tmp_dir/body-spoof-a.json" >/dev/null

header_spoof_id="$(new_uuid)"
body="$(jq -cn --arg canary "partner-a-header-spoof-$run_tag" '{record:"outbound_api_request",canary:$canary}')"
make_event "$event_file" "$header_spoof_id" partner-a outbound_api_request API OUTBOUND_TO_PARTNER UNKNOWN ASYNC_REQUEST_SENT submit-application "$body"
assert_post_status 200 sdk-partner-a local-synthetic-sdk-a partner-a "$event_file" \
  -H 'X-Scope-OrgID: local-p002-91bc' -H 'x-scope-orgid: local-p003-c4d2'
wait_for_event query-partner-a local-synthetic-query-a "$header_spoof_id" "$tmp_dir/header-spoof-a.json"
assert_event_count query-partner-b local-synthetic-query-b "$header_spoof_id" 0
assert_event_count query-partner-c local-synthetic-query-c "$header_spoof_id" 0

for rejected_id in "$missing_route_id" "$wrong_route_id"; do
  assert_event_count query-partner-a local-synthetic-query-a "$rejected_id" 0
  assert_event_count query-partner-b local-synthetic-query-b "$rejected_id" 0
  assert_event_count query-partner-c local-synthetic-query-c "$rejected_id" 0
done

echo "Proving Alloy second-stage credential/Base64 drops, PII masking, and metadata allowlists."
credential_event_id="$(new_uuid)"
body='{"record":"partner_business_event","payload":{"Authorization":"Bearer LOCAL_SYNTHETIC_CREDENTIAL_DO_NOT_USE"}}'
make_event "$event_file" "$credential_event_id" partner-a partner_business_event BUSINESS OUTBOUND_TO_PARTNER SUCCESS "" journey-updated "$body"
assert_post_status 200 sdk-partner-a local-synthetic-sdk-a partner-a "$event_file"

base64_event_id="$(new_uuid)"
body='{"record":"callback_request","payload":{"document":"U1lOVEhFVElDX0JJTkFSWV9ET0NVTUVOVF9QQVlMT0FEXzAxMjM0NTY3ODg5"}}'
make_event "$event_file" "$base64_event_id" partner-a callback_request CALLBACK INBOUND_FROM_PARTNER UNKNOWN CALLBACK_RECEIVED application-callback "$body"
assert_post_status 200 sdk-partner-a local-synthetic-sdk-a partner-a "$event_file"

invalid_record_id="$(new_uuid)"
make_event "$event_file" "$invalid_record_id" partner-a unapproved_record BUSINESS OUTBOUND_TO_PARTNER SUCCESS "" journey-updated '{"record":"unapproved_record"}'
assert_post_status 200 sdk-partner-a local-synthetic-sdk-a partner-a "$event_file"

masked_event_id="$(new_uuid)"
body='{"record":"partner_business_event","phone":"+1 202 555 0199","email":"synthetic.person@example.test","bankAccount":"SYNTHETIC-ACCOUNT-5432","nationalId":"SYNTHETIC-NATIONAL-6789","address":"10 Synthetic Fixture Street","safe":"retained"}'
make_event "$event_file" "$masked_event_id" partner-a partner_business_event BUSINESS OUTBOUND_TO_PARTNER SUCCESS "" journey-updated "$body"
assert_post_status 200 sdk-partner-a local-synthetic-sdk-a partner-a "$event_file"

safety_barrier_id="$(new_uuid)"
make_event "$event_file" "$safety_barrier_id" partner-a partner_business_event BUSINESS OUTBOUND_TO_PARTNER SUCCESS "" journey-updated '{"record":"partner_business_event","barrier":"safe"}'
assert_post_status 200 sdk-partner-a local-synthetic-sdk-a partner-a "$event_file"
wait_for_event query-partner-a local-synthetic-query-a "$safety_barrier_id" "$tmp_dir/safety-barrier.json"
assert_event_count query-partner-a local-synthetic-query-a "$credential_event_id" 0
assert_event_count query-partner-a local-synthetic-query-a "$base64_event_id" 0
assert_event_count query-partner-a local-synthetic-query-a "$invalid_record_id" 0
wait_for_event query-partner-a local-synthetic-query-a "$masked_event_id" "$tmp_dir/masked.json"
jq -e 'all(.data.result[].values[][1];
  (contains("202 555 0199") or contains("synthetic.person@example.test") or
   contains("SYNTHETIC-ACCOUNT-5432") or contains("SYNTHETIC-NATIONAL-6789") or
   contains("10 Synthetic Fixture Street")) | not)' "$tmp_dir/masked.json" >/dev/null
jq -e 'any(.data.result[].values[][1];
  contains("[MASKED_PHONE]") and contains("[MASKED_EMAIL]") and
  contains("[MASKED_ACCOUNT]") and contains("[MASKED_NATIONAL_ID]") and
  contains("[MASKED_ADDRESS]"))' "$tmp_dir/masked.json" >/dev/null

query_to_file query-partner-a local-synthetic-query-a \
  '{service_name="synthetic-partner-service"}' "$tmp_dir/all-a.json"
jq -e 'all(.data.result[].values[][1]; (contains("LOCAL_SYNTHETIC_CREDENTIAL_DO_NOT_USE") or contains("U1lOVEhFVElDX0JJTkFSWV9ET0NVTUVOVF9QQVlMT0FEXzAxMjM0NTY3ODg5")) | not)' \
  "$tmp_dir/all-a.json" >/dev/null

# Query returned stream maps include structured metadata. Unknown and routing metadata must be absent.
jq -e 'all(.data.result[].stream; (has("partner_key") or has("tenant_route_id") or has("unknown_metadata") or has("unapproved_resource")) | not)' \
  "$tmp_dir/all-a.json" >/dev/null

echo "Proving typed transaction search and common-key journey retrieval."
for metadata_query in \
  "application_id=$shared_application_id" \
  "loan_id=$shared_loan_id" \
  "original_correlation_id=$shared_original_correlation_id" \
  "request_id=$shared_request_id" \
  "partner_reference_id=$shared_partner_reference_id" \
  "callback_reference_id=$shared_callback_reference_id" \
  "external_transaction_id=$shared_external_transaction_id"; do
  metadata_name="${metadata_query%%=*}"
  metadata_value="${metadata_query#*=}"
  query_to_file query-partner-a local-synthetic-query-a \
    "{service_name=\"synthetic-partner-service\"} | ${metadata_name}=\"${metadata_value}\"" \
    "$tmp_dir/search-${metadata_name}.json"
  if [[ "$(result_count "$tmp_dir/search-${metadata_name}.json")" == "0" ]]; then
    echo "ERROR: structured-metadata transaction search failed for $metadata_name" >&2
    exit 1
  fi
done

query_to_file query-partner-a local-synthetic-query-a \
  "{service_name=\"synthetic-partner-service\"} | correlation_id=\"$legacy_correlation_id\"" \
  "$tmp_dir/legacy-correlation.json"
jq -e '
  ([.data.result[].stream.event_type] | index("partner_api_request")) != null and
  ([.data.result[].stream.event_type] | index("partner_api_response")) != null and
  ([.data.result[].stream.event_type] | index("partner_event")) != null
' "$tmp_dir/legacy-correlation.json" >/dev/null

query_to_file query-partner-a local-synthetic-query-a \
  "{service_name=\"synthetic-partner-service\"} | application_id=\"$shared_application_id\"" \
  "$tmp_dir/journey.json"
jq -e '
  ([.data.result[].stream.event_type] | index("outbound_api_request")) != null and
  ([.data.result[].stream.event_type] | index("async_acknowledgement")) != null and
  ([.data.result[].stream.event_type] | index("callback_request")) != null and
  ([.data.result[].stream.event_type] | index("callback_processing_event")) != null and
  ([.data.result[].stream.event_type] | index("callback_response")) != null
' "$tmp_dir/journey.json" >/dev/null

echo "Proving indexed labels remain the exact architecture allowlist."
curl -fsS -u query-partner-a:local-synthetic-query-a --get \
  --data-urlencode 'match[]={service_name="synthetic-partner-service"}' \
  --data-urlencode 'since=10m' \
  "http://127.0.0.1:${query_port}/loki/api/v1/series" >"$tmp_dir/series.json"
jq -e '
  .status == "success" and (.data | length) > 0 and
  all(.data[]; (keys | sort) == (["deployment_environment","direction","event_domain","event_type","market","outcome","service_name","severity"] | sort))
' "$tmp_dir/series.json" >/dev/null

echo "Proving Alloy exposes bounded receiver/exporter/queue self-metrics."
curl -fsS "http://127.0.0.1:${metrics_port}/metrics" >"$tmp_dir/alloy-metrics.txt"
for metric_name in \
  otelcol_receiver_accepted_log_records_total \
  otelcol_exporter_sent_log_records_total \
  otelcol_exporter_queue_capacity \
  otelcol_exporter_queue_size; do
  if ! rg -q "^${metric_name}(\\{| )" "$tmp_dir/alloy-metrics.txt"; then
    echo "ERROR: expected Alloy self-metric is absent: $metric_name" >&2
    exit 1
  fi
done

failed=0
echo "PASS: local Alloy/Loki schema, safety, tenant-routing, identifier-search, and journey integration checks."
