# Runtime Profile and Environment Model

## Canonical model

The canonical Spring profiles are `local`, `dev`, `stage`, and `prod`. Runnable applications use
`application.properties` and one `application-{profile}.properties` file for each profile; Spring
application YAML is prohibited. Profile activation is external through
`SPRING_PROFILES_ACTIVE=<profile>`, so the same immutable artifact is promoted between environments.

The Java `DeploymentEnvironment` enum uses `LOCAL`, `DEV`, `STAGE`, and `PROD`. Its canonical
telemetry/configuration values are lowercase: `local`, `dev`, `stage`, and `prod`. The
`LOCAL_SYNTHETIC` wording may still identify synthetic fixtures or test credentials, but it is not
a Spring profile or a `deployment.environment` value.

| Profile | Runtime | Partner | Enterprise infrastructure effect |
| --- | --- | --- | --- |
| `local` | Local VM and local Docker components; LocalStack/Testcontainers only where needed | Local/in-process mock | No AWS or enterprise Terraform dependency |
| `dev` | Dedicated AWS DEV ECS cluster and VPC | AWS-hosted mock | No new infrastructure requirement from the Stage/Prod contract |
| `stage` | Dedicated AWS STAGE ECS cluster and VPC | Real partner staging | Central Terraform requirements apply |
| `prod` | AWS production cluster and network | Real partner production | Central Terraform requirements apply with production controls |

DEV and STAGE for a market may be in the same AWS account. That does not permit shared clusters,
VPCs, environment resources, runtime configuration, tenants, partner endpoints, or secrets.

## Local safety boundary

The synthetic test application activates `local` explicitly. Its loopback mock partner, local
Alloy, Loki, Prometheus, Grafana, query gateway, integration tests, security tests, and performance
fixtures require no live AWS service. The `partner-observability.local-synthetic=true` HTTP
exception validates only with `DeploymentEnvironment.LOCAL` and a literal loopback origin.

## Non-local configuration

The checked-in DEV/STAGE/PROD profile files provide canonical identity and safe disabled defaults.
The approved runtime/GHA configuration artifact supplies the non-secret partner/tenant/API manifest
and secret references before enabling capture. DEV points only to its HTTPS mock partner. STAGE and
PROD point only to their corresponding real partner environment and use runtime-injected secrets.
No test or context validation makes a real network or AWS call.

Spring profile alignment changes application configuration only. It does not add DEV Terraform or
move Stage/Prod infrastructure ownership into this repository.
