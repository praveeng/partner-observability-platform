# ADR 0011: HTTPS/TLS ownership and ingress boundary

- Status: Accepted for M1 specification; implementation evidence required in M3/M8/M9
- Date: 2026-08-17
- Decision owners: Application security, cloud platform, and SDK architecture

## Context

All external partner traffic must use HTTPS/TLS. The platform observes RestTemplate, WebClient, OkHttp, and inbound callbacks, but observability must not weaken certificate validation, hostname verification, redirect policy, or application availability. Callback ingress needs an explicit ALB/ACM trust boundary, and certificate/private-key material must remain outside telemetry and Git. Local synthetic HTTP fixtures need a narrow exception that cannot reach deployed environments.

## Decision

Require HTTPS for every external partner API, acknowledgement, callback/webhook, and partner Grafana connection in DEV, STAGE, and PROD. ECS DEV mock partners also use HTTPS. Only isolated `LOCAL_SYNTHETIC` Docker/loopback fixtures may use HTTP.

Host integrations own outbound endpoint, redirect, `SSLContext`, trust manager, hostname verifier, trust store, proxy, and client-certificate settings. RestTemplate instrumentation adds an interceptor without replacing its request factory; WebClient instrumentation adds a filter without replacing its connector/`SslProvider`; OkHttp instrumentation adds an application interceptor without changing TLS, certificate pinning, connection, DNS, or redirect settings. The starter never constructs, installs, mutates, relaxes, or bypasses TLS configuration and never retries or falls back to HTTP.

External callbacks and Grafana enter through an ALB HTTPS listener on port 443 using an approved TLS-1.2-or-newer policy and ACM-managed certificate. Port 80 is absent, not redirect-only, because a plaintext first hop violates the invariant and callback redirects can alter POST/authentication semantics. The ALB is the approved external TLS termination boundary. ECS targets are private, have no public IP, and accept application traffic only from the ALB security group. Host service infrastructure owns callback ALBs; the observability Terraform network module owns only the Grafana ALB.

The default platform/JVM trust store is preferred. A custom partner CA is a service/security-owned, reviewed, scoped configuration delivered through approved artifact/secret mechanisms. It retains certificate-path and hostname validation; failure never selects trust-all or plaintext fallback. ACM private keys do not leave ACM. All key, keystore, trust-store password, signature, and certificate-private material is prohibited from telemetry, logs, Git, generated manifests, and Terraform values.

Partner-safe telemetry may contain only a bounded TLS outcome/failure enum plus configured API ID, duration, attempt, and already-safe correlation identifiers. It never contains exception messages, peer URL/host/address, certificate chain/identity/fingerprint/serial, cipher debug output, trust-store path, headers, payload, or key material. Inbound handshake failures before callback authentication remain internal-only at ALB.

The existing opaque authentication-adapter boundary permits future mTLS. A later ADR must define certificate issuance, identity mapping, revocation, rotation, and performance; the observability SDK still will not own key/trust managers.

## Security and availability consequences

- Standard client certificate and hostname verification remains authoritative and unchanged when the starter is enabled.
- There is no HTTP downgrade/fallback and no public/direct path to ECS callback or Grafana tasks.
- ALB termination is a transport boundary, not proof of callback partner identity; host authentication/signature verification still precedes partner telemetry.
- TLS/certificate failures remain original business transport outcomes. Observability classification/export failures cannot replace or suppress them.
- Detailed certificate diagnostics stay internal; partner telemetry remains useful through safe failure classes without disclosing certificate or key material.

## Alternatives considered

- Trust-all manager or permissive hostname verifier: prohibited because it defeats server authentication.
- Starter-managed TLS configuration: rejected because it could mutate business transport behavior and centralize sensitive key/trust authority.
- Port-80 redirect to HTTPS: rejected because the first hop is plaintext and callback clients may change/drop POST/authentication on redirect.
- TLS passthrough directly to public ECS tasks: rejected because tasks must not be internet reachable and ACM/ALB is the approved termination boundary.
- Emit certificate subjects/fingerprints for diagnosis: rejected as unnecessary disclosure; bounded type-based failure metadata is sufficient.
- Implement mTLS now: deferred until partner identity/lifecycle requirements exist; extension points are preserved.

## Implementation and migration impact

M3 source/integration tests prove no TLS object or client security setting changes when instrumentation activates. M8 Terraform validates 443-only ALBs, ACM ARN attachment, approved TLS policy, private subnets/targets, no public task IP, and exact security-group edges. M9 runs synthetic certificate, hostname, downgrade, forwarding-header, secret-leak, and rotation tests. Onboarding inventories current endpoints and remediates HTTP before telemetry enablement; the starter does not rewrite a noncompliant endpoint.

## Verification evidence required

Use synthetic CA/server certificates to prove valid trust, unknown CA, expiry, hostname mismatch, and HTTPS-to-HTTP redirect behavior for all three clients with the starter disabled and enabled. Compare TLS configuration identity/effective behavior before and after instrumentation. Validate callback/Grafana listeners and target reachability from Terraform plan/config. Scan every telemetry and diagnostic surface for certificate/key/trust-store sentinels. Prove the local HTTP exception is unreachable outside an isolated local profile.

## References and supersession

Normative details: `../transport-security.md`, `../architecture.md`, `../security-invariants.md`, `../threat-model.md`, and `../deployment-model.md`. This ADR refines ADRs 0003, 0007, and 0010 without superseding their client, deployment, or callback trust boundaries.
