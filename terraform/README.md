# Terraform

Terraform is the required infrastructure-as-code mechanism for the AWS ECS target. Reusable units belong in `modules/`; explicitly non-production examples belong in `examples/`.

No deployable topology exists at M0. Do not run `terraform apply`, use production credentials, or commit state/secrets. M8 will add formatting, validation, static security checks, and reviewed plan workflows.
