# Partner Observability Platform

Foundation repository for a partner-isolated observability SDK and platform targeting Java 17, Spring Boot 2.7.x, Grafana Alloy, Loki, Prometheus, Grafana, Terraform, and AWS ECS.

The repository is at **M0: foundation ready for review**. It intentionally contains no product implementation. See [PLANS.md](PLANS.md) and [the machine-readable state](.agent-state/status.json).

## Core promise

Observability must never reduce business availability. Application traffic writes only to bounded in-process telemetry paths; saturation drops telemetry, backend failures are contained, and no request waits synchronously for Alloy, Loki, Prometheus, or Grafana. Sensitive content is removed or masked before telemetry queue admission, and partner isolation is enforced by trusted server-side identity with one Loki tenant per partner.

## Repository map

| Path | Purpose |
| --- | --- |
| `partner-observability-core` | Framework-independent SDK contracts and safety mechanisms |
| `partner-observability-spring-boot-autoconfigure` | Spring Boot 2.7 integration and conditional configuration |
| `partner-observability-spring-boot-starter` | Single-dependency consumer entry point |
| `partner-observability-test-app` | Synthetic verification application |
| `alloy`, `loki`, `prometheus`, `grafana` | Local/integration observability component configuration |
| `terraform` | AWS ECS modules and non-production examples |
| `docker` | Docker Compose local integration environment |
| `test` | Cross-component integration, security, performance, and synthetic fixtures |
| `docs` | Product, architecture, security, telemetry, deployment, and acceptance contracts |
| `.agent-state` | Machine-readable autonomous-agent handoff state |

## Agent entry point

Read [AGENTS.md](AGENTS.md) in full before making changes. Then inspect `.agent-state/status.json`, [PLANS.md](PLANS.md), and [open decisions](docs/decisions-needed.md). Repository state, not prior chat context, is the source of truth.

## Foundation commands

Prerequisites are Java 17, Bash, and Docker Compose/Terraform only when their milestones are implemented. A Gradle wrapper is included for reproducibility.

```bash
./scripts/build.sh
./scripts/test.sh
./scripts/test-security.sh     # explicitly NOT IMPLEMENTED at M0
./scripts/test-performance.sh  # explicitly NOT IMPLEMENTED at M0
./scripts/verify-all.sh        # non-zero until every required suite exists
```

The M0 scaffold can be checked without claiming later milestone coverage:

```bash
./gradlew projects
bash -n scripts/*.sh
python3 -m json.tool .agent-state/status.json
```

## Constraints

- Java 17; Spring Boot 2.7.x; Gradle Groovy DSL.
- SLF4J/Logback for application-side logging.
- Docker Compose locally; Terraform and AWS ECS as the deployment model.
- No Kubernetes and no Helm.
- No production deployment, production credentials, or real partner/customer data.
- Agents may commit locally but must never push or merge.

The project is licensed under Apache License 2.0; see [LICENSE](LICENSE).
