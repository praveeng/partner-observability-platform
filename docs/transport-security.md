# Transport Security

## Scope and hard invariant

Every external partner-facing connection is HTTPS/TLS. This includes synchronous and asynchronous calls from a partner integration service to a partner API, acknowledgements returned on those connections, callbacks/webhooks from a partner to a Samsung/service endpoint, and partner access to Grafana. Plain HTTP partner traffic is prohibited in DEV, STAGE, and PROD. There is no automatic or operator-configurable downgrade from HTTPS to HTTP.

The only plaintext HTTP exception is a synthetic fixture on an isolated local Docker network or loopback interface. It must use the `LOCAL_SYNTHETIC` profile, synthetic data, no route to a real partner, and configuration that cannot be promoted to an ECS environment. ECS DEV mock partners use HTTPS.

TLS protects transport; it does not establish callback business identity by itself. Callback authentication/signature validation and trusted partner resolution remain the host service's responsibility unless a future approved mTLS adapter supplies an authenticated identity.

## Data classes at the TLS boundary

Transport processing preserves the platform's four relevant classes:

| Class | Examples | Partner telemetry treatment |
| --- | --- | --- |
| Partner exchange data | Request/response/callback plaintext or ciphertext | Never queued directly; only the existing bounded safe projection may be observed |
| Partner-safe derived observability | Configured endpoint ID, HTTPS transport outcome, bounded TLS failure class | May enter the partner tenant after normal trust and sanitization |
| Internal-only transport information | Load-balancer request IDs, target health, certificate-expiry alarms, security-group decisions | Internal operational systems only |
| Transport security secrets | Private keys, client keys, trust-store/keystore bytes and passwords, session tickets, tokens, signature material | Never telemetry, logs, Git, Terraform values, dashboards, or error text |

Certificate subject/issuer/SAN values, serial numbers, fingerprints, certificate chains, raw peer hostnames, trust-store paths, and exception messages are internal-only by default. They are not needed for partner-safe diagnosis and are therefore omitted.

## Trust boundaries and connection paths

```text
Outbound
private ECS task
  -> service-owned HTTPS client and TLS configuration
  -> controlled egress/NAT or approved egress proxy
  -> partner HTTPS endpoint
  -> validated server certificate + hostname

Inbound callback
partner HTTPS client
  -> public DNS
  -> AWS ALB :443 + approved TLS policy + ACM certificate
       [external TLS termination trust boundary]
  -> private target group / callback service security group
  -> host authentication/signature/decryption chain
  -> trusted callback context
  -> observability interception and business processing

Partner UI
partner browser HTTPS
  -> Grafana ALB :443 + ACM
  -> private Grafana target
```

The internet-facing ALB is the approved external TLS termination boundary. Decrypted traffic after that boundary is no longer an external partner-facing hop and is restricted to private targets by security groups and routing. HTTPS target groups may add encryption in transit inside the VPC when required by the market threat assessment, but ALB-to-target encryption is not represented as end-to-end partner authentication. Host services own any application TLS listener and certificate used behind an ALB.

The observability SDK does not create, terminate, configure, or inspect TLS sessions. It observes only structured transport results made available by the already-configured client/server framework.

## Outbound HTTPS requirements

Every automatic outbound telemetry definition has a required configuration-owned `origin` containing only scheme, host, and optional port. Startup rejects a missing origin, user-info, path, query, fragment, and any non-HTTPS scheme. Selection requires exact scheme/host/effective-port, method, and path, so a different host with the same path cannot be attributed to the configured partner. Raw endpoint URLs and query strings never become telemetry.

The sole SDK configuration exception is `local-synthetic=true` with environment `DEV` and a literal `127.0.0.1` or `::1` HTTP origin. It exists for generated local fixtures with dynamic ports. It cannot validate in STAGE or PROD, does not accept a non-loopback host, and is not an ECS DEV mock exception. Onboarding and service CI still own proof that every business endpoint is represented and that client redirect policy cannot transition from HTTPS to HTTP; observability is not an egress firewall.

The host integration owns DNS, proxy, connect/read timeouts, TLS versions, cipher policy, certificate trust, hostname verification, certificate pinning if approved, and redirects. The starter may observe a request but cannot replace the transport, rebuild the client, or change these settings. The service must retain the standard JDK/client certificate-path validation and RFC-compliant hostname verification. Trust-all managers and permissive hostname verifiers are prohibited.

Redirect handling is client-owned and tested per integration. Cross-scheme redirects from HTTPS to HTTP are rejected. An integration may disable redirects entirely, or allow only a bounded same-scheme/same-approved-host policy. The SDK never follows a redirect independently and never retries a request on another scheme.

### RestTemplate

- The service constructs the `RestTemplate` and its `ClientHttpRequestFactory` with its approved TLS configuration.
- Instrumentation adds only a `ClientHttpRequestInterceptor`/bounded response wrapper. It reuses the original request factory and executes the request exactly once.
- Instrumentation never installs `BufferingClientHttpRequestFactory` globally and never creates or assigns an `SSLContext`, `SSLSocketFactory`, `TrustManager`, `HostnameVerifier`, Apache HttpClient TLS strategy, proxy, redirect strategy, or connection manager.
- Default JDK or explicitly service-configured certificate validation and hostname verification remain authoritative. Any custom CA is added only by the service-owned client configuration described below.

### WebClient

- The service owns the `WebClient.Builder`, `ClientHttpConnector`, Reactor Netty/JDK HTTP client, `SslProvider`, proxy, redirect policy, and connection pool.
- Instrumentation is an `ExchangeFilterFunction` and optional bounded body decorator only. It does not replace the connector or call SSL/TLS builder methods.
- Reactor Context carries observation identity; it is not used to configure TLS or choose a host.
- Certificate, hostname, protocol-negotiation, connect, cancellation, and timeout failures preserve the original reactive error signal. Observability classifies only safe structured failure metadata and never changes retry/fallback behavior.

### OkHttp

- The service owns the `OkHttpClient` and all TLS, `CertificatePinner`, proxy, DNS, redirect, connection-specification, and timeout settings.
- Instrumentation is an application interceptor. It calls `chain.proceed(request)` exactly once and does not create a replacement client.
- It never calls or replaces `sslSocketFactory`, `hostnameVerifier`, `certificatePinner`, `connectionSpecs`, `dns`, `proxy`, `followRedirects`, or `followSslRedirects`.
- It does not repeat a request body or TLS handshake to obtain observability data. Existing server certificate and hostname verification remain intact.

## Custom partner CA trust

The default JVM/platform trust store is preferred. A custom private partner CA is allowed only after security review and is owned by the host integration, not the starter.

The reviewed design is:

1. Store CA certificate material in an approved versioned artifact or Secrets Manager reference according to corporate certificate policy. Never commit a trust-store password or binary trust store.
2. Deliver it to the task through an ECS task-role-authorized, read-only, ephemeral path. A secret value is not passed through Terraform outputs, application properties committed to Git, command-line arguments, or telemetry.
3. Build a service-scoped trust store/`SSLContext` using normal certificate-path validation. Scope it to the intended partner client; do not change global JVM defaults unless a service-level security review explicitly approves that blast radius.
4. Retain hostname verification. Adding a CA never authorizes a hostname mismatch, expired/not-yet-valid certificate, invalid chain, weak protocol, or revocation-policy bypass.
5. Rotate trust anchors with an explicitly bounded overlap, test both chains in DEV/STAGE, roll the host service, then remove the retired anchor. A missing/invalid trust store fails the partner call; it does not trigger plaintext or trust-all fallback.

A trust store contains public trust anchors, not a client private key. Future client certificates and private keys for mTLS use a separate keystore/secret lifecycle.

## Inbound callback and webhook HTTPS

Every external callback route is exposed only through an architecture-approved ALB HTTPS listener on TCP 443. The host service infrastructure—not the observability modules or starter—owns the callback ALB, certificate, WAF/rate policy, target group, health check, and application security chain.

The ALB terminates external TLS and forwards only to private callback targets. Callback ECS tasks have no public IP and no public route. Their inbound security group permits the application port only from the callback ALB security group; it does not allow partner CIDRs or `0.0.0.0/0` directly. The ALB security group permits 443 only from approved partner/public sources. Target health and management ports are separately scoped.

The application may use a server-owned trusted-proxy facility to determine that the ingress hop was HTTPS. The SDK must not trust a caller-supplied `Forwarded` or `X-Forwarded-Proto` header. Network reachability from the ALB security group plus framework trusted-proxy configuration establishes the transport-boundary fact. This fact still does not authenticate the callback partner.

Callback capture remains ordered after host authentication/signature verification and trusted context resolution as defined in the callback ADR. TLS failure occurs at the ALB before the application and therefore produces no partner callback record. ALB/WAF/CloudWatch may retain internal-only bounded operational evidence under the account policy.

## ALB listener, ACM, and port 80 decision

- External partner callback and Grafana ALBs have an HTTPS listener on 443 only.
- There is no listener on port 80 and no security-group ingress rule for port 80. Redirect-only port 80 was rejected because callback clients may not preserve POST bodies/authentication across redirects and any plaintext first hop violates the hard invariant.
- The listener uses an organization-approved security policy with TLS 1.2 as the minimum. Weak protocols/ciphers are not enabled. Policy selection is pinned in Terraform and tested before promotion rather than inherited from a mutable default.
- Each DNS name has an ACM-managed certificate whose SAN set contains only approved names. Terraform references an ACM certificate ARN; it does not create, export, or read private key material.
- Public ACM certificates use DNS validation controlled through the approved account/domain workflow. ACM performs managed renewal. Certificate-expiry and renewal-failure alarms are internal-only, and renewal is verified in DEV/STAGE before any listener/certificate migration that changes names or trust.
- Listener rotation attaches the renewed/replacement certificate before removing the old certificate, validates the hostname and supported protocol, and uses ALB-managed atomic listener updates. Rollback reattaches the previous still-valid certificate. Private keys never reach ECS tasks when TLS terminates at ALB.

The observability `observability-network` Terraform module owns the Grafana ALB only. Existing partner-service infrastructure owns callback ALBs. M8 modules consume approved certificate ARNs and expose policy-test evidence; they do not create partner-service listeners or alter service TLS ownership.

## Security-group and routing model

Preventing direct internet access requires all of these controls, not a security group alone:

- ECS tasks use private subnets and `assign_public_ip=false`.
- Public route tables attach only to the ALB subnets. Task subnets have no internet-gateway route; controlled outbound partner HTTPS uses NAT or an approved egress proxy/firewall.
- Callback/Grafana target groups register private task addresses. Target security groups accept only their ALB security group.
- Loki, Prometheus, Alloy, query gateway, Actuator, EFS, S3/KMS endpoints, and management ports remain internal with exact security-group-to-security-group rules.
- Egress is restricted to approved partner HTTPS destinations through the environment's enforceable egress control where available, plus explicit AWS/internal dependencies. An outbound SG rule alone is not claimed to provide hostname enforcement.
- No ECS service exposes a task ENI directly through public DNS or a public IP.

## TLS ownership boundaries

| Concern | Owner | Observability starter behavior |
| --- | --- | --- |
| Partner endpoint URI and redirect policy | Host integration/service configuration | Reads configured API ID only; never rewrites URI/scheme |
| Outbound client TLS context/trust/hostname verification | Host integration and security owner | Reuses client unchanged; no SSL/TLS mutation |
| Callback/Grafana external TLS listener and ACM certificate | Host service infrastructure / `observability-network` for Grafana | No listener/certificate access |
| Callback authentication/signature/decryption | Host application security chain | Consumes only trusted result after it exists |
| Internal Alloy ingress TLS and source authentication | Observability platform | Dispatcher uses configured private TLS endpoint; business threads do not connect |
| CA/client-certificate/private-key lifecycle | Security/platform secret owners | Never loads, copies, logs, meters, serializes, or exports material |
| Safe transport outcome classification | Core/client adapter | Bounded enum from type/structured signal; preserves original failure |

Static dependency and source checks reject observability code that references trust-all implementations, permissive hostname verifiers, SSL context initialization, or client SSL setter methods outside explicitly test-only synthetic fixtures. Runtime integration tests compare client TLS configuration before and after starter activation.

## Failure classification and partner-safe metadata

A TLS failure must preserve the host client's original exception/error and business behavior. The SDK catches only its own observation failures. It does not retry a handshake, substitute a client, accept a certificate, suppress the original error, or fall back to HTTP.

Where the client exposes a structured/type-safe signal, an outbound response/acknowledgement terminal may record:

- `transportSecurity=TLS`;
- `outcome=TECHNICAL_FAILURE` and `statusClass=IO_ERROR`;
- `transportFailureClass` from `TLS_HANDSHAKE`, `TLS_CERTIFICATE_VALIDATION`, `TLS_HOSTNAME_VERIFICATION`, `TLS_PROTOCOL_NEGOTIATION`, `TLS_CONFIGURATION`, or `UNKNOWN_TLS`;
- configured `apiId`, attempt, monotonic duration, and normal safe correlation identifiers.

Classification inspects a bounded exception-cause chain by known types only. It never reads or emits an exception message, certificate, subject, issuer, SAN, serial number, fingerprint, cipher debug dump, trust-store path, peer address, request URL, header, response body, key material, or stack trace. If a client cannot distinguish certificate and hostname failures without parsing text, it emits the broader certificate/handshake classification rather than guessing; the hostname-specific value is reserved for an unambiguous structured signal.

Inbound TLS handshake failures terminate at ALB and are internal-only aggregate metrics/logs. They cannot be assigned to a partner tenant from an untrusted network attempt. After trusted callback context exists, transport metadata may state that the configured ingress boundary was `ALB_TLS`; it does not include a certificate identity.

## Future mTLS extension point

mTLS is not implemented by this architecture revision. The design permits it without changing telemetry records or business-path correlation:

- outbound clients may later receive a service-owned `KeyManager`/keystore alongside their existing trust configuration;
- ALB listener mTLS or a reviewed ingress proxy may later validate partner client certificates;
- a host authentication adapter may translate the trusted mTLS result into the existing opaque callback principal consumed by `CallbackPartnerContextResolver`;
- the market manifest can add a trust-adapter type and secret/certificate ARN references without storing certificate contents;
- certificate identity and rotation remain authentication/internal-audit data, not partner telemetry.

An mTLS change requires a new ADR, partner-by-partner certificate lifecycle/revocation design, load/performance evidence, and negative tests. The observability SDK still must not create or mutate key/trust managers.

## Configuration, audit, and verification

The non-secret market manifest declares only endpoint/API identifiers, HTTPS-required policy, approved host/port references, callback ALB/DNS ownership references, trust-adapter IDs, and secret/certificate ARNs. It contains no URI credentials, private key, trust-store password, certificate body, or token. Configuration and Terraform validation reject HTTP in DEV/STAGE/PROD and any port-80 listener/rule.

Required evidence includes:

- valid CA, expired/not-yet-valid/untrusted CA, incomplete chain, and hostname-mismatch tests for RestTemplate, WebClient, and OkHttp;
- HTTPS-to-HTTP redirect/downgrade denial and no independent interceptor retry;
- client TLS configuration equality before/after starter activation;
- callback ALB 443-only listener, ACM ARN, approved TLS policy, private target, `assign_public_ip=false`, and SG reachability policy tests;
- spoofed forwarding-header denial and proof that TLS transport does not replace callback authentication;
- secret scans covering Git, generated config, Terraform plan JSON, logs, telemetry, metrics, dashboards, and test reports;
- certificate renewal/rotation and custom-CA overlap/rollback drills using synthetic certificates;
- isolated local HTTP fixture checks proving no ECS/non-local profile can enable the exception.

The current starter evidence uses runtime-generated trusted, untrusted, expired, and wrong-host certificates for RestTemplate, Reactor Netty WebClient, and OkHttp with instrumentation disabled and enabled. Outcomes are identical; expired/untrusted/wrong-host connections fail; the service-owned RestTemplate request factory and WebClient connector are reused; and OkHttp retains its socket factory, trust manager, hostname verifier, certificate pinner, and connection specifications. Telemetry contains no synthetic certificate sentinel or secret-shaped material. Typed untrusted failures classify as certificate validation; where an expired-certificate client exposes only a generic timeout/transport exception, telemetry remains generic rather than parsing unsafe exception text. Production-source/static checks reject TLS context/trust/hostname setters, trust-all/permissive implementations, HTTPS-to-HTTP rewrite literals, tracked private keys, and sensitive Terraform outputs. Not-yet-valid/incomplete-chain cases, runtime redirect/downgrade policy, callback/ALB staging behavior, rotation, and end-to-end sink scans remain open evidence and are not claimed.

Failures in observability classification or export remain bounded telemetry loss. Failures in the business client's TLS validation remain business transport failures exactly as they were without the starter.
