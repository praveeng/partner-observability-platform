#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

canonical_profiles=(local dev stage prod)
mapfile -t runnable_modules < <(
  rg -l "^[[:space:]]*id ['\"]org\.springframework\.boot['\"][[:space:]]*$" \
    --glob 'build.gradle' --glob '*/build.gradle' . | xargs -r -n1 dirname | sort -u
)

[[ ${#runnable_modules[@]} -gt 0 ]] || fail 'no runnable Spring Boot module was discovered'

for module in "${runnable_modules[@]}"; do
  resources="$module/src/main/resources"
  [[ -f "$resources/application.properties" ]] ||
    fail "$module is missing src/main/resources/application.properties"
  for profile in "${canonical_profiles[@]}"; do
    profile_file="$resources/application-$profile.properties"
    [[ -f "$profile_file" ]] || fail "$module is missing application-$profile.properties"
    rg -q "^partner-observability\.environment=$profile$" "$profile_file" ||
      fail "$profile_file does not declare canonical environment $profile"
  done

  while IFS= read -r profile_file; do
    profile_name="${profile_file##*/application-}"
    profile_name="${profile_name%.properties}"
    [[ " ${canonical_profiles[*]} " == *" $profile_name "* ]] ||
      fail "$profile_file uses unsupported Spring profile name $profile_name"
  done < <(find "$resources" -maxdepth 1 -type f -name 'application-*.properties' -print)
done

if find . -type f \
    \( -name 'application.yml' -o -name 'application.yaml' -o -name 'application-*.yml' -o -name 'application-*.yaml' \) \
    -not -path './.git/*' -not -path '*/build/*' -print -quit | grep -q .; then
  find . -type f \
    \( -name 'application.yml' -o -name 'application.yaml' -o -name 'application-*.yml' -o -name 'application-*.yaml' \) \
    -not -path './.git/*' -not -path '*/build/*' -print >&2
  fail 'Spring application YAML is prohibited; use .properties files'
fi

if rg -n -i \
    '(@Profile|spring[._-]profiles[^=]*=|SPRING_PROFILES_ACTIVE[^=]*=)[^\n]*(development|staging|production|uat)' \
    sure-partner-observability-* docker scripts test 2>/dev/null \
    --glob '!**/build/**' --glob '!validate-profiles.sh'; then
  fail 'unsupported Spring profile alias found in active code or runtime configuration'
fi

common='sure-partner-observability-test-app/src/main/resources/application.properties'
if rg -n '^spring\.profiles\.active=' "$common"; then
  fail 'the packaged application must not hard-code an active profile'
fi

local_file='sure-partner-observability-test-app/src/main/resources/application-local.properties'
rg -q '^partner-observability\.local-synthetic=true$' "$local_file" ||
  fail 'the local profile must explicitly enable the guarded synthetic fixture'

for profile in dev stage prod; do
  profile_file="sure-partner-observability-test-app/src/main/resources/application-$profile.properties"
  rg -q '^partner-observability\.local-synthetic=false$' "$profile_file" ||
    fail "$profile profile must explicitly disable local-synthetic mode"
  if rg -n -i 'localstack|localhost\.localstack\.cloud|testcontainers|http://|127\.0\.0\.1|localhost' "$profile_file"; then
    fail "$profile profile contains local-only runtime configuration"
  fi
done

if rg -n -i '^[^#=]*(url|uri|endpoint|origin)[^=]*=.*(^|[.=/_-])(stage|staging|prod|production)([.=/_-]|$)' \
    sure-partner-observability-test-app/src/main/resources/application-dev.properties; then
  fail 'DEV profile contains an obvious STAGE/PROD reference'
fi
if rg -n -i '^[^#=]*(url|uri|endpoint|origin)[^=]*=.*(^|[.=/_-])(dev|mock|prod|production)([.=/_-]|$)' \
    sure-partner-observability-test-app/src/main/resources/application-stage.properties; then
  fail 'STAGE profile contains an obvious DEV/mock/PROD reference'
fi
if rg -n -i '^[^#=]*(url|uri|endpoint|origin)[^=]*=.*(^|[.=/_-])(local|dev|mock|stage|staging)([.=/_-]|$)' \
    sure-partner-observability-test-app/src/main/resources/application-prod.properties; then
  fail 'PROD profile contains an obvious LOCAL/DEV/mock/STAGE reference'
fi

secret_failure=0
while IFS=: read -r file line assignment; do
  key="${assignment%%=*}"
  value="${assignment#*=}"
  if [[ "$value" != \$\{*\} && ! "$value" =~ ^(synthetic|local-test|test-only|dummy) ]]; then
    printf 'FAIL: suspicious plaintext secret assignment at %s:%s (%s)\n' "$file" "$line" "$key" >&2
    secret_failure=1
  fi
done < <(rg -n -i \
  '^[[:space:]]*[^#=]*(password|secret|token|api[-_.]?key|apikey|credential|authorization|private[-_.]?key)[^=]*=' \
  sure-partner-observability-*/src/main/resources/application*.properties 2>/dev/null || true)
(( secret_failure == 0 )) || fail 'plaintext secret-shaped Spring configuration is prohibited'

rg -q 'SPRING_PROFILES_ACTIVE:[[:space:]]*local' docker/compose.yml ||
  fail 'Docker Compose test app must activate the local Spring profile'

enum_file='sure-partner-observability-core/src/main/java/com/samsung/sure/partner/observability/core/context/DeploymentEnvironment.java'
for profile in "${canonical_profiles[@]}"; do
  enum_name="${profile^^}"
  rg -q "^[[:space:]]*${enum_name}\\(\"${profile}\"\\)[,;]$" "$enum_file" ||
    fail "DeploymentEnvironment is missing $enum_name -> $profile"
done

echo 'PASS: runnable Spring applications use properties-only local/dev/stage/prod profiles with isolated environment semantics.'
