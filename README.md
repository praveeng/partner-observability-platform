# Sure Partner Observability

Repository machine name: `sure-partner-observability`.

Repository for a partner-isolated observability SDK and platform targeting Java 17, Spring Boot 2.7.x, Grafana Alloy, Loki, Prometheus, Grafana, and AWS ECS. Enterprise AWS Terraform is owned by a separate centralized repository; this repository publishes its STAGE/PROD [infrastructure requirements contract](docs/enterprise-infrastructure/README.md).

Partner services consume `com.samsung.sure:sure-partner-observability-spring-boot-starter:<version>` and import public SDK types only from `com.samsung.sure.partner.observability.*`. See the [enterprise naming migration record](docs/enterprise-naming-migration.md).

The scoped **M2-M6 SDK, integration, and local data-plane slices** are ready for review. Core provides schema-2 async/callback records and correlation, the Spring Boot 2.7 starter instruments configured application paths, and the LOCAL_SYNTHETIC Compose stack provides trusted Alloy routing, one Loki tenant per synthetic partner, and scrape-based Prometheus API/callback health metrics. Encrypted services can use the typed, fail-open hooks in [the encrypted-service migration guide](docs/encrypted-service-migration.md). Grafana query authorization, deployment policy evidence, the remaining certificate matrix, and full-duration performance remain later milestone work. See [PLANS.md](PLANS.md) and [the machine-readable state](.agent-state/status.json).

## Core promise

Observability must never reduce business availability. Application traffic writes only to bounded in-process telemetry paths; saturation drops telemetry, backend failures are contained, and no request waits synchronously for Alloy, Loki, Prometheus, or Grafana. Sensitive content is removed or masked before telemetry queue admission, and partner isolation is enforced by trusted server-side identity with one Loki tenant per partner. External partner traffic is HTTPS-only; the starter never weakens or mutates host TLS configuration.

## Repository map

| Path | Purpose |
| --- | --- |
| `sure-partner-observability-core` | Framework-independent SDK contracts and safety mechanisms |
| `sure-partner-observability-spring-boot-autoconfigure` | Spring Boot 2.7 integration and conditional configuration |
| `sure-partner-observability-spring-boot-starter` | Single-dependency consumer entry point |
| `sure-partner-observability-test-app` | Synthetic verification application |
| `alloy`, `loki`, `prometheus`, `grafana` | Local/integration observability component configuration |
| `docker` | Docker Compose local integration environment |
| `test` | Cross-component integration, security, performance, and synthetic fixtures |
| `docs` | Product, architecture, security, telemetry, deployment, and acceptance contracts |
| `docs/enterprise-infrastructure` | STAGE/PROD requirements for the centralized enterprise Terraform repository and GHA handoff |
| `docs/transport-security.md` | HTTPS-only client, ALB/ACM, certificate, secret, and TLS ownership contract |
| `docs/security-review.md` | Adversarial production-security findings, 84 attack dispositions, fixes, and blockers |
| `.agent-state` | Machine-readable autonomous-agent handoff state |

## Agent entry point

Read [AGENTS.md](AGENTS.md) in full before making changes. Then inspect `.agent-state/status.json`, [PLANS.md](PLANS.md), and [open decisions](docs/decisions-needed.md). Repository state, not prior chat context, is the source of truth.

## Verification commands

Prerequisites are Java 17, Bash, and Docker Compose for the local platform suites. Terraform is not
required or executed by this repository. A Gradle wrapper is included for reproducibility.

```bash
./scripts/build.sh
./scripts/test.sh
./scripts/test-enterprise-naming.sh
./scripts/test-enterprise-infrastructure-contract.sh
./scripts/test-security.sh --core  # implemented M2 pre-queue/trusted-context scope
./scripts/test-security.sh --data-plane  # implemented M5 real Alloy/Loki scope
./scripts/test-security.sh --metrics-plane  # implemented M6 Alloy/Prometheus scope
./scripts/test-security.sh         # non-zero until remaining M7-M9 gates exist
./scripts/test-performance.sh      # non-zero until M9 profiles exist
./scripts/verify-all.sh        # non-zero until every required suite exists
```

Documentation/static state can be checked without claiming later milestone coverage:

```bash
./gradlew projects
bash -n scripts/*.sh
jq empty .agent-state/status.json
```

## Constraints

- Java 17; Spring Boot 2.7.x; Gradle Groovy DSL.
- SLF4J/Logback for application-side logging.
- Docker Compose locally; AWS ECS for STAGE/PROD, provisioned by the separate centralized
  enterprise Terraform repository under `docs/enterprise-infrastructure/`.
- No Kubernetes and no Helm.
- No production deployment, production credentials, or real partner/customer data.
- Agents may commit locally and may push completed, verified work from a `READY_FOR_REVIEW` or `COMPLETE` scope to an existing remote branch. Force pushes, merges, protected-branch bypass, releases, and deployments remain prohibited.

The project is licensed under Apache License 2.0; see [LICENSE](LICENSE).
