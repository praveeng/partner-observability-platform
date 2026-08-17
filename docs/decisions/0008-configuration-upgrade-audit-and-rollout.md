# ADR 0008: Configuration, upgrades, audit, and rollout

- Status: Accepted for M1/M8-M10 implementation
- Date: 2026-08-16
- Decision owners: Platform operations and release architecture

## Context

Markets, partners, services, APIs, capture policies, tenants, datasources, and credentials must be onboarded repeatably. Existing services need incremental adoption. Configuration/upgrade actions must be auditable without claiming partner telemetry is an audit ledger.

## Decision

Use one versioned non-secret manifest per market/environment as source of truth. It declares configured services/Cloud Map names, partners, opaque tenant/slot/org mappings, outbound/callback API and event schemas, approved HTTPS endpoint and callback ALB/ACM ownership references, callback trust-adapter IDs and route/filter ordering, typed correlation extractors/validators, per-leg capture/rate/sample policy, source authorizations, SLO inputs, local-user references, and secret/certificate ARNs. Validation enforces uniqueness, tombstones, hard caps, safe defaults, HTTPS-only deployed endpoints/no port 80 or downgrade, authenticated callback context, and no secret/certificate values. Generated artifacts have content digests and feed Alloy/gateway/journey-resolver/Grafana/Terraform.

Pin SDK/container/dependency versions and container digests. Promote identical artifacts DEV->STAGE->PROD. Alloy supports event schema N/N-1. Loki schema entries are append-only/future-dated. Stateless services roll/blue-green; stateful upgrades use compatibility/backup/maintenance steps. Kill switches reduce capture independently of rollback.

Audit evidence comes from Git/ADRs/CI artifacts/config digests, CloudTrail, ALB access logs, ECS/CloudWatch internal logs, and named operator/ticket account workflows. Partner telemetry remains best-effort operational data, not audit evidence.

Migrate existing services disabled -> health/metrics -> metadata-only sync DEV/STAGE -> one mock async acknowledgement/callback journey -> tenant/correlation/dashboard validation -> explicit plaintext/processing hooks -> per-leg full-safe approval -> canary/soak -> approved PROD. Offboarding disables access/capture first and preserves inaccessible data only for the remaining retention period.

## Security and availability consequences

- Onboarding cannot dynamically create tenants/meters/fields from runtime traffic.
- Config review/digests make deployments reproducible; secrets remain external.
- Rolling compatibility reduces upgrade coupling while kill switches protect business paths.
- Grafana OSS/local accounts do not provide a complete tamper-proof audit/MFA control; formal requirements remain unresolved.

## Alternatives considered

- Runtime self-service partner creation: rejected for security/cardinality/audit risk.
- Put credentials in manifest/Terraform variables: rejected for secret/state exposure.
- Big-bang service rollout/full payload default: rejected for availability/disclosure risk.
- Treat logs as an audit ledger: rejected because delivery is at-most-once and retention is 16 days.
- Automatically apply production Terraform: prohibited.

## Implementation and migration

M1 defines a JSON-Schema-equivalent manifest contract; M5-M8 add generators/validators and environment artifacts; M10 publishes onboarding/upgrade/offboarding runbooks. Tombstones prevent tenant/slot reuse. Production actions remain human-controlled external workflows.

## Verification evidence required

Golden and mutation manifest tests, collision/cap/unsafe-field rejection, deterministic generation/digests, N/N-1 compatibility, staged upgrade/rollback drills, account/credential lifecycle evidence, and rollout performance/security gates.

## References and supersession

Normative details: `../architecture.md`, `../deployment-model.md`, `../transport-security.md`, `../acceptance-criteria.md`, and `../../PLANS.md`. No ADR is superseded.
