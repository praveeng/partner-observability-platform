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

run_data_plane_security() {
  ./test/integration/run-local-data-plane.sh
}

run_metrics_plane_security() {
  ./test/integration/run-local-metrics-plane.sh
}

run_terraform_security() {
  ./scripts/test-terraform.sh
}

if [[ "${1:-}" == "--core" ]]; then
  echo "Running the implemented M2 pre-queue payload, trusted-context, and failure-containment security gate."
  run_core_security
  echo "PASS: M2 core security scope. This does not claim downstream Alloy/Loki/Grafana isolation."
  exit 0
fi

if [[ "${1:-}" == "--data-plane" ]]; then
  echo "Running the implemented M5 real-container Alloy/Loki isolation and sink-safety gate."
  run_data_plane_security
  echo "PASS: M5 local data-plane security scope. This does not claim Grafana/Prometheus query authorization or deployed-network isolation."
  exit 0
fi

if [[ "${1:-}" == "--metrics-plane" ]]; then
  echo "Running the implemented M6 real-container Alloy/Prometheus scrape-safety gate."
  run_metrics_plane_security
  echo "PASS: M6 local metrics-plane scope. This does not claim Grafana/query-proxy or deployed-network isolation."
  exit 0
fi

if [[ "${1:-}" == "--terraform" ]]; then
  echo "Running the implemented M8 Terraform/ECS static and mocked-provider security gate."
  run_terraform_security
  echo "PASS: M8 configuration scope. No AWS plan, apply, credential, or runtime reachability claim is made."
  exit 0
fi

echo "Running implemented M2 core, M5 local data-plane, M6 local metrics-plane, and M8 Terraform checks before reporting remaining platform gaps."
run_core_security
run_data_plane_security
run_metrics_plane_security
run_terraform_security
echo "NOT IMPLEMENTED: Grafana/Prometheus partner query authorization, deployed runtime reachability, rotation/revocation drills, and remaining end-to-end verification are scheduled for M7/M9." >&2
echo "No whole-platform security-check success is being claimed. Use a scoped option for an implemented gate." >&2
exit 2
