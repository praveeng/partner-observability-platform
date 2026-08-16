# ADR 0004: Tenant routing and Grafana isolation

- Status: Accepted for M5/M7 implementation
- Date: 2026-08-16
- Decision owners: Security and platform architecture

## Context

The requirement is one Loki tenant per partner with local Grafana accounts and partner-specific datasources. Loki requires a tenant header but does not authenticate it. Grafana OSS organizations isolate datasources, yet a Viewer can query every datasource inside its organization. Shared Prometheus has no native tenant authorization.

## Decision

Create one opaque Loki tenant, opaque `partner_slot`, and Grafana organization per market/environment/partner. Trusted application context supplies a canonical partner key. The private Alloy ingress authenticates a source-service credential, authorizes the exact source-partner pair, strips tenant headers, and routes to a fixed generated Alloy partner pipeline. The Loki authenticating gateway injects fixed `X-Scope-OrgID`; Loki direct access and multi-tenant queries are disabled.

Give each partner organization only fixed proxy datasources. Datasource credentials authenticate at a query gateway. Loki credentials map to one tenant. The stateless journey resolver receives that fixed tenant before it accepts a typed identifier and uses only bounded exact structured-metadata queries. Prometheus credentials map to one slot; Nginx strips caller headers and supplies the fixed slot to a shared pinned `prom-label-proxy`, which parses PromQL and enforces `partner_slot=<fixed>`. Unsupported endpoints are denied. Network policy prevents direct backend access.

Initial users are individual local Grafana Viewer accounts in exactly one partner organization. Anonymous/self-signup is disabled. Partner users are not Editor/Admin/server admin. Internal operations uses a separate organization and credentials.

## Security and availability consequences

- Browser controls and dashboard variables are usability only; authorization exists at ingest/query gateways.
- A compromised source credential is limited to its configured partner set, though it can fabricate events for that set.
- One Grafana/query gateway serves many organizations cost-effectively; gateway outage removes dashboards but not business traffic.
- Local auth lacks guaranteed enterprise MFA/audit capabilities, retained as an explicit production policy question.

## Alternatives considered

- Trust SDK `X-Scope-OrgID`: rejected because a compromised/misconfigured service could select another tenant.
- Grafana folder/dashboard filtering only: rejected as queryable datasource bypass.
- Grafana Enterprise datasource permissions/LBAC: not assumed for the cost-conscious OSS baseline.
- One full Grafana/Prometheus instance per partner: stronger physical isolation but disproportionate cost/operations initially.
- Raw partner ID as metric tenant label: rejected; use bounded opaque slot.

## Implementation and migration

Manifests generate source-partner maps, fixed pipelines, credentials, orgs, and datasource provisioning. Onboarding creates backend isolation before SDK enablement. OIDC/SAML can later replace local login while preserving organization mappings.

## Verification evidence required

Cross-source/tenant/org/header/direct-network/PromQL endpoint bypass tests, context concurrency tests, rotation/offboarding tests, and account role/session configuration checks.

## References and supersession

- [Loki authentication requires a proxy](https://grafana.com/docs/loki/latest/operations/authentication/)
- [Loki multi-tenancy](https://grafana.com/docs/loki/latest/operations/multi-tenancy/)
- [Grafana organization isolation](https://grafana.com/docs/grafana/latest/administration/organization-management/)
- [prom-label-proxy query enforcement](https://github.com/prometheus-community/prom-label-proxy)

Normative details: `../partner-isolation.md` and ADR 0009. No ADR is superseded.
