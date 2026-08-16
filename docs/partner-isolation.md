# Partner Isolation

## Isolation unit

The security unit is `(market, environment, canonicalPartnerKey)`. It maps one-to-one to an opaque Loki tenant ID, an opaque Prometheus `partner_slot`, and a Grafana organization. Identifiers are never reused across environments or markets. There is no shared/default/fallback partner.

## Trust chain

```text
authenticated business principal / server-owned integration configuration
        -> PartnerContextResolver
        -> canonicalPartnerKey + AUTHENTICATED_SERVER trust
        -> service startup allowlist
        -> SDK safe event routing key / bounded metric slot
        -> authenticated Alloy ingress mapping (service principal, partner key)
        -> fixed per-partner Alloy pipeline
        -> fixed X-Scope-OrgID at Loki gateway
```

The SDK never trusts a public header, query parameter, body field, MDC value, Grafana variable, Loki label, or caller-supplied tenant ID. A host service may authenticate a partner using a header/certificate/token, but the resolver consumes only the authenticated principal or server-owned result after that authentication.

Missing, ambiguous, stale, disabled, conflicting, or unmapped context produces no partner telemetry. It does not route to an internal or common tenant. Business processing continues.

## Application enforcement

- Each service has a startup allowlist of partners it may observe and APIs each partner may use.
- `PartnerContext` construction is package-restricted to trusted resolvers; application APIs accept an opaque context handle, not a tenant string.
- Context is immutable and captured per record before asynchronous batching.
- Queues and a drain may contain multiple partners, but the dispatcher partitions them into bounded single-partner transport requests. The ingress maps each request to one fixed tenant pipeline.
- Rate/sampling state is preallocated per configured partner and cannot create dynamic tenants.
- Reactive/thread context restoration tests prevent partner identity leakage through pooled threads.

## Ingest enforcement

The internal Alloy ingress proxy terminates TLS and authenticates a unique source-service credential stored in AWS Secrets Manager. It strips `X-Scope-OrgID`, tenant, and slot headers. A generated exact map authorizes `(source principal, canonical partner key)` and routes it to a private Alloy receiver/pipeline whose tenant value is fixed in configuration. A payload field cannot select the tenant.

Alloy validates market/environment/service/schema and overwrites trusted routing attributes. Unknown source, partner, API mapping, or pipeline is denied and counted internally without logging the request body. Loki is reachable only from the Alloy/query gateway security groups and runs multi-tenant mode.

Credential rotation uses overlapping old/new source credentials for at most 24 hours, with distinct IDs and audit events. Disabling a partner removes its mapping before credentials/config are deleted.

## Loki tenant model

- Exactly one opaque tenant per partner per market/environment.
- Tenant IDs match `[a-z0-9-]{1,40}` and reveal no legal/customer name.
- Multi-tenant queries are disabled.
- Tenant identity is not a Loki label and is removed from log lines.
- Per-tenant limits use the same safe baseline; changes require manifest review.
- Operator access uses a separate internal query credential, named identity, ticket reference, one tenant at a time, and gateway audit log.

Loki itself is not an authentication system. An authenticating proxy strips all incoming tenant headers and injects the mapped tenant. No client can connect directly.

## Grafana local authentication

Initial authentication uses individual Grafana local accounts, never shared partner accounts. Account creation/reset is an operator workflow with partner approval, a temporary random password delivered out of band, forced password change on first login where supported, and immediate disable on offboarding. Minimum password length is 16 characters; login throttling, secure/HttpOnly/SameSite cookies, TLS, short sessions, and ALB/WAF rate controls are enabled. Anonymous access and self-signup are disabled.

Each user belongs to exactly one partner organization with Viewer role. Partner users are never Grafana server admin, organization Admin, or Editor. Server administrators use separate named break-glass accounts that are not used for routine partner access. Whether local accounts satisfy production MFA/identity policy is unresolved and recorded in `decisions-needed.md`; the migration target is corporate OIDC/SAML without changing organization/tenant mappings.

## Grafana authorization and datasource isolation

One Grafana organization per partner isolates dashboards, folders, alerts, service accounts, and datasource definitions. Provisioned resources carry deterministic UIDs and cannot be edited by partner Viewers.

Organization isolation is necessary but not sufficient because any Viewer may query every datasource in its organization:

- The organization contains one Loki datasource whose secret credential maps at the query gateway to one fixed tenant. The gateway strips `X-Scope-OrgID` and injects the mapped value.
- The organization contains one Prometheus datasource whose credential maps to one fixed `partner_slot`. Nginx strips caller headers and injects the slot to `prom-label-proxy`, which enforces the matcher in parsed PromQL and supported metadata APIs.
- Datasources use Grafana server/proxy access only. Browser/direct access is disabled.
- Direct Loki, Prometheus, gateway-internal, Alloy, and Actuator endpoints are denied by network security groups.
- Explore is disabled for partner users where the selected Grafana version supports it; isolation does not depend on that UI control.

An internal operations Grafana organization has separate internal-only datasources/credentials. Partner dashboards and partner accounts are never added to it.

## Search and visualization isolation

Application/loan/reference search occurs only inside the datasource's fixed Loki tenant. Search inputs are length/syntax validated and require a bounded time range but never influence tenant selection. Timeline and detail links carry an `eventId`/identifier as a query value; the destination dashboard repeats the same tenant-fixed datasource query rather than trusting the link.

SLA/SLI dashboards use a datasource behind the enforced `partner_slot`. The slot is not exposed as a dashboard variable. Dashboard variables may only narrow API/service/direction within the already authorized tenant.

## Configuration-driven onboarding/offboarding

The reviewed market manifest assigns canonical partner key, tenant ID, slot, Grafana organization, allowed source services/APIs, datasource credential secret ARNs, and local user references. Validation rejects collisions and changes that would reassign an existing tenant/slot/org.

Onboarding creates isolation in this order: secrets/identities, backend tenant/slot mapping, gateway/Alloy routes, Grafana organization/datasources/dashboards, user, then SDK enablement. Tests must pass before enablement. Offboarding reverses access first: disable SDK and user/query credentials, retain inaccessible telemetry for the remaining 16-day period, then delete mappings/resources. A tenant ID is never reassigned.

## Mandatory isolation tests

- Spoofed headers/body/MDC/query variables cannot choose a tenant or slot.
- Every source principal can emit only for its exact configured partner set.
- Unknown/missing/conflicting partner context produces no fallback telemetry.
- Concurrent servlet, executor, Reactor, batch, retry, and cache paths do not cross context.
- A partner Grafana account cannot join/switch to another organization or access its datasource/dashboard.
- Loki query/write credentials cannot change or combine `X-Scope-OrgID`.
- PromQL selectors and metadata endpoints cannot remove, regex-widen, or conflict with enforced `partner_slot`.
- Direct backend/internal endpoint access is denied.
- Rotation, disabled mapping, stale config, and offboarding fail closed.
- Internal operator access is separate and auditable.
