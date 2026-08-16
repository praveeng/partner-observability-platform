# Project Constitution for Autonomous Agents

## Scope and authority

This file governs the entire repository. It is the highest-authority repository-local instruction for autonomous agents. More specific `AGENTS.md` files may add constraints but may not weaken this constitution. If code, configuration, documentation, or a task request conflicts with these rules, stop and record the conflict in `docs/decisions-needed.md`.

The project supplies partner-isolated application observability for Java 17 / Spring Boot 2.7 services, with asynchronous telemetry collection and an AWS ECS deployment target. It is not a business transaction system and must never become part of the success path for business traffic.

## Required reading before changing the repository

1. Read this file completely.
2. Read `.agent-state/status.json` and `PLANS.md`.
3. Read the documents relevant to the change, especially security, payload, telemetry, isolation, and deployment contracts.
4. Inspect `git status` and preserve unrelated work.
5. Resolve or explicitly record material ambiguity in `docs/decisions-needed.md`; do not silently invent security-sensitive behavior.

## Non-negotiable invariants

1. Business availability has priority over observability.
2. There is no synchronous dependency from business traffic to Grafana Alloy, Loki, Prometheus, or Grafana.
3. Every telemetry queue is bounded.
4. Queue saturation drops telemetry instead of blocking business traffic.
5. Observability exceptions never propagate into business logic.
6. Partner isolation is enforced server-side; client-side filtering is never a security boundary.
7. There is one Loki tenant per partner.
8. Unknown unsafe content fails closed for disclosure: it is omitted unless explicitly classified as safe.
9. Credentials, secrets, one-time passwords (OTP), and card data are removed completely, never merely masked.
10. Phone numbers, email addresses, account numbers, national identifiers, and addresses are masked.
11. Images, documents, PDFs, binary content, and Base64-encoded content are never captured.
12. Binary values are excluded before entering any telemetry queue.
13. `applicationId`, `loanId`, `correlationId`, and `requestId` are not normal Loki labels because of cardinality.
14. High-cardinality transaction identifiers use Loki structured metadata or an equivalent appropriate mechanism, subject to the telemetry and payload contracts.
15. Normal Spring Boot integration requires one starter dependency plus configuration.
16. Java 17 and Spring Boot 2.7 compatibility is mandatory.
17. Helm is prohibited. This repository has no Kubernetes deployment model.
18. Terraform is required for provisioned infrastructure.
19. Production deployment and production credentials are prohibited from agent activity in this repository.
20. Codex agents may commit locally but must not push or merge.

These invariants are acceptance gates. Tests that contradict them are defective; an agent must not relax an invariant merely to make a check pass.

## Architectural boundaries

- `partner-observability-core`: framework-independent telemetry model, policy, sanitization, bounded buffering, and emission abstractions.
- `partner-observability-spring-boot-autoconfigure`: Spring Boot 2.7 conditional configuration and integration points.
- `partner-observability-spring-boot-starter`: the single dependency intended for ordinary consumers; it contains dependency wiring, not business behavior.
- `partner-observability-test-app`: synthetic, non-production verification application only.
- `alloy`, `loki`, `prometheus`, and `grafana`: local/integration configuration owned by the corresponding component.
- `terraform`: reusable AWS ECS-oriented modules and non-production examples.
- `test`: cross-module integration, security, performance, and end-to-end assets.

Dependencies must point inward: the starter may expose autoconfiguration, autoconfiguration may depend on core, and core must not depend on Spring. Application instrumentation must enqueue an already-safe bounded representation without waiting for network or backend acknowledgement.

## Security and telemetry working rules

- Treat payloads, headers, query strings, exceptions, and metadata as untrusted and potentially sensitive.
- Apply type/size rejection and binary/Base64 exclusion before queue admission. Apply removal and masking before emission. Raw unsafe values must not appear in fallback logs or error messages.
- Partner identity comes from authenticated, server-side trusted context. Never accept a tenant selection solely from a request parameter, arbitrary header, dashboard variable, or client-supplied log field.
- Default to allowlisted fields and deny disclosure of unknown fields or types.
- Keep Loki indexed labels low-cardinality and bounded. Any proposed label needs a documented cardinality analysis.
- Metrics must not contain raw partner identifiers or transaction identifiers unless an approved bounded mapping is documented.
- Use only synthetic fixtures. Never add real customer or production-derived data.
- Do not print secrets or payload samples during tests. Secret-shaped test values must be unmistakably synthetic.

## Agent workflow and lifecycle

The lifecycle values are `BOOTSTRAPPING`, `IN_PROGRESS`, `VERIFYING`, `BLOCKED`, `READY_FOR_REVIEW`, and `COMPLETE`.

- Set `IN_PROGRESS` before material milestone implementation.
- Set `VERIFYING` while running the full checks required by the active milestone.
- Use `BLOCKED` only with a concrete blocker and next action recorded in the state file.
- Use `READY_FOR_REVIEW` when implementation and available checks are complete but human review or explicitly deferred milestone work remains.
- Use `COMPLETE` only when the current declared scope and every required acceptance check are complete; never use it for the entire product while later milestones remain.

Keep `.agent-state/status.json` valid JSON and update its timestamp, milestone, summary, blockers, and next actions whenever lifecycle state changes. Update `PLANS.md` and relevant documentation in the same change as architectural or scope decisions.

## Change and verification discipline

- Work in the smallest coherent milestone or task slice.
- Add tests with behavior. Security-sensitive behavior requires negative tests proving prohibited data is absent and failures do not reach business logic.
- Run the narrowest relevant checks first, followed by `scripts/verify-all.sh` when its constituent milestone checks are implemented.
- A missing check must report `NOT IMPLEMENTED` and return non-zero; never replace verification with a false success.
- Do not claim tests passed unless they were run in the current worktree. Record unavailable tooling or environment limitations.
- Keep documentation executable as a contract: names, defaults, queue limits, trust boundaries, and commands must agree with code and configuration.
- Do not introduce Kubernetes manifests, Helm charts, synchronous exporters on request threads, unbounded executors/queues, or high-cardinality labels.

## Git and external-action policy

- Preserve existing user changes and never use destructive Git operations to discard them.
- Local commits are permitted when requested or useful to an autonomous milestone workflow.
- Never push, merge, create a remote release, deploy infrastructure, or use production credentials.
- Do not commit generated build output, local state, secrets, `.env` files, Terraform state, or captured telemetry.

## Definition of done for any later milestone

A milestone is ready for review only when its documented acceptance criteria are met, relevant automated checks are real and passing, security invariants remain enforced, documentation and agent state are current, the worktree contains no accidental artifacts, and unresolved choices are recorded rather than hidden.
