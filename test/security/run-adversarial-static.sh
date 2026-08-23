#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

production_java=(
  partner-observability-core/src/main/java
  partner-observability-spring-boot-autoconfigure/src/main/java
)

fail() {
  echo "FAIL: adversarial static security check: $*" >&2
  exit 1
}

forbidden_tls='X509TrustManager|HostnameVerifier|NoopHostnameVerifier|InsecureTrustManagerFactory|trustAll|SSLContext\.getInstance|\.sslSocketFactory\(|\.hostnameVerifier\(|replace(All)?\("https://'
if rg -n "$forbidden_tls" "${production_java[@]}"; then
  fail 'production SDK source contains a TLS trust/hostname mutation or downgrade primitive'
fi

if rg -n 'http://' partner-observability-spring-boot-autoconfigure/src/main/java \
    partner-observability-core/src/main/java; then
  fail 'production starter source contains a plaintext endpoint literal'
fi

if git grep -n -E -- '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'; then
  fail 'tracked source contains private-key material'
fi

if rg -n -i 'output[[:space:]]+"[^"]*(private[_-]?key|password|secret|certificate)' terraform --glob '*.tf'; then
  fail 'Terraform exposes a secret- or key-shaped output'
fi

rg -q 'outbound origin must use HTTPS' \
  partner-observability-spring-boot-autoconfigure/src/main/java/com/partner/observability/autoconfigure/PartnerObservabilityConfigurationValidator.java \
  || fail 'outbound HTTPS origin startup validation is missing'
rg -q 'originMatches' \
  partner-observability-spring-boot-autoconfigure/src/main/java/com/partner/observability/autoconfigure/ConfiguredObservationRegistry.java \
  || fail 'outbound capture is not bound to a configured origin'
rg -q 'callback routes must not overlap' \
  partner-observability-spring-boot-autoconfigure/src/main/java/com/partner/observability/autoconfigure/PartnerObservabilityConfigurationValidator.java \
  || fail 'ambiguous callback routes are not rejected'

echo 'PASS: no production TLS bypass/downgrade primitive, tracked private key, sensitive Terraform output, or missing origin/route guard was found.'
