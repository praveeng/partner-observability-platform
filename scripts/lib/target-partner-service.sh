#!/usr/bin/env bash

# Shared, side-effect-free target resolution for local SureWebServices validation.
# Callers must use resolve_target_partner_service before reading, building, or starting a service.

target_service_fail() {
  printf 'TARGET_SERVICE_ERROR: %s\n' "$1" >&2
  return 1
}

normalize_target_partner_service() {
  local cli_service="${1:-}"
  local env_service="${TARGET_PARTNER_SERVICE:-}"

  if [[ -n "$cli_service" && -n "$env_service" && "$cli_service" != "$env_service" ]]; then
    target_service_fail "--service conflicts with TARGET_PARTNER_SERVICE"
    return 1
  fi

  RESOLVED_TARGET_PARTNER_SERVICE="${cli_service:-$env_service}"
  if [[ -z "$RESOLVED_TARGET_PARTNER_SERVICE" ]]; then
    target_service_fail "set TARGET_PARTNER_SERVICE or pass --service"
    return 1
  fi
  if [[ ! "$RESOLVED_TARGET_PARTNER_SERVICE" =~ ^sure-nbfc-[a-z0-9][a-z0-9-]*$ ]]; then
    target_service_fail "target must be one exact sure-nbfc-* directory basename"
    return 1
  fi
}

resolve_target_partner_service() {
  local platform_root="$1"
  local cli_service="${2:-}"
  normalize_target_partner_service "$cli_service" || return 1

  local workspace_root
  if [[ -n "${SUREWEBSERVICES_ROOT:-}" ]]; then
    [[ -d "$SUREWEBSERVICES_ROOT" ]] || {
      target_service_fail "SUREWEBSERVICES_ROOT is not a directory"
      return 1
    }
    workspace_root="$(cd "$SUREWEBSERVICES_ROOT" && pwd -P)"
  else
    workspace_root="$(cd "$platform_root/.." && pwd -P)"
  fi

  local candidate="$workspace_root/$RESOLVED_TARGET_PARTNER_SERVICE"
  [[ -d "$candidate" ]] || {
    target_service_fail "exact target does not exist: $candidate"
    return 1
  }

  local canonical_candidate
  canonical_candidate="$(cd "$candidate" && pwd -P)"
  case "$canonical_candidate" in
    "$workspace_root"/*) ;;
    *)
      target_service_fail "target resolves outside SUREWEBSERVICES_ROOT"
      return 1
      ;;
  esac
  [[ "$(basename "$canonical_candidate")" == "$RESOLVED_TARGET_PARTNER_SERVICE" ]] || {
    target_service_fail "resolved target basename changed unexpectedly"
    return 1
  }
  [[ "$canonical_candidate" != "$(cd "$platform_root" && pwd -P)" ]] || {
    target_service_fail "platform project cannot be selected as a partner service"
    return 1
  }

  RESOLVED_SUREWEBSERVICES_ROOT="$workspace_root"
  RESOLVED_TARGET_PARTNER_SERVICE_PATH="$canonical_candidate"
  export RESOLVED_TARGET_PARTNER_SERVICE RESOLVED_SUREWEBSERVICES_ROOT RESOLVED_TARGET_PARTNER_SERVICE_PATH
}
