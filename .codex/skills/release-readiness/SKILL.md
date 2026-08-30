---
name: release-readiness
description: Verify M10 documentation, packages, artifacts, compatibility, security, performance, and operational evidence before a release candidate is reviewed. Use for release-candidate preparation, package publication review, versioning, upgrade notes, or final milestone acceptance.
---

# Release Readiness

Gate a local release candidate without publishing, pushing, merging, or deploying.

## Load authoritative requirements

Read `AGENTS.md`, `PLANS.md`, `.agent-state/status.json`, `README.md`, every document under `docs/`, every ADR, build/publishing configuration, and previous verification evidence. Treat `docs/acceptance-criteria.md` and M10 in `PLANS.md` as the current release gates.

## Readiness workflow

1. Identify the intended version, supported upgrade path, included modules/artifacts, and claimed milestone scope.
2. Require a clean, reviewable worktree or explain every intentional uncommitted file. Do not push or merge.
3. Verify Java/Spring compatibility, one-dependency starter adoption, dependency convergence, reproducible archives, checksums, sources/Javadoc, license notices, SBOM/provenance if configured, and absence of credentials or environment-specific data.
4. Trace every acceptance criterion to current test evidence. Invoke the payload-safety,
   partner-security, Loki-isolation, performance-validation, test-adequacy, enterprise
   infrastructure/ECS, and dashboard gates when their scope is release-relevant.
5. Verify configuration reference, onboarding, upgrade, rollback, compatibility, troubleshooting, retention, security, and known-limit documentation.
6. Build artifacts locally and inspect their contents. Do not publish to a registry or repository and do not deploy infrastructure.

## Commands

```bash
git status --short --branch
git diff --check
rg -n "NOT IMPLEMENTED|TODO|TBD|FIXME" README.md PLANS.md docs scripts sure-partner-observability-* alloy loki prometheus grafana docker test
./scripts/build.sh
./scripts/test.sh
./scripts/test-security.sh
./scripts/test-performance.sh
./scripts/verify-all.sh
```

Also run `./gradlew clean check` and configured publication/archive verification when the wrapper exists. Inspect generated JAR/POM/module metadata rather than assuming a successful build means correct publication. Scan tracked files and artifacts with the repository's configured secret, dependency, and vulnerability tools.

A missing required command, `NOT IMPLEMENTED`, non-zero exit, missing evidence, unreviewed TODO affecting release behavior, or unavailable required artifact is `FAIL`. Do not claim readiness based on documentation alone.

## PASS/FAIL criteria

`PASS` requires every applicable acceptance and M10 exit criterion, passing functional/security/performance/end-to-end evidence, correct consumable artifacts, complete release/upgrade/rollback documentation, no unresolved release-blocking decision, no secret/unsafe payload, and no prohibited production action.

`FAIL` on any failed or skipped gate, incompatible dependency, non-reproducible or incomplete artifact, missing upgrade/rollback path, unbounded or unsafe default, unresolved blocking security/tenant issue, dirty unexplained release input, or publication/deployment attempted as part of validation.

Never weaken, delete, skip, quarantine, relax, or relabel tests, security controls, performance limits, compatibility requirements, or artifact checks merely to obtain `PASS`. Preserve the regression evidence and record the release blocker.

## Record valid findings

Use `.agent-state/status.json` state `VERIFYING` while gating. On a scoped pass, use `READY_FOR_REVIEW`, not `COMPLETE`; only a separately authorized project-completion decision may use `COMPLETE`. On failure, use `IN_PROGRESS`, unless the constitution's repeated-blocker rule truly permits `BLOCKED`. Preserve the schema, record version/evidence in `summary`, blockers accurately in `blockers`, and remediation in `nextActions`. Update M10 evidence in `PLANS.md` and unresolved choices in `docs/decisions-needed.md`.
