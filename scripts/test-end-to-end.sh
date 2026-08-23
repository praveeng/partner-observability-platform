#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

end_to_end_runner='test/integration/run-local-end-to-end.sh'
if [[ ! -x "$end_to_end_runner" ]]; then
  echo "NOT IMPLEMENTED: executable application-to-platform completion suite is missing: $end_to_end_runner" >&2
  echo 'The current data-plane suite injects synthetic OTLP records directly; it does not prove the required application outbound request/response and async acknowledgement/callback journeys through Grafana.' >&2
  echo 'The missing suite must also prove transaction/callback-reference search, event and metric correctness, callback metrics, cross-tenant denial, and colliding application/callback-reference isolation.' >&2
  exit 2
fi

exec "$end_to_end_runner"
