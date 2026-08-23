#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

run_core_security() {
  ./gradlew --no-daemon :partner-observability-core:test \
    --tests '*PayloadSafetyTest' \
    --tests '*PartnerContextSecurityTest' \
    --tests '*BoundedAsyncDispatcherTest'
  ./gradlew --no-daemon :partner-observability-spring-boot-autoconfigure:test \
    --tests '*TlsInstrumentation*' \
    --tests '*PartnerObservabilityAutoConfigurationTest' \
    --tests '*PartnerCallbackWebFilterTest' \
    --tests '*PartnerSafeLogCompatibilityTest' \
    --tests '*JacksonSafeBodyCaptureTest'
  ./gradlew --no-daemon :partner-observability-test-app:test \
    --tests '*PartnerObservabilityStarterIntegrationTest' \
    --tests '*SyntheticAsyncLifecycleIntegrationTest' \
    --tests '*EncryptedRestTemplateFixtureIntegrationTest'
  ./test/security/run-adversarial-static.sh
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
  echo "Running the implemented pre-queue, callback, client TLS, trusted-context, and failure-containment security gate."
  run_core_security
  echo "PASS: SDK/callback security scope. This does not claim downstream Alloy/Loki/Grafana isolation."
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

echo "Running the complete local security gate: pre-queue safety, data/metrics planes, Terraform policy, Grafana/query authorization, and application end to end."
run_core_security
run_data_plane_security
run_metrics_plane_security
run_terraform_security
./scripts/test-grafana.sh
./scripts/test-end-to-end.sh
echo "PASS: complete local disclosure and partner-isolation security gate."
