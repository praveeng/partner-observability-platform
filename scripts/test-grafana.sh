#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

required_paths=(
  grafana/provisioning
  grafana/dashboards
  test/integration/run-local-grafana.sh
)

missing=0
for required_path in "${required_paths[@]}"; do
  if [[ ! -e "$required_path" ]]; then
    echo "NOT IMPLEMENTED: required Grafana completion asset is missing: $required_path" >&2
    missing=1
  fi
done

if (( missing != 0 )); then
  echo 'Grafana completion requires deterministic provisioning/dashboard validation and real local authentication, organization, datasource, query-isolation, search, timeline, detail, SLI, and health tests.' >&2
  exit 2
fi

for command_name in docker jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "ERROR: required command is unavailable: $command_name" >&2
    exit 1
  }
done

find grafana -type f -name '*.json' -print0 | while IFS= read -r -d '' json_file; do
  jq empty "$json_file"
done

if [[ "${1:-}" == '--validate-only' ]]; then
  exec ./test/integration/run-local-grafana.sh --validate-only
fi

exec ./test/integration/run-local-grafana.sh
