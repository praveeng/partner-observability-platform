#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

legacy_package_dot='com[.]partner[.]observability'
legacy_package_path="com/partner/""observability"
legacy_module_prefix="partner-observability""-"
legacy_module_names="${legacy_module_prefix}(core|spring-boot-autoconfigure|spring-boot-starter|test-app)"
target_package='com.samsung.sure.partner.observability'
result=0

mapfile -d '' java_files < <(
  find sure-partner-observability-* -type f \
    \( -path '*/src/main/java/*.java' -o -path '*/src/test/java/*.java' -o -path '*/src/integrationTest/java/*.java' \) \
    -print0
)

if ((${#java_files[@]} == 0)); then
  echo 'ERROR: no Partner Observability Java source files were found.' >&2
  result=1
fi

if rg -n "^(package|import([[:space:]]+static)?)[[:space:]]+${legacy_package_dot}" "${java_files[@]}"; then
  echo 'ERROR: legacy Partner Observability package declaration or import found.' >&2
  result=1
fi

for java_file in "${java_files[@]}"; do
  package_name="$(sed -n 's/^package \([^;]*\);$/\1/p' "$java_file" | head -n 1)"
  if [[ "$package_name" != "$target_package" && "$package_name" != "$target_package".* ]]; then
    printf 'ERROR: Java package is outside the enterprise root: %s (%s)\n' "$java_file" "${package_name:-missing}" >&2
    result=1
    continue
  fi

  source_relative="${java_file#*/java/}"
  expected_relative="${package_name//./\/}/${java_file##*/}"
  if [[ "$source_relative" != "$expected_relative" ]]; then
    printf 'ERROR: Java package/path mismatch: %s declares %s\n' "$java_file" "$package_name" >&2
    result=1
  fi
done

if find sure-partner-observability-* -type d \
    \( -path "*/src/main/java/${legacy_package_path}" \
       -o -path "*/src/test/java/${legacy_package_path}" \
       -o -path "*/src/integrationTest/java/${legacy_package_path}" \) \
    -print -quit | grep -q .; then
  echo 'ERROR: a legacy Java package source directory remains.' >&2
  result=1
fi

if rg -n "$legacy_package_dot" sure-partner-observability-*/src --glob '*/resources/**'; then
  echo 'ERROR: legacy Java package reference found in active Spring resources.' >&2
  result=1
fi

if find . -maxdepth 1 -type d -regextype posix-extended \
    -regex "[.]/${legacy_module_names}" -print -quit | grep -q .; then
  echo 'ERROR: a legacy Java/Spring module directory remains.' >&2
  result=1
fi

if rg -n "['\"]:?${legacy_module_names}['\"]" \
    settings.gradle build.gradle sure-partner-observability-*/*.gradle; then
  echo 'ERROR: a stale Gradle module include or project dependency remains.' >&2
  result=1
fi

if rg -n --pcre2 ":${legacy_module_names}:|(^|[^[:alnum:]-])${legacy_module_names}/(build|src)/" scripts test docker; then
  echo 'ERROR: a stale Gradle task or Java module filesystem reference remains.' >&2
  result=1
fi

for module in \
  sure-partner-observability-core \
  sure-partner-observability-spring-boot-autoconfigure \
  sure-partner-observability-spring-boot-starter \
  sure-partner-observability-test-app; do
  if ! rg -q -F "include '$module'" settings.gradle; then
    printf 'ERROR: settings.gradle does not include required module %s\n' "$module" >&2
    result=1
  fi
done

if ! rg -q -F "group = 'com.samsung.sure'" build.gradle; then
  echo 'ERROR: Gradle group must be com.samsung.sure.' >&2
  result=1
fi

if ((result != 0)); then
  exit "$result"
fi

echo 'PASS: enterprise Java packages, source paths, Gradle group, and Java/Spring module names are standardized.'
