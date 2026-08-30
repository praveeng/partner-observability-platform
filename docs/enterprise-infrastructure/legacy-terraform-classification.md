# Retired Repository Terraform Classification

## Classification performed before removal

The former `terraform/` tree and `test/terraform/` fixture were inspected before retirement.

| Former content | Class | Disposition |
| --- | --- | --- |
| `terraform/modules/*` ECS, network, IAM, storage, alarm, and composition modules | A — enterprise infrastructure code | Requirements migrated into this contract; implementation removed from active repository ownership |
| `terraform/examples/dev`, `stage`, `prod`, and `shared` | A — enterprise infrastructure code presented as validation examples | Environment guards and inputs migrated; examples removed. No DEV requirement was carried forward. |
| `terraform/M8-EVIDENCE.md`, READMEs, and module tables | C — documentation/evidence describing the former implementation | Security/topology conclusions preserved in ADR/history and this contract; executable instructions removed |
| mocked-provider network test and `test/terraform/static-policy.sh` | D — obsolete after ownership transfer | Removed because they validated infrastructure code this repository must no longer own |
| local Docker/Alloy/Loki/Prometheus/Grafana assets | B — genuine local/test fixtures, outside `terraform/` | Preserved unchanged; they do not provision AWS |

No former Terraform file was required to run the local application, Docker Compose, Grafana,
end-to-end, security, or performance workflows. The previous M8 evidence remains valid historical
evidence about the design that was inspected, but it is no longer evidence that this repository
owns or can deploy enterprise infrastructure.

`scripts/test-terraform.sh` is retained only as a compatibility entry point for the local
enterprise-infrastructure contract validator. It invokes no Terraform CLI and requires no AWS
access. New automation should call `scripts/test-enterprise-infrastructure-contract.sh` directly.
