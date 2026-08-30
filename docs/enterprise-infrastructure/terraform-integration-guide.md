# Central Terraform Integration Guide

## Mandatory first step

The future Terraform integration engineer must inspect the centralized enterprise Terraform
repository before proposing code. Reuse its established modules and patterns for ECS, networking,
ALB/ACM/DNS/WAF, IAM, encryption, S3/EFS, secrets, CloudWatch, service discovery, tagging, state,
account/environment composition, review, and manual execution. Do not copy the retired module
names or structure from this repository as a requirement.

## Integration workflow

1. Map every requirement and machine-contract input/output to an existing central module/pattern.
2. Identify genuine gaps generically before proposing a new enterprise module.
3. Resolve the target market/account/region, existing cluster/VPC/subnets, DNS/certificate/WAF,
   partner allowlists, secret ownership, artifact registry, sizing, backups, alerts, and GHA output
   transport.
4. Produce a central-repository Terraform change for STAGE only, using approved remote state and
   provider conventions.
5. Run central repository formatting, validation, lint/security/policy, cost, and plan checks.
6. Have a human review the saved STAGE plan, including public exposure, IAM, encryption, secrets,
   replacement/data-loss, and cost.
7. Manually execute through the central workflow; do not use this repository.
8. Validate base health and publish the non-secret output/change reference for application GHA.
9. Deploy and validate application assets through GHA.
10. Repeat for PROD only after STAGE evidence and production approval.

## Prohibitions

- Do not add enterprise `.tf` files, Terraform state, lock files, provider downloads, plans, or
  apply scripts to Sure Partner Observability.
- Do not run Terraform from Sure Partner Observability CI or local completion gates.
- Do not discover or use AWS credentials from this repository.
- Do not embed secret values, certificates, private keys, trust stores, partner data, or state in
  Terraform inputs/outputs.
- Do not change LOCAL or DEV while implementing this Stage/Prod contract.
- Do not introduce Kubernetes or Helm.

## Acceptance handoff

The central change must return a requirements trace, reviewed plan evidence, outputs listed in the
machine contract, manual execution/change reference, base health evidence, and known deviations.
Application GHA then owns runtime asset deployment and its own post-deployment tests. Neither side
may claim whole-platform readiness from infrastructure checks alone.
