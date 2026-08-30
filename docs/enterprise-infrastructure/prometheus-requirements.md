# Prometheus Requirements

## Infrastructure owned by central Terraform

Central Terraform provides one initial private Prometheus ECS service/task per STAGE/PROD
deployment, encrypted persistent TSDB storage, task/execution roles, private service discovery,
security groups, health checks, bounded internal logs, storage alarms, and the configuration/rule
artifact interface.

Prometheus accepts remote writes only from Alloy and queries only from the query gateway and
approved internal operators. Admin and lifecycle APIs are disabled. Direct partner/browser/Grafana
network access is denied. Initial horizontal HA is not claimed.

## Application/runtime policy owned here

Sure Partner Observability owns:

- the Micrometer metric and bounded label/cardinality contract;
- Alloy scrape/relabel/allowlist semantics;
- 16-day time retention requirement and environment-approved size cap input;
- recording rules and application-owned alert rules;
- partner SLI queries and dashboard formulas;
- fixed `partner_slot` query enforcement requirements;
- no-data semantics and the prohibition on transaction identifiers/raw partner IDs in metrics.

GHA deploys validated Prometheus configuration and rules after the base runtime exists. Central
Terraform provides storage and runtime flags/interfaces but does not own application SLI semantics.

Sizing, storage cap, desired task resources, operational alarm thresholds, and backup/restore
policy are environment inputs. Metric gaps or Prometheus outage never alter business traffic.
