#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo 'INFO: test-terraform.sh is a compatibility alias for the enterprise infrastructure requirements contract; it does not invoke Terraform or AWS.'
exec ./scripts/test-enterprise-infrastructure-contract.sh
