# Existing-market examples

`dev`, `stage`, and `prod` are validation-only compositions of the same reusable stack. All identifiers are synthetic placeholders, all partners are fictitious, and all container digests are non-runnable sentinels. They demonstrate configuration shape; they are not credentials or deployment authorization.

- DEV accepts only partners marked `dev_mock_only = true`; deployed DEV partner endpoints remain HTTPS.
- STAGE is independent from DEV and PROD even when it uses the same market label.
- PROD stays fail-closed because `production_deployment_enabled` is false. A human-controlled external workflow must provide both explicit enablement and a change reference.

Each environment supplies three partner entries with unique Loki tenants, `partner_slot` values, Grafana organizations, datasource secret ARNs, allowed service/API/callback names, and callback ALB ownership evidence. Adding a partner changes this non-secret manifest and generated artifact inputs only; it does not require Java or dashboard source changes.

Do not use the examples with `terraform apply`. Validation uses mocked providers and no AWS account.
