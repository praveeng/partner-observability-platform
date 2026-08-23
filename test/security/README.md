# Security tests

The implemented M2 pre-queue security scope runs with `./scripts/test-security.sh --core`. The M5 real Alloy/Loki trust-boundary and sink-absence scope runs with `./scripts/test-security.sh --data-plane`.

The M5 suite proves fixed partner routing for outbound and callback records, cross-tenant query denial, colliding identifier isolation, missing/conflicting route rejection, body/header spoof resistance, prohibited metadata removal, credential/Base64 absence in Loki, second-stage masking, and the exact label/structured-metadata contract.

Running `./scripts/test-security.sh` executes both implemented scopes and then remains non-zero while Grafana authorization, Prometheus query isolation, AWS network exposure, rotation/revocation, and the remaining M7-M9 adversarial checks are not implemented. A scoped M5 pass is not a whole-platform security claim.
