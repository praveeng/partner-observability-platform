# Runtime Profile and Environment Model

## Discovery result

Repository-wide inspection found no Spring profile definitions or selectors:

- no `spring.profiles.active` or `SPRING_PROFILES_ACTIVE`;
- no `spring.config.activate.on-profile`;
- no `@Profile` annotations;
- no `application-{profile}.yml` files.

The SDK's actual application environment enum is `DEV`, `STAGE`, and `PROD` in
`DeploymentEnvironment`. The synthetic test application sets
`partner-observability.environment: DEV` and `partner-observability.local-synthetic: true` in its
single `application.yml`. Local Alloy and Docker fixtures stamp `LOCAL_SYNTHETIC`; this is a guarded
execution/test identity, not a Spring profile or deployable ECS environment.

Therefore the expected `local/dev/stage/prod` model matches the repository only as deployment
intent. It does not match as Spring profiles:

| Intent | Actual repository representation | Contract effect |
| --- | --- | --- |
| LOCAL | Docker Compose, loopback test app, `local-synthetic=true`, `LOCAL_SYNTHETIC` telemetry label | No change; no AWS dependency |
| DEV | `DeploymentEnvironment.DEV`; AWS DEV is documented as HTTPS mock-partner only | No new infrastructure requirement |
| STAGE | `DeploymentEnvironment.STAGE` | Central Terraform requirements apply |
| PROD | `DeploymentEnvironment.PROD` | Central Terraform requirements apply with production controls |

No `staging`, `production`, or UAT runtime aliases were found. This task does not introduce or
rename Spring profiles. Adding Spring profile files later would be a separate application
configuration change and must preserve the enum and telemetry wire values.

## LOCAL

LOCAL remains entirely self-contained: the synthetic application, mock partners, Alloy, Loki,
Prometheus, Grafana, query gateway, integration/security/performance fixtures, Docker networks, and
disposable volumes run without AWS or enterprise Terraform. The isolated HTTP exception remains
limited to local loopback/Docker synthetic traffic.

## DEV

DEV remains the existing AWS mock-partner environment. This contract adds no DEV service, storage,
networking, IAM, secret, DNS, or load-balancer requirement. DEV is not a prerequisite for central
Terraform changes under this task. Existing HTTPS and isolation rules still apply to DEV.

## STAGE and PROD

STAGE and PROD use the same component architecture and application artifacts. Central Terraform
supplies environment-specific base infrastructure; configuration selects market, sizing, scaling,
DNS/certificates, partners, allowlists, secret references, task counts, thresholds, and maintenance
controls. Infrastructure and telemetry must never cross an account/market/environment boundary.
