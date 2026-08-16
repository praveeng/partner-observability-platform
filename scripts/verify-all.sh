#!/usr/bin/env bash
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

status=0

run_check() {
  local name="$1"
  shift
  echo "==> $name"
  if "$@"; then
    echo "PASS: $name"
  else
    local result=$?
    echo "FAIL: $name (exit $result)" >&2
    status=1
  fi
}

run_check "build" ./scripts/build.sh
run_check "tests" ./scripts/test.sh
run_check "security" ./scripts/test-security.sh
run_check "performance" ./scripts/test-performance.sh

if (( status != 0 )); then
  echo "Verification incomplete or failed. See results above; NOT IMPLEMENTED checks are not successes." >&2
  exit "$status"
fi

echo "All implemented verification checks passed."
