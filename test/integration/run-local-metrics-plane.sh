#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

for command_name in curl docker jq rg; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "ERROR: required command is unavailable: $command_name" >&2
    exit 1
  }
done

project_name="partner-observability-m6-${PPID}-$$"
port_base=$((25000 + ($$ % 5000)))
prometheus_port="$port_base"
alloy_port=$((port_base + 1))
compose_file="$repo_root/docker/compose.yml"
tmp_dir="$(mktemp -d /tmp/partner-observability-m6.XXXXXX)"
failed=1

compose() {
  LOCAL_PROMETHEUS_PORT="$prometheus_port" \
    LOCAL_ALLOY_METRICS_PORT="$alloy_port" \
    docker compose -p "$project_name" -f "$compose_file" "$@"
}

cleanup() {
  if (( failed != 0 )); then
    echo "Local metrics-plane test failed; bounded component logs follow." >&2
    compose logs --no-color --tail=120 alloy prometheus metrics-fixture >&2 || true
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

prom_query() {
  local expression="$1"
  local output="$2"
  local encoded
  encoded="$(jq -rn --arg value "$expression" '$value | @uri')"
  compose exec -T prometheus wget -qO- \
    "http://127.0.0.1:9090/api/v1/query?query=${encoded}" >"$output"
}

prom_get() {
  local url="$1"
  local output="$2"
  compose exec -T prometheus wget -qO- "$url" >"$output"
}

wait_for_query() {
  local expression="$1"
  local output="$2"
  for _ in $(seq 1 30); do
    if prom_query "$expression" "$output" 2>/dev/null \
        && jq -e '.status == "success" and (.data.result | length) > 0' "$output" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "ERROR: Prometheus query did not produce a sample: $expression" >&2
  return 1
}

echo "Validating Compose, Alloy, Prometheus, and recording-rule configuration."
compose config --format json >"$tmp_dir/compose.json"
jq -e '
  all(.services.prometheus.ports[]; .host_ip == "127.0.0.1") and
  (.services["metrics-fixture"].ports == null) and
  (.services.prometheus.networks | keys == ["backend"]) and
  .networks.backend.internal == true and
  any(.services.prometheus.command[]; . == "--web.enable-remote-write-receiver") and
  any(.services.prometheus.command[]; . == "--storage.tsdb.retention.time=16d") and
  any(.services.prometheus.command[]; . == "--storage.tsdb.retention.size=1GB") and
  (all(.services.prometheus.command[]; . != "--web.enable-admin-api" and . != "--web.enable-lifecycle"))
' "$tmp_dir/compose.json" >/dev/null
docker run --rm --entrypoint /bin/promtool \
  -v "$repo_root/prometheus/local-config.yml:/etc/prometheus/prometheus.yml:ro" \
  -v "$repo_root/prometheus/partner-recording-rules.yml:/etc/prometheus/partner-recording-rules.yml:ro" \
  prom/prometheus:v3.12.0 check config /etc/prometheus/prometheus.yml
docker run --rm -v "$repo_root/alloy/local-config.alloy:/etc/alloy/config.alloy:ro" \
  grafana/alloy:v1.18.0 validate /etc/alloy/config.alloy

echo "Starting scrape -> relabel -> bounded remote-write -> Prometheus flow."
compose up -d --wait prometheus metrics-fixture alloy

wait_for_query 'partner_observability_http_interactions_total{partner_slot="p001"}' "$tmp_dir/p001.json"
wait_for_query 'partner_observability_callback_deliveries_total{partner_slot="p002"}' "$tmp_dir/p002.json"
wait_for_query 'partner_observability_callback_deliveries_total{partner_slot="p003"}' "$tmp_dir/p003.json"

echo "Proving trusted deployment labels overwrite target values and unsafe labels/series are removed."
jq -e '
  .data.result | length == 1 and
  .[0].metric.market == "LOCAL" and
  .[0].metric.environment == "LOCAL_SYNTHETIC" and
  .[0].metric.service == "synthetic-partner-service" and
  .[0].metric.partner_slot == "p001" and
  (.[0].metric | has("applicationId") | not)
' "$tmp_dir/p001.json" >/dev/null
prom_query 'partner_observability_http_interactions_total{partner_slot="p999"}' "$tmp_dir/p999.json"
jq -e '.data.result | length == 0' "$tmp_dir/p999.json" >/dev/null
prom_query 'unsafe_metric' "$tmp_dir/unsafe.json"
jq -e '.data.result | length == 0' "$tmp_dir/unsafe.json" >/dev/null

echo "Proving outbound, retry, histogram, callback, and A/B/C partner-slot series are queryable."
for expression in \
  'partner_observability_http_interactions_total{partner_slot="p001",result="http_2xx"}' \
  'partner_observability_http_interactions_total{partner_slot="p002",result="timeout"}' \
  'partner_observability_outbound_retries_total{partner_slot="p001"}' \
  'partner_observability_http_duration_seconds_bucket{partner_slot="p001",le="0.5"}' \
  'partner_observability_callback_deliveries_total{partner_slot="p001",delivery_class="retry"}' \
  'partner_observability_callback_deliveries_total{partner_slot="p002",delivery_class="duplicate"}' \
  'partner_observability_callback_processing_total{partner_slot="p001",outcome="success"}' \
  'partner_observability_callback_response_total{partner_slot="p001",status_class="2xx"}' \
  'partner_observability_callback_response_total{partner_slot="p001",status_class="4xx"}' \
  'partner_observability_callback_response_total{partner_slot="p001",status_class="5xx"}'; do
  wait_for_query "$expression" "$tmp_dir/query.json"
done

echo "Proving recording rules, retention flags, and Alloy scrape/write self-metrics are exposed."
prom_get "http://127.0.0.1:9090/api/v1/rules" "$tmp_dir/rules.json"
jq -e '[.data.groups[].rules[].name] | index("partner_observability:outbound_latency_seconds:p99_5m") != null and index("partner_observability:callback_processing_latency_seconds:p99_5m") != null' \
  "$tmp_dir/rules.json" >/dev/null
prom_get "http://127.0.0.1:9090/api/v1/status/flags" "$tmp_dir/flags.json"
jq -e '.data["storage.tsdb.retention.time"] == "16d" and .data["storage.tsdb.retention.size"] == "1GiB" and .data["web.enable-admin-api"] == "false" and .data["web.enable-lifecycle"] == "false"' \
  "$tmp_dir/flags.json" >/dev/null
prom_get "http://alloy:12345/metrics" "$tmp_dir/alloy-metrics.txt"
for metric_name in prometheus_scrape_targets_gauge alloy_prometheus_relabel_metrics_processed prometheus_remote_storage_samples_total; do
  rg -q "^${metric_name}(\\{| )" "$tmp_dir/alloy-metrics.txt" || {
    echo "ERROR: expected Alloy self-metric is absent: $metric_name" >&2
    exit 1
  }
done

failed=0
echo "PASS: local M6 Prometheus metrics plane, bounded labels, retention, and recording rules."
