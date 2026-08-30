# ECS Requirements

## Existing cluster integration

STAGE and PROD use the existing dedicated market/environment ECS cluster and VPC. Central
Terraform owns cluster integration, services, task definitions, capacity-provider/Fargate policy,
deployment controllers, service discovery, scaling, infrastructure health checks, and task
execution/runtime identities. It must not couple a partner service's health or rollout to any
observability component.

## Minimum runtime services

The baseline requires these independently restartable services, or centrally approved equivalent
task boundaries that preserve the same network/IAM/isolation properties:

| Service | Minimum containers | Initial state/scaling contract |
| --- | --- | --- |
| Alloy ingress | authenticating ingress proxy plus Alloy | Stateless; PROD baseline two tasks, STAGE baseline one; bounded horizontal scaling |
| Loki | Loki single-binary | One stateful task initially; S3 TSDB plus encrypted persistent WAL/cache/compactor work; no HA claim |
| Prometheus | Prometheus | One private stateful task initially; encrypted persistent TSDB; no HA claim |
| Grafana | Grafana OSS | One stateful task while SQLite is used; encrypted state and backup; ALB target |
| Query gateway | auth proxy, pinned Prometheus label proxy, stateless journey resolver | Stateless; PROD baseline two tasks, STAGE baseline one; bounded horizontal scaling |

The centralized repository may map these containers to an established sidecar/task pattern, but it
must not collapse trust boundaries so that Grafana reaches Loki/Prometheus directly, unauthenticated
traffic reaches Alloy, or a stateful component gains unsafe shared permissions.

## Task-definition interface

Every task definition must provide:

- immutable, approved image digests and a recorded version;
- CPU/memory and ephemeral/persistent storage values set per environment;
- private networking and no public IP;
- separate execution and component task roles;
- read-only references to versioned configuration artifacts plus expected SHA-256 digests;
- Secrets Manager/SSM references, never embedded secret values;
- explicit port mappings accessible only through the contract security groups;
- component health checks that fail the component deployment only;
- bounded internal CloudWatch log configuration and encryption/retention policy;
- persistent volume/access-point mounts only for Loki work state, Prometheus TSDB, and Grafana state;
- graceful stop/rollback settings compatible with stateful component upgrade guidance;
- enterprise-standard runtime hardening, image scanning, and tag/ownership metadata.

The application artifact contract must allow GHA to roll a new image/config digest without
recreating base networking or storage. A missing/invalid config may make the observability task
unhealthy but must never affect business-service readiness.

## Configuration interfaces

Tasks consume application-owned, versioned artifacts for:

- Alloy pipelines and ingress source/partner maps;
- Loki runtime policy/configuration;
- Prometheus configuration and recording/alert rules;
- Grafana provisioning, dashboards, and application alerts;
- query-gateway identity maps and bounded journey-resolver definitions.

Artifact transport may use the central repository's established S3, deployment bundle, or task
definition mechanism. It must provide integrity digests, rollback to a prior version, and least-
privilege read access. Secret maps are separate from non-secret artifacts.

## Health, rollout, and capacity

Central Terraform supplies infrastructure-level ECS and load-balancer health checks. GHA supplies
post-deployment application checks. Stateful upgrades require compatibility and restore review;
stateless services may use rolling or blue/green rollout. Initial stateful services do not
autoscale horizontally. Sustained CPU/memory/storage or query use above 70%, approaching the
64-partner cap, or an approved HA requirement triggers a reviewed architecture change rather than
silent scaling or database creation.
