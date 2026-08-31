#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
partner_key="${TARGET_LOCAL_PARTNER_KEY:-}"
sdk_username="${TARGET_LOCAL_SDK_USERNAME:-}"
output="${1:-}"

[[ "$partner_key" =~ ^[a-z0-9][a-z0-9._-]{0,127}$ ]] || {
  echo 'TARGET_SERVICE_ERROR: TARGET_LOCAL_PARTNER_KEY is required and must be a safe exact key' >&2
  exit 2
}
[[ "$sdk_username" =~ ^[a-z0-9][a-z0-9._-]{0,127}$ ]] || {
  echo 'TARGET_SERVICE_ERROR: TARGET_LOCAL_SDK_USERNAME is required and must be a safe exact username' >&2
  exit 2
}
[[ -n "$output" && "$output" == /* ]] || {
  echo 'TARGET_SERVICE_ERROR: renderer output must be an explicit absolute temporary path' >&2
  exit 2
}

python3 - "$repo_root/docker/nginx/local-gateway.conf" "$output" "$sdk_username" "$partner_key" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
username = sys.argv[3]
partner_key = sys.argv[4]
text = source.read_text(encoding="utf-8")
needle = '        default "";\n'
if text.count(needle) < 1:
    raise SystemExit("TARGET_SERVICE_ERROR: local gateway template insertion point is missing")
route = f'        "{username}:{partner_key}" "alloy:4318";\n'
text = text.replace(needle, needle + route, 1)
output.write_text(text, encoding="utf-8")
PY
