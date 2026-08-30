# Alloy Requirements

## Infrastructure owned by central Terraform

Central Terraform provides the base Alloy ECS service/task, authenticating ingress sidecar/runtime
boundary, private load balancer or approved internal equivalent, service discovery, task/execution
roles, source-service security-group access, health checks, bounded autoscaling, image/config
interfaces, secret references, and internal operational logs.

STAGE starts with one stateless task; PROD starts with at least two across failure domains where the
enterprise cluster pattern supports it. Scaling is bounded and must not create an unbounded ingest
or retry buffer. Source services connect only through the private authenticated endpoint.

## Application/runtime policy owned here

Sure Partner Observability owns the versioned Alloy pipeline logic:

- source identity plus canonical partner route mapping;
- second-stage schema validation, sanitization, masking, and prohibited-content dropping;
- one fixed Loki tenant route per partner;
- removal of client tenant/routing fields;
- Loki labels and structured-metadata allowlists;
- Micrometer target discovery contract, metric/label allowlists, trusted label overwrite, and
  remote-write policy;
- bounded queue, timeout, retry, and application health/self-metric semantics.

GHA deploys the validated pipeline/proxy artifacts and digests after the base service exists.
Central Terraform owns how a task obtains those artifacts and secrets, not their processing policy.

Alloy failure causes bounded telemetry loss only. It must not be a business readiness dependency,
and no application request thread may call it synchronously.
