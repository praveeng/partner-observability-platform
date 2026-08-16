#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

run_core_security() {
  ./gradlew --no-daemon :partner-observability-core:test \
    --tests '*PayloadSafetyTest' \
    --tests '*PartnerContextSecurityTest' \
    --tests '*BoundedAsyncDispatcherTest'
}

if [[ "${1:-}" == "--core" ]]; then
  echo "Running the implemented M2 pre-queue payload, trusted-context, and failure-containment security gate."
  run_core_security
  echo "PASS: M2 core security scope. This does not claim downstream Alloy/Loki/Grafana isolation."
  exit 0
fi

echo "Running implemented M2 core security checks before reporting remaining platform gaps."
run_core_security
echo "NOT IMPLEMENTED: downstream Alloy/Loki/Grafana and end-to-end security verification remains scheduled for M4-M9." >&2
echo "No whole-platform security-check success is being claimed. Use --core only for the implemented M2 scope." >&2
exit 2
