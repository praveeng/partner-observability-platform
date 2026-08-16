#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ ! -x ./gradlew ]]; then
  echo "ERROR: Gradle wrapper is missing or not executable." >&2
  exit 1
fi

echo "Running implemented Gradle tests, including the synthetic partner integration fixture."
exec ./gradlew --no-daemon test
