# Sure Partner Observability

Repository machine name: `sure-partner-observability`.

Repository for a partner-isolated observability SDK and platform targeting Java 17, Spring Boot 2.7.x, Grafana Alloy, Loki, Prometheus, Grafana, and AWS ECS. Enterprise AWS Terraform is owned by a separate centralized repository; this repository publishes its STAGE/PROD [infrastructure requirements contract](docs/enterprise-infrastructure/README.md).

Partner services in this checkout consume `com.samsung.sure:sure-partner-observability-spring-boot-starter:0.1.0-SNAPSHOT` and import public SDK types only from `com.samsung.sure.partner.observability.*`. See the [enterprise naming migration record](docs/enterprise-naming-migration.md).

The scoped **M2-M6 SDK, integration, and local data-plane slices** are ready for review. Core provides schema-2 async/callback records and correlation, the Spring Boot 2.7 starter instruments configured application paths, and the LOCAL_SYNTHETIC Compose stack provides trusted Alloy routing, one Loki tenant per synthetic partner, and scrape-based Prometheus API/callback health metrics. Encrypted services can use the typed, fail-open hooks in [the encrypted-service migration guide](docs/encrypted-service-migration.md). The full B003 harness is documented in [performance validation](docs/performance-validation.md); B003 remains open until its complete unshortened evidence passes. See [PLANS.md](PLANS.md) and [the machine-readable state](.agent-state/status.json).

## Core promise

Observability must never reduce business availability. Application traffic writes only to bounded in-process telemetry paths; saturation drops telemetry, backend failures are contained, and no request waits synchronously for Alloy, Loki, Prometheus, or Grafana. Sensitive content is removed or masked before telemetry queue admission, and partner isolation is enforced by trusted server-side identity with one Loki tenant per partner. External partner traffic is HTTPS-only; the starter never weakens or mutates host TLS configuration.

## Repository map

| Path | Purpose |
| --- | --- |
| `sure-partner-observability-core` | Framework-independent SDK contracts and safety mechanisms |
| `sure-partner-observability-spring-boot-autoconfigure` | Spring Boot 2.7 integration and conditional configuration |
| `sure-partner-observability-spring-boot-starter` | Single-dependency consumer entry point |
| `sure-partner-observability-test-app` | Synthetic verification application |
| `sure-partner-observability-reactive-test-app` | Test-only WebFlux streaming/callback performance fixture |
| `sure-partner-observability-local-test-support` | Optional `local`-only fixed-route transport for one selected real service |
| `alloy`, `loki`, `prometheus`, `grafana` | Local/integration observability component configuration |
| `docker` | Docker Compose local integration environment |
| `test` | Cross-component integration, security, performance, and synthetic fixtures |
| `docs` | Product, architecture, security, telemetry, deployment, and acceptance contracts |
| `docs/enterprise-infrastructure` | STAGE/PROD requirements for the centralized enterprise Terraform repository and GHA handoff |
| `docs/transport-security.md` | HTTPS-only client, ALB/ACM, certificate, secret, and TLS ownership contract |
| `docs/security-review.md` | Adversarial production-security findings, 84 attack dispositions, fixes, and blockers |
| `.agent-state` | Machine-readable autonomous-agent handoff state |

## Agent entry point

Read [AGENTS.md](AGENTS.md) in full before making changes. Then inspect `.agent-state/status.json`, [PLANS.md](PLANS.md), and [open decisions](docs/decisions-needed.md). Repository state, not prior chat context, is the source of truth.

## Build, deploy, and verify

Run every command in this section from the repository root in Bash on Linux or WSL. PowerShell and
`cmd.exe` are not supported by the repository scripts. The two runnable applications are synthetic
test fixtures, not applications to deploy to DEV, STAGE, or PROD.

The current checkout has a complete executable local model but only an enterprise deployment
**contract** for non-local environments:

- there is no `.github/workflows` directory, enterprise deployment script, Dockerfile, AWS CLI
  command, or repository-owned Terraform implementation;
- no DEV, STAGE, or PROD hostname is checked in, and no non-local profile defines a hostname
  environment variable;
- the centralized enterprise Terraform repository must create STAGE/PROD base infrastructure, and
  an enterprise GitHub Actions release must subsequently deploy application/runtime assets;
- B003 full-duration performance evidence and remaining production transport evidence are still
  open. This repository is not currently a production-release authorization.

Consequently, a human can build, run, test, and inspect LOCAL from this checkout. A human cannot
deploy DEV/STAGE/PROD from this checkout alone. The non-local procedures below identify the exact
required release boundary, evidence, and stop conditions so missing external integration is never
mistaken for a successful deployment.

### Prerequisites

The normal build needs Git, Bash, Java 17, and the included Gradle 7.6.4 wrapper. Local platform
tests additionally need a reachable Docker daemon with Docker Compose v2, `curl`, `jq`, and
`ripgrep` (`rg`). The performance harness additionally needs Python 3, standard Linux tools
(`awk`, `date`, `nproc`, `sha256sum`), at least 8 logical CPUs and 12 GiB available to both the host
and Docker for full mode, and the pinned K6 0.49.0 container image. A host-installed `k6` binary,
AWS CLI, Terraform CLI, LocalStack, and Testcontainers are not required by the current commands.
Target-service OpenAPI preparation additionally requires Python 3 with PyYAML. It is not a
prerequisite for standalone generic builds or local validation.

Check the prerequisites before starting:

```bash
git --version
bash --version
java -version
./gradlew --version
docker version
docker compose version
docker info
curl --version
jq --version
rg --version
python3 --version
```

`java -version` must report Java 17, `./gradlew --version` must report Gradle 7.6.4, Docker Compose
must report a 2.x version, and `docker info` must succeed for the current user. The first wrapper
run downloads Gradle if it is not already cached. If the default Gradle home is read-only, use a
writable task-specific cache:

```bash
GRADLE_USER_HOME=/tmp/partner-observability-gradle ./gradlew --version
```

The performance script deliberately does not pull K6 automatically. Prepare and verify it before a
smoke or full run:

```bash
docker pull grafana/k6:0.49.0
docker run --rm grafana/k6:0.49.0 version
```

### Human-relevant project structure

The [repository map](#repository-map) is exhaustive enough for navigation. Operationally, these
are the important boundaries:

- `sure-partner-observability-core` is the framework-independent telemetry, sanitization,
  bounded-buffering, and publication library.
- `sure-partner-observability-spring-boot-autoconfigure` supplies Spring Boot 2.7 integration,
  HTTP client/callback instrumentation, Micrometer, and Actuator integration.
- `sure-partner-observability-spring-boot-starter` is the single dependency a partner service
  consumes. It contains dependency wiring, not a deployable server.
- `sure-partner-observability-test-app` is the runnable MVC synthetic application used for manual
  local flows and end-to-end evidence.
- `sure-partner-observability-reactive-test-app` is the runnable WebFlux fixture used by reactive
  and B003 performance tests.
- `sure-partner-observability-local-test-support` is a non-production development dependency for
  the explicitly selected real-service mode. It supplies one fixed `local` route on the bounded
  dispatcher thread and is never a replacement for the ordinary one-starter deployed integration.
- `docker/compose.yml` and `alloy/`, `loki/`, `prometheus/`, and `grafana/` define the local
  observability platform. `test/integration/` drives its disposable real-container checks.
- `docs/enterprise-infrastructure/` defines the STAGE/PROD handoff to centralized Terraform and
  enterprise GitHub Actions. It is requirements documentation, not deployable infrastructure.

### Build manually

Inspect the checkout and perform a clean build:

```bash
git status --short
./gradlew --no-daemon clean build
```

`BUILD SUCCESSFUL` is the pass condition. `./scripts/build.sh` is the repository wrapper for that
same clean build:

```bash
./scripts/build.sh
```

Build only the libraries, or only one runnable boot JAR, with the discovered Gradle tasks:

```bash
./gradlew --no-daemon \
  :sure-partner-observability-core:build \
  :sure-partner-observability-spring-boot-autoconfigure:build \
  :sure-partner-observability-spring-boot-starter:build

./gradlew --no-daemon :sure-partner-observability-test-app:bootJar
./gradlew --no-daemon :sure-partner-observability-reactive-test-app:bootJar
```

Version `0.1.0-SNAPSHOT` is set in the root `build.gradle`. A successful build produces:

| Artifact | Output |
| --- | --- |
| Core library | `sure-partner-observability-core/build/libs/sure-partner-observability-core-0.1.0-SNAPSHOT.jar` |
| Auto-configuration library | `sure-partner-observability-spring-boot-autoconfigure/build/libs/sure-partner-observability-spring-boot-autoconfigure-0.1.0-SNAPSHOT.jar` |
| Consumer starter | `sure-partner-observability-spring-boot-starter/build/libs/sure-partner-observability-spring-boot-starter-0.1.0-SNAPSHOT.jar` |
| Local test support | `sure-partner-observability-local-test-support/build/libs/sure-partner-observability-local-test-support-0.1.0-SNAPSHOT.jar` |
| MVC synthetic boot JAR | `sure-partner-observability-test-app/build/libs/sure-partner-observability-test-app-0.1.0-SNAPSHOT.jar` |
| WebFlux synthetic boot JAR | `sure-partner-observability-reactive-test-app/build/libs/sure-partner-observability-reactive-test-app-0.1.0-SNAPSHOT.jar` |

Confirm the outputs without assuming an artifact:

```bash
find sure-partner-observability-* -path '*/build/libs/*.jar' -type f -print | sort
```

The repository does not define Maven publication, an enterprise container build, or a remote
artifact publication command. Artifact publication belongs to the future enterprise release
workflow and must not be improvised from a checkout.

### Actual deployment model

LOCAL is deployed manually as disposable Docker Compose services by the repository runners below.
For STAGE and PROD, the only approved order is:

1. Change the separate centralized enterprise Terraform repository.
2. Have a human review the saved plan, isolation, cost, and replacement/data-loss impact.
3. Have an authorized human manually execute Terraform in that central workflow.
4. Require healthy base infrastructure and the non-secret
   `infrastructure-version-or-change-reference` plus every required output in
   `docs/enterprise-infrastructure/infrastructure-contract.yaml`.
5. Run the protected enterprise GitHub Actions application release.
6. Let that release deploy immutable runtime/task artifacts, Alloy/Loki/Prometheus/query-gateway
   configuration, `grafana/dashboards/`, `grafana/alerts/` when definitions exist, and Prometheus
   rules, then run post-deployment health, routing, isolation, dashboard, and rollback validation.

The current architecture has no application database, JDBC/R2DBC dependency, or Liquibase
changelog, so no current release step runs Liquibase. If an approved application schema is added
later, enterprise GitHub Actions—not Terraform and not a local operator—would run its migration
after the base database exists.

There is no tracked GitHub Actions workflow name, trigger, branch mapping, deployment input, or
enterprise authentication interface to invoke today. Do not substitute `aws ecs`, Terraform, a
manual container copy, or direct Grafana edits. Until the external release integration supplies
those details, repository-driven DEV/STAGE/PROD deployment is a hard stop.

### Local

#### Two explicit local testing modes

`GENERIC` is the default and remains authoritative for B001, B002, B003, adversarial payloads,
queue/sanitizer failures, reactive stress, and tenant-collision fixtures. It uses only
`sure-partner-observability-test-app` and `sure-partner-observability-reactive-test-app`. No sibling
repository is discovered or required.

`TARGET_SERVICE` is an additional enterprise-workspace E2E layer for exactly one human-selected
service. It never enumerates, auto-selects, aggregates, builds, or starts all `sure-nbfc-*`
directories. Select the exact pilot from a SureWebServices checkout as follows:

```bash
export SUREWEBSERVICES_ROOT=/absolute/path/to/SureWebServices
export TARGET_PARTNER_SERVICE=sure-nbfc-unionbank-ph

./scripts/prepare-target-service-test-fixtures.sh
./test/integration/run-local-service-end-to-end.sh
```

The first command reads YAML/YML only below the selected target, recognizes only OpenAPI
documents, and writes payload-free structural inventories to
`test/partner-contracts/generated/$TARGET_PARTNER_SERVICE/`. Unreviewed operations are
`NOT_COVERED`, which fails readiness. A reviewed mapping and local integration adapter are required
before the second command can build the target through Gradle `--include-build`, start it with
`SPRING_PROFILES_ACTIVE=local`, use its local/mock partner, and validate its real APIs and callbacks
through Alloy, Loki/Prometheus, the authorization boundary, and Grafana. Generated OpenAPI Java is
never edited. See [the target contract format](test/partner-contracts/README.md).

If `SUREWEBSERVICES_ROOT` is omitted, only the repository's direct parent is considered, and only
the exact target basename is resolved. A missing target fails; it never falls back to another
service. Local mode never calls AWS or a real partner. The current standalone checkout does not
contain `sure-nbfc-unionbank-ph`, so the pilot E2E is intentionally deferred to SureWebServices.

Standalone aggregate verification stays generic. To add the reviewed real-service E2E explicitly:

```bash
SUREWEBSERVICES_ROOT=/absolute/path/to/SureWebServices \
TARGET_PARTNER_SERVICE=sure-nbfc-unionbank-ph \
RUN_TARGET_SERVICE_E2E=1 \
./scripts/verify-all.sh
```

#### Purpose and start order

`local` is the only self-contained execution profile. It uses the MVC synthetic application, its
bounded in-process loopback mock partner, local Docker Compose, Alloy, Loki, Prometheus, the
fixed-identity query gateway, and Grafana. The current local flow does not need AWS, LocalStack, or
Testcontainers. HTTP is permitted only on this loopback/isolated-Docker `LOCAL_SYNTHETIC` fixture.

The supported way to get a complete, provisioned, human-inspectable stack is the real end-to-end
runner. It builds first, starts the platform in dependency order, creates isolated Grafana
organizations and random Viewer credentials, starts the application with
`SPRING_PROFILES_ACTIVE=local`, drives representative traffic, and validates the stack. The
following overrides make the otherwise randomized ports deterministic and retain the stack:

```bash
set -o pipefail
MANUAL_RUN_LOG="$(mktemp /tmp/partner-observability-manual.XXXXXX.log)"
E2E_PROJECT_NAME=partner-observability-manual \
LOCAL_TEST_APP_PORT=18080 \
LOCAL_GRAFANA_PORT=13000 \
LOCAL_OTLP_PORT=14318 \
LOCAL_QUERY_PORT=13101 \
LOCAL_PROMETHEUS_PORT=19090 \
LOCAL_ALLOY_METRICS_PORT=12345 \
KEEP_RUNNING=1 \
./scripts/test-end-to-end.sh | tee "$MANUAL_RUN_LOG"

CREDENTIALS_FILE="$(sed -n 's/^KEEP_RUNNING:.* credentials=\(.*\)$/\1/p' "$MANUAL_RUN_LOG" | tail -1)"
test -r "$CREDENTIALS_FILE"
. "$CREDENTIALS_FILE"
```

Do not continue from a failed run merely because `KEEP_RUNNING=1` retained containers for
diagnosis. A usable stack ends with
`PASS: requirements 36-46 application -> SDK -> Alloy -> Loki/Prometheus -> tenant gateway -> Grafana end-to-end boundary`,
followed by a `KEEP_RUNNING` line. The generated credential file is mode 0600 and contains only
random local synthetic users/passwords. It is removed by the cleanup procedure below.

Bare `docker compose up` does not create the two Grafana organizations/Viewers or perform the
bootstrap/re-provision sequence. Use the runner for a portal that a human can actually log into.

#### Manual application-only run

To run the MVC fixture without Docker or backend telemetry, reserve a fixed loopback mock-partner
port and start the application in one terminal:

```bash
SPRING_PROFILES_ACTIVE=local \
SERVER_PORT=8080 \
LOCAL_SYNTHETIC_MOCK_PARTNER_LISTEN_PORT=18082 \
LOCAL_SYNTHETIC_PARTNER_ORIGIN=http://127.0.0.1:18082 \
./gradlew :sure-partner-observability-test-app:bootRun
```

Then `curl -fsS http://127.0.0.1:8080/actuator/health | jq .` must report `"status": "UP"`.
The mock partner is a second server inside the same JVM; it is deliberately loopback-only and is
not a separately managed public service. This application-only command uses the bounded in-process
telemetry collector and does not send records to Grafana. Use the retained end-to-end stack for
backend visibility. Stop `bootRun` with Ctrl-C.

The WebFlux fixture can also be run directly:

```bash
SPRING_PROFILES_ACTIVE=local SERVER_PORT=8081 \
./gradlew :sure-partner-observability-reactive-test-app:bootRun
```

Its health URL is `http://127.0.0.1:8081/actuator/health`. A minimal authenticated synthetic
callback is:

```bash
curl -i -sS -N -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-Synthetic-Callback-Key: local-synthetic-reactive-callback-key' \
  --data '{"applicationId":"SYNTHETIC-REACTIVE-APP-0001","correlationId":"SYNTHETIC-REACTIVE-CORR-0001"}' \
  'http://127.0.0.1:8081/fixture/reactive/callback/alpha?completion=inline'
```

Pass is HTTP 200 with one NDJSON object for partner `alpha`. The B003 runner, not this manual call,
owns the required reactive load and cancellation evidence.

#### Local URLs and health checks

These URLs apply to the deterministic retained end-to-end command above:

| Component | URL | PASS |
| --- | --- | --- |
| MVC test application health | `http://127.0.0.1:18080/actuator/health` | HTTP 200 and JSON `status=UP` |
| MVC test application metrics | `http://127.0.0.1:18080/actuator/prometheus` | HTTP 200 and Prometheus text including `partner_observability_` meters |
| Grafana login | `http://127.0.0.1:13000/login` | Login form; use one generated Viewer from `$CREDENTIALS_FILE` |
| Grafana health | `http://127.0.0.1:13000/api/health` | HTTP 200 and JSON `database=ok` |
| Partner Operations dashboard | `http://127.0.0.1:13000/d/partner-operations/partner-operations` | Provisioned, read-only dashboard |
| Prometheus local operator UI/API | `http://127.0.0.1:19090` | UI responds; `/-/ready` returns HTTP 200 |
| Alloy readiness | `http://127.0.0.1:12345/-/ready` | HTTP 200 |
| Alloy self-metrics | `http://127.0.0.1:12345/metrics` | HTTP 200 and Alloy/OTel collector metrics |
| Authenticated ingest-gateway health | `http://127.0.0.1:14318/healthz` | HTTP 200 body `ready` |
| Fixed-identity query gateway | `http://127.0.0.1:13101` | No open root/health route; approved query paths require generated fixed credentials |
| Loki | No host URL | Intentionally internal; query only through a Viewer-authenticated Grafana Loki datasource |
| Mock partner | No stable host URL | In-process loopback fixture; trigger it through `/fixture/*` |

Run the health checks together:

```bash
curl -fsS http://127.0.0.1:18080/actuator/health | jq .
curl -fsS http://127.0.0.1:13000/api/health | jq .
curl -fsS http://127.0.0.1:19090/-/ready
curl -fsS http://127.0.0.1:12345/-/ready
curl -fsS http://127.0.0.1:14318/healthz
```

Do not publish a Loki port or call Loki with a caller-selected `X-Scope-OrgID`. Direct Prometheus is
loopback-only operator diagnostics and is not partner-isolation evidence. Partner verification
uses the fixed Grafana datasource paths shown below.

#### Manual business and safety scenarios

All MVC control endpoints take no request body; the path selects generated synthetic data.
`alpha` and `beta` are fixed local lanes, not a production authentication model. The control
endpoint itself normally returns HTTP 200 with a bounded JSON summary; `httpStatus` and
`failureType` inside that summary describe the mock partner exchange.

Start with these representative calls against the retained stack:

```bash
# Normal RestTemplate exchange: httpStatus=200, attempts=1, failureType=null.
curl -fsS -X POST http://127.0.0.1:18080/fixture/rest/alpha/success | jq .

# Host-owned retry: httpStatus=200 and attempts=2; the SDK does not perform the retry.
curl -fsS -X POST http://127.0.0.1:18080/fixture/rest/alpha/retry | jq .

# Timeout/error: control HTTP remains 200; summary httpStatus=0 and failureType is populated.
curl -fsS -X POST http://127.0.0.1:18080/fixture/rest/alpha/timeout | jq .

# Explicit plaintext observation around an AES-GCM application flow; ciphertext is not telemetry.
curl -fsS -X POST http://127.0.0.1:18080/fixture/encrypted-rest/alpha | jq .

# PII must be masked downstream.
curl -fsS -X POST http://127.0.0.1:18080/fixture/rest/alpha/restricted-pii | jq .

# Credentials, OTP, and card values must be absent downstream.
curl -fsS -X POST http://127.0.0.1:18080/fixture/rest/alpha/credentials | jq .
curl -fsS -X POST http://127.0.0.1:18080/fixture/rest/alpha/otp | jq .
curl -fsS -X POST http://127.0.0.1:18080/fixture/rest/alpha/card-data | jq .

# A generated 5 MiB Base64 PDF candidate must be omitted before queue admission.
curl -fsS -X POST http://127.0.0.1:18080/fixture/rest/alpha/pdf-request-base64-5-mb | jq .
```

Equivalent client endpoints are
`POST /fixture/webclient/{alpha|beta}/{scenario}` and
`POST /fixture/okhttp/{alpha|beta}/{scenario}`. Supported synchronous scenario names are:

- `normal-json`, `success`, `partner-4xx`, `partner-5xx`, `timeout`, `slow-response`,
  `connection-failure`, `retry`, and `malformed-response`;
- `large-normal-json`, `mixed-large-json-96-kib`, `pdf-request-base64-5-mb`,
  `jpeg-request-base64-8-mb`, `unknown-request-large-base64`,
  `malformed-response-binary-request`, `pdf-base64-5-mb`, `jpeg-base64-8-mb`,
  `unknown-large-base64`, and `base64-document-array`;
- `nested-sensitive`, `credentials`, `otp`, `card-data`, and `restricted-pii`.

Telemetry from a successful synchronous call contains separate `PARTNER_API_REQUEST` and
`PARTNER_API_RESPONSE` records, configured API, direction, status, duration, safe structured
metadata, and a sanitized payload only where permitted. Timeout and connection-failure calls
produce bounded technical outcomes without exception text or changing the control response.

#### Callback manual test

Trigger callbacks through the mock partner so the fixed signature, authenticated context,
acknowledgement, and callback lifecycle are exercised:

```bash
curl -fsS -X POST \
  http://127.0.0.1:18080/fixture/async/alpha/callback-success \
  | tee /tmp/partner-observability-manual-async.json

RUN_ID="$(jq -r '.runId' /tmp/partner-observability-manual-async.json)"
for attempt in $(seq 1 45); do
  curl -fsS \
    "http://127.0.0.1:18080/fixture/async/runs/$RUN_ID" \
    > /tmp/partner-observability-manual-snapshot.json
  jq -e 'any(.events[]; .stage == "CALLBACK_RESPONSE_SENT")' \
    /tmp/partner-observability-manual-snapshot.json >/dev/null && break
  sleep 1
done

jq '{runId, acknowledgementHttpStatus, acknowledgementReceived,
     stages: [.events[].stage],
     callback: .callbackAttempts[0]}' \
  /tmp/partner-observability-manual-snapshot.json

jq -e '
  .acknowledgementHttpStatus == 202 and
  .acknowledgementReceived == true and
  (["ASYNC_REQUEST_SENT","ASYNC_ACK_RECEIVED","CALLBACK_RECEIVED",
    "CALLBACK_PROCESSED","CALLBACK_RESPONSE_SENT"] -
   [.events[].stage] | length == 0) and
  .callbackAttempts[0].responseStatus == 200
' /tmp/partner-observability-manual-snapshot.json >/dev/null
```

Pass is acknowledgement HTTP status 202, then distinct
`ASYNC_REQUEST_SENT`, `ASYNC_ACK_RECEIVED`, `CALLBACK_RECEIVED`,
`CALLBACK_PROCESSED`, and `CALLBACK_RESPONSE_SENT` stages with callback response status 200.
Obtain the run-specific Grafana search value with:

```bash
CALLBACK_REFERENCE_ID="$(jq -r '.callbackAttempts[0].identifiers.callbackReferenceId' \
  /tmp/partner-observability-manual-snapshot.json)"
printf '%s\n' "$CALLBACK_REFERENCE_ID"
```

Other implemented async paths are `acknowledgement-only`, `ack-with-partner-reference`,
`callback-with-application-id`, `callback-with-partner-reference-only`,
`callback-with-callback-reference`, `callback-processing-failure`, `callback-retry`,
`duplicate-callback`, `callback-out-of-order`, `callback-after-outbound-timeout`,
`unknown-partner-reference`, `wrong-partner`, `authentication-failure`,
`malformed-callback`, `callback-pdf-base64-5-mb`, `callback-image-base64`,
`callback-sensitive-pii`, `callback-credentials`, `accepted-then-downstream-failure`,
`response-transmission-failure`, `cross-partner-callback-reference`,
`high-concurrency-callbacks`, and `multiple-callbacks`. The
`performance-inline-success`, `performance-short-deferred-success`, and
`performance-long-deferred-success` variants belong to the B003 harness. Use
`GET /fixture/async/security-counters` to inspect bounded denial counts. These are all synthetic
local fixtures; none is a STAGE/PROD test endpoint.

#### Grafana, Loki, and transaction verification

Open `$GRAFANA_URL/login` and use `PARTNER_A_USER`/`PARTNER_A_PASSWORD` from the sourced generated
credentials file. The user must be a Viewer in exactly the `PARTNER_A` organization. Open the
**Partner Operations** dashboard, select service `partner-observability-test-app`, set **Search
field** to **Application ID**, and enter:

```text
SYNTHETIC-APPLICATION-COLLISION-0001
```

The search must show Alpha request/response records only, even though Beta uses the same
application ID. The overview and ascending timeline must show first/last/current facts and elapsed
time. Selecting an event ID must show safe detail with omission/masking counts, never credentials,
OTP/card data, Base64 documents, or unmasked phone/email/account/national-ID/address values.

For the callback run above, choose **Callback reference ID** and enter
`$CALLBACK_REFERENCE_ID`. The timeline must keep receipt, processing, and response as separate
facts. The lower dashboard panels must show request count, availability/success, error, timeout,
retry, throughput, p50/p95/p99 latency, and callback volume/retry/processing/latency. No contractual
SLA target is provisioned.

Verify provisioning and the approved Loki query route through Grafana's server proxy:

```bash
curl -fsS -u "$PARTNER_A_USER:$PARTNER_A_PASSWORD" \
  "$GRAFANA_URL/api/dashboards/uid/partner-operations" \
  | jq -e '.dashboard.uid == "partner-operations" and .meta.provisioned == true'

curl -fsSG -u "$PARTNER_A_USER:$PARTNER_A_PASSWORD" \
  "$GRAFANA_URL/api/datasources/proxy/uid/partner-loki/loki/api/v1/query_range" \
  --data-urlencode \
  'query={service_name="partner-observability-test-app"} | application_id="SYNTHETIC-APPLICATION-COLLISION-0001"' \
  --data-urlencode 'since=30m' \
  --data-urlencode 'direction=forward' \
  --data-urlencode 'limit=1000' \
  | jq '{status, records: ([.data.result[].values[]] | length)}'
```

Pass is `status=success` with at least the request and response records. This
Viewer-authenticated datasource path fixes the Loki tenant server-side. Direct Loki queries are
not an approved partner verification path.

#### Prometheus verification

The local operator endpoint can prove ingestion and rule evaluation:

```bash
curl -fsSG http://127.0.0.1:19090/api/v1/query \
  --data-urlencode \
  'query=sum(partner_observability_http_interactions_total{api="PARTNER_ALPHA_SYNC"})' \
  | jq .

curl -fsSG http://127.0.0.1:19090/api/v1/query \
  --data-urlencode \
  'query=sum(partner_observability_callback_deliveries_total{api="CREDIT_DECISION_CALLBACK_ALPHA"})' \
  | jq .

curl -fsSG http://127.0.0.1:19090/api/v1/query \
  --data-urlencode \
  'query=avg(partner_observability:outbound_latency_seconds:p95_5m)' \
  | jq .
```

Pass is Prometheus `status=success` and non-empty results after the next scrape/rule interval.
For the partner authorization boundary, run the same query through the fixed Grafana datasource:

```bash
curl -fsSG -u "$PARTNER_A_USER:$PARTNER_A_PASSWORD" \
  "$GRAFANA_URL/api/datasources/proxy/uid/partner-prometheus/api/v1/query" \
  --data-urlencode 'query=sum(partner_observability_http_interactions_total)' \
  | jq .
```

Every returned `partner_slot`, when present, must be `p001` for Partner A. Do not treat direct
Prometheus output as evidence of partner isolation.

#### Local automated tests

| Command | What it proves |
| --- | --- |
| `./scripts/test.sh` | All Gradle unit and Spring integration tests without a clean |
| `./scripts/test-enterprise-naming.sh` | Java package, Gradle group, module, and artifact naming |
| `./scripts/validate-profiles.sh` | Exactly `local`, `dev`, `stage`, `prod` and properties-only configuration |
| `./scripts/test-target-service-local.sh` | Exact-target rejection/isolation, target-only OpenAPI parsing, fail-closed coverage, and route rendering |
| `TARGET_PARTNER_SERVICE=... ./test/integration/run-local-service-end-to-end.sh` | Optional real selected-service E2E; requires its reviewed mapping/adapter and never falls back |
| `./test/integration/run-local-data-plane.sh` | Real Compose, Alloy, Loki, fixed-tenant routing, schema, safety, and searches |
| `./test/integration/run-local-metrics-plane.sh` | Real Alloy scrape/relabel/remote-write, Prometheus retention/rules, and bounded labels |
| `./scripts/test-grafana.sh --validate-only` | Real Grafana health, accounts, organizations, fixed datasources, and provisioning; no telemetry/SLI seeding |
| `./scripts/test-grafana.sh` or `./test/integration/run-local-grafana.sh` | Full local Grafana auth, isolation, search, timeline, detail, SLI, and bypass suite |
| `./scripts/test-end-to-end.sh` or `./test/integration/run-local-end-to-end.sh` | Builds and drives the real MVC app through SDK → Alloy → Loki/Prometheus → gateway → Grafana |
| `./scripts/test-security.sh --core` | Pre-queue payload, TLS, callback, trusted-context, and failure-containment security |
| `./scripts/test-security.sh --data-plane` | Alloy/Loki disclosure and tenant-isolation security |
| `./scripts/test-security.sh --metrics-plane` | Alloy/Prometheus label and metric safety |
| `./scripts/test-security.sh` | Complete local security gate, including Grafana and end to end |
| `./scripts/test-enterprise-infrastructure-contract.sh` | Central ownership, required STAGE/PROD capabilities/outputs, and absence of repository Terraform |
| `./scripts/test-terraform.sh` | Compatibility alias for the preceding contract check; it invokes neither Terraform nor AWS |
| `./scripts/validate-performance-profiles.sh` | Nine B003 profiles and P01-P24 mapping consistency without running load |
| `./scripts/test-docs.sh` | Documentation/configuration mappings, shell/JSON syntax, profiles, versions, retention, and deployment constraints |
| `./scripts/verify-all.sh` | Authoritative aggregate gate, including the approximately 29.5-hour full B003 run |

Performance mechanics only:

```bash
SPRING_PROFILES_ACTIVE=local PERF_MODE=smoke ./scripts/test-performance.sh
```

Smoke uses reduced load/duration and one repetition. It prints
`SMOKE MODE — NOT RELEASE EVIDENCE` and cannot close B003. The only release-evidence command is:

```bash
SPRING_PROFILES_ACTIVE=local PERF_MODE=full ./scripts/test-performance.sh
```

Full mode runs all nine profiles three times with matched baselines and is approximately 29.5
hours before setup/recovery overhead. It must not be shortened. Detailed evidence is retained in
`test/performance/evidence/$RUN_ID/`; commit-safe results appear in
`test/performance/results/$RUN_ID/` only after a complete pass. `KEEP_RUNNING=1` retains the
performance stack for bounded diagnosis, and `RUN_ID` may be a filename-safe opaque identifier.

`./scripts/verify-all.sh` is the authoritative aggregate gate and explicitly invokes full
performance mode. Do not run it as a quick test and do not claim it passed unless the entire
full-duration run completes with `FINAL RESULT: PASS`.

#### Stop and clean up local execution

Normal integration runners automatically execute `docker compose down -v --remove-orphans` and
remove generated credentials. For the deterministic `KEEP_RUNNING=1` stack above, stop containers
and delete only its named volumes with:

```bash
GRAFANA_ADMIN_PASSWORD=cleanup-only \
GRAFANA_SECRET_KEY=cleanup-only-secret-key \
GRAFANA_PARTNER_A_QUERY_PASSWORD=cleanup-only-a \
GRAFANA_PARTNER_B_QUERY_PASSWORD=cleanup-only-b \
docker compose --profile grafana --profile end-to-end \
  -p partner-observability-manual \
  -f docker/compose.yml \
  down -v --remove-orphans
```

Then remove only the generated runner directory after validating its prefix:

```bash
MANUAL_TEMP_DIR="$(dirname "$CREDENTIALS_FILE")"
case "$MANUAL_TEMP_DIR" in
  /tmp/partner-observability-m9.*) rm -rf -- "$MANUAL_TEMP_DIR" ;;
  *) printf 'Refusing unexpected path: %s\n' "$MANUAL_TEMP_DIR" >&2; exit 1 ;;
esac
rm -f -- "$MANUAL_RUN_LOG" \
  /tmp/partner-observability-manual-async.json \
  /tmp/partner-observability-manual-snapshot.json
```

The `-v` flag removes disposable local Loki, Prometheus, Alloy, and Grafana volumes. It does not
touch AWS or enterprise infrastructure.

### Dev

#### Purpose, profile, and build

`dev` means a dedicated AWS DEV ECS cluster/VPC and an AWS-hosted HTTPS mock partner. It never uses
the real partner staging or production endpoint. DEV and STAGE may share a market AWS account but
must not share their cluster, VPC, resources, configuration, tenant maps, routes, or secrets.

Build the same immutable artifacts with `./gradlew --no-daemon clean build` and activate the host
service externally with `SPRING_PROFILES_ACTIVE=dev`. The checked-in
`sure-partner-observability-test-app/src/main/resources/application-dev.properties` has safe
defaults:

- `partner-observability.environment=dev`;
- `PARTNER_OBSERVABILITY_ENABLED` defaults to `false`;
- `PARTNER_OBSERVABILITY_MARKET` defaults to `unconfigured`;
- `partner-observability.local-synthetic=false`.

The WebFlux test fixture is also disabled in DEV. Neither synthetic boot JAR is a DEV deployment
artifact. The approved host-service runtime manifest must supply the fixed HTTPS mock-partner API,
partner/tenant/slot configuration, and secret references before observability is enabled.

#### Deployment and URL derivation

The current STAGE/PROD infrastructure contract intentionally makes no DEV infrastructure change.
This repository has no DEV GitHub Actions workflow, ECS command, DNS value, runtime-manifest
schema instance, health output, or Grafana URL. Therefore there is no repository-supported DEV
deployment command or URL derivation today.

Use only the market's existing protected DEV application-release process after the dedicated DEV
cluster/VPC and HTTPS mock partner already exist. That external release must identify the exact
artifact digest, set `SPRING_PROFILES_ACTIVE=dev`, inject the reviewed non-secret manifest and
secret references, and publish the deployed host-service health URL and DEV Grafana URL. If its
release record does not provide those exact URLs, stop: they cannot be reconstructed from this
repository and a guessed DNS name is not acceptable.

Do not run Terraform, `aws ecs`, the local Compose scripts, or the synthetic `/fixture/*` endpoints
against DEV.

#### Manual DEV verification

After the external release reports success:

1. Call the exact HTTPS health URL in the DEV release output. For a host service exposing standard
   Spring Actuator health, the path is `/actuator/health` and the response must be HTTP 200 with
   `status=UP`. The optional `partnerObservability` contributor must remain `UP` and report bounded
   dispatcher/queue detail; backend failure must not make business readiness depend on it.
2. Run an existing approved host-application flow against the DEV HTTPS mock partner. There is no
   repository-owned live DEV test endpoint. Capture one synthetic application/loan/correlation or
   partner-reference identifier from that approved flow.
3. Open the exact DEV Grafana HTTPS URL from the release record, authenticate to the expected
   partner organization as Viewer, open **Partner Operations**, and search that exact identifier.
4. Require separate request/response or async acknowledgement/callback facts, sanitized detail,
   and non-empty partner-scoped metrics/SLI panels. Verify a second partner account cannot retrieve
   the identifier when an approved isolation account is available.
5. Do not access Loki, Prometheus, Alloy, or the query gateway directly; their DEV endpoints are
   private and this repository defines no safe public URL.

### Stage

#### Purpose, build, and infrastructure prerequisite

`stage` means the dedicated AWS STAGE cluster/VPC and the real partner staging environment. All
external partner requests, acknowledgements, callbacks, and Grafana access use HTTPS. Build once
with `./gradlew --no-daemon clean build`, promote the same immutable artifact tested in DEV, and
set `SPRING_PROFILES_ACTIVE=stage` in the protected runtime.

Before application deployment, the centralized enterprise Terraform repository must have been
updated, human-reviewed, and manually executed for the target market STAGE deployment. Base health
and all required non-secret outputs in
`docs/enterprise-infrastructure/infrastructure-contract.yaml` must exist. Never run Terraform from
this repository.

The checked-in `application-stage.properties` deliberately supplies no partner endpoint,
credential, callback URL, or DNS value and keeps `PARTNER_OBSERVABILITY_ENABLED=false` by default.
The real staging HTTPS endpoint, tenant/slot/organization manifest, callback ownership reference,
and secret/parameter ARNs come from the protected runtime configuration. Secret values are
runtime-only.

#### Deployment

After the central infrastructure change reference and healthy outputs exist, use the protected
enterprise GitHub Actions application release described in
`docs/enterprise-infrastructure/github-actions-contract.md`. It promotes immutable application
artifacts and deploys application-owned runtime configuration, the Partner Operations dashboard,
alerts when present, and Prometheus rules, then performs post-deployment validation.

No tracked workflow currently implements this contract, so no workflow filename, trigger, branch,
or input can be invoked from this checkout. STAGE deployment remains blocked until that enterprise
integration exists. Do not replace it with a direct ECS update or manual Grafana provisioning.

#### STAGE URLs and manual validation

The full URLs are derived only from the access-controlled outputs required by the machine contract:

- use `grafana-https-url-and-load-balancer-identifiers` for the exact Grafana base URL;
- use `log-group-and-health-check-identifiers` for exact component/application health-check URLs;
- use `private-alloy-ingress-endpoint` and `internal-service-discovery-endpoints` only inside
  automated/private validation—never as partner browser URLs;
- obtain the externally reachable callback URL from the host service's own callback ALB/DNS
  release evidence. This platform contract does not create callback ALBs or define a callback
  path.

If any required output or the `infrastructure-version-or-change-reference` is missing, fail the
release. Do not append a guessed market/domain suffix.

For human STAGE validation:

1. Call the exact HTTPS health-check URL published in the central/GHA release evidence and require
   the documented healthy result. A host Spring service that exposes Actuator uses
   `/actuator/health`; internal platform health paths come from the required output rather than
   this README.
2. Use an existing approved application flow to initiate a safe partner-staging transaction. This
   repository has no STAGE-safe initiation endpoint. Capture the application, loan, correlation,
   partner-reference, and callback-reference IDs that the approved flow makes available.
3. Verify the host application received the expected real partner-staging response. For async
   flows, wait for the actual staging callback and verify host business processing separately from
   HTTP receipt/response.
4. Open the output-provided Grafana HTTPS URL in the expected partner organization, open
   **Partner Operations**, select the deployed service, and search one captured exact identifier.
5. Require outbound request/response or request/acknowledgement visibility and, when applicable,
   an ordered timeline containing distinct `CALLBACK_RECEIVED`, `CALLBACK_PROCESSED` or
   `CALLBACK_PROCESSING_FAILED`, and `CALLBACK_RESPONSE_SENT` facts.
6. Inspect selected sanitized detail and the request, availability, error, timeout, retry,
   throughput, latency, and callback SLI panels. “No data” is not a pass and no contractual SLA
   threshold should appear unless separately approved.
7. Use a separately authenticated partner Viewer to prove the captured identifier is absent from
   the wrong organization. Do not attempt direct Loki/Prometheus access.

### Prod

#### Purpose, build, and approval boundary

`prod` is AWS production with the real production partner and real HTTPS callbacks. Promote the
same immutable artifact that passed DEV and STAGE; set `SPRING_PROFILES_ACTIVE=prod` only through
the protected production runtime. Secrets, datasource credentials, certificates, private keys,
and partner configuration are runtime references and must never enter Git, command history,
Terraform output, or a workflow log.

Production is not currently authorized: full B003 evidence, remaining transport/staging evidence,
the central infrastructure implementation, the enterprise GHA implementation, production
approvals, and unresolved production identity/capacity inputs must all be closed first.

#### Infrastructure and deployment

The centralized Terraform repository must first be human-reviewed and manually executed with a
production change reference and successful STAGE evidence. Then the protected enterprise GitHub
Actions release—not an operator shell—deploys the approved prior-tested image/config digests and
application-owned observability assets. There is no direct manual ECS shortcut, tracked production
workflow, or repository command. Do not deploy, run Terraform, obtain production credentials, or
create infrastructure from this repository.

#### Safe post-deployment verification

After an authorized future production release:

1. Use `grafana-https-url-and-load-balancer-identifiers` and
   `log-group-and-health-check-identifiers` from the access-controlled production infrastructure
   and GHA evidence. Require HTTPS and the exact recorded health response; never guess a hostname.
2. Confirm Grafana is available, the expected user is Viewer in exactly one partner organization,
   the Partner Operations dashboard is provisioned/read-only, and its Loki and Prometheus
   datasources are healthy through the fixed query gateway.
3. Confirm platform/service metrics have fresh samples and no sustained dispatcher drops,
   datasource errors, or tenant/query-gateway denials.
4. Do **not** create synthetic production applications, loans, callbacks, or transactions. With
   approval, use an identifier from an actual existing production transaction and verify only its
   partner-scoped sanitized request/response/timeline/SLI visibility. Verify callback visibility
   only when an actual production callback occurs.
5. Treat absent telemetry as an observability incident, not a reason to fail or block business
   traffic. Alloy, Loki, Prometheus, Grafana, and their health must not become host-service
   readiness dependencies.

#### Rollback and disable controls

Application rollback uses the prior immutable task/config/dashboard/rule artifacts through the
enterprise GHA interface; it must not destroy or re-apply base infrastructure. The actual startup
controls are:

| Control | Property | Environment form |
| --- | --- | --- |
| Disable all auto-configuration | `partner-observability.enabled=false` | `PARTNER_OBSERVABILITY_ENABLED=false` |
| Disable payload capture | `partner-observability.payloads-enabled=false` | `PARTNER_OBSERVABILITY_PAYLOADS_ENABLED=false` |
| Disable selected safe-log capture | `partner-observability.logs-enabled=false` | `PARTNER_OBSERVABILITY_LOGS_ENABLED=false` |
| Disable business events | `partner-observability.events-enabled=false` | `PARTNER_OBSERVABILITY_EVENTS_ENABLED=false` |
| Disable explicit observation hooks | `partner-observability.explicit-observations-enabled=false` | `PARTNER_OBSERVABILITY_EXPLICIT_OBSERVATIONS_ENABLED=false` |
| Disable metrics | `partner-observability.metrics-enabled=false` | `PARTNER_OBSERVABILITY_METRICS_ENABLED=false` |
| Disable telemetry export | `partner-observability.export-enabled=false` | `PARTNER_OBSERVABILITY_EXPORT_ENABLED=false` |
| Disable callback instrumentation | `partner-observability.callbacks-enabled=false` | `PARTNER_OBSERVABILITY_CALLBACKS_ENABLED=false` |

These are runtime configuration inputs applied through an approved rollout. No management HTTP
endpoint for changing them is implemented in this repository. The in-process
`ObservabilityKillSwitches` API is monotonic, but a host must explicitly integrate it with an
approved management plane before an operator can use it. Never invent an actuator write endpoint.

### Profile-specific property reference

Both runnable test modules contain
`application.properties` plus `application-local.properties`, `application-dev.properties`,
`application-stage.properties`, and `application-prod.properties` under their respective
`src/main/resources` directories. There are no Spring application YAML files or profile aliases.

| Profile | Properties file | Runtime | Partner | Infrastructure | Deployment method | Primary verification |
| --- | --- | --- | --- | --- | --- | --- |
| `local` | `application-local.properties` | Local VM plus Docker Compose | In-process/mock local partner | Disposable local Alloy/Loki/Prometheus/Grafana; no AWS | `test/integration/run-local-end-to-end.sh` or direct `bootRun` | Concrete loopback health URLs, fixture calls, Partner Operations |
| `dev` | `application-dev.properties` | Dedicated AWS DEV ECS cluster/VPC | AWS-hosted HTTPS mock | Existing DEV resources; no new requirement from the STAGE/PROD contract | Existing protected DEV release process, not present here | Release-provided health and Grafana URLs; approved mock flow |
| `stage` | `application-stage.properties` | Dedicated AWS STAGE ECS cluster/VPC | Real partner staging over HTTPS | Central Terraform already reviewed/manually applied | Enterprise GHA after central outputs; integration not tracked here | Output-provided health/Grafana, approved staging flow and callback |
| `prod` | `application-prod.properties` | AWS production cluster/network | Real partner production over HTTPS | Central Terraform already reviewed/manually applied with production approval | Protected enterprise GHA; integration not tracked here | Safe health/Grafana/metrics and an approved existing real identifier only |

The same immutable host-service artifact moves between profiles. Runtime/GHA sets exactly one
`SPRING_PROFILES_ACTIVE` value. The non-local synthetic test-app profiles start observability
disabled and do not make those fixtures deployable applications.

### Manual test matrix

“Pre-promotion” means the command runs locally/CI against synthetic data before an artifact is
promoted; it does not call that AWS environment.

| Check | Local | Dev | Stage | Prod |
| --- | --- | --- | --- | --- |
| Clean Gradle build | Required | Required pre-promotion | Same artifact promoted | Same STAGE-passed artifact promoted |
| Unit tests | `./scripts/test.sh` | Required pre-promotion | Reuse passing artifact evidence | Reuse passing artifact evidence |
| Spring context/profile binding | Gradle tests | Safe disabled DEV context test | Safe disabled STAGE context test | Safe disabled PROD context test |
| Profile/YAML validation | `./scripts/validate-profiles.sh` | Same gate | Same gate | Same gate |
| Alloy/Loki integration | `./test/integration/run-local-data-plane.sh` | No repository live runner | Contract-required external post-deploy check | Contract-required safe external post-deploy check |
| Prometheus integration | `./test/integration/run-local-metrics-plane.sh` | No repository live runner | Contract-required external post-deploy check | Contract-required safe external post-deploy check |
| Grafana provisioning/isolation | `./scripts/test-grafana.sh` | Release-provided DEV verification | Required GHA/STAGE verification | Required safe GHA/PROD verification |
| Application end to end | `./scripts/test-end-to-end.sh` | Approved host flow with mock partner | Existing approved flow with real partner staging | Never synthesize; approved existing real flow only |
| Security gate | `./scripts/test-security.sh` | Local evidence plus external release controls | Local evidence plus staged isolation/TLS evidence | Local/STAGE evidence plus production controls |
| Performance smoke | `PERF_MODE=smoke`; mechanics only | Not run against DEV | Not run against STAGE | Not run against PROD |
| Performance full | `PERF_MODE=full` under `local` only | Not run against DEV | Not run against STAGE | Not run against PROD |
| Live partner verification | Not applicable | Mock only | Approved real staging flow | Approved existing real transaction only |

### URL and endpoint quick reference

| Component | Profile | URL or exact derivation | Purpose and expected result |
| --- | --- | --- | --- |
| MVC app | Local retained stack | `http://127.0.0.1:18080` | Fixture control plane; root is not a business UI |
| MVC health | Local retained stack | `http://127.0.0.1:18080/actuator/health` | HTTP 200, `status=UP` |
| MVC Prometheus scrape | Local retained stack | `http://127.0.0.1:18080/actuator/prometheus` | Application Micrometer text |
| Sync scenarios | Local retained stack | `POST http://127.0.0.1:18080/fixture/{rest|webclient|okhttp}/{alpha|beta}/{scenario}` | Bounded exchange summary; telemetry follows configured client behavior |
| Encrypted scenario | Local retained stack | `POST http://127.0.0.1:18080/fixture/encrypted-rest/{alpha|beta}` | HTTP 200 logical round trip; ciphertext absent from telemetry |
| Async initiation | Local retained stack | `POST http://127.0.0.1:18080/fixture/async/{alpha|beta}/{async-scenario}` | HTTP 200 control summary containing acknowledgement status/run ID |
| Async status | Local retained stack | `GET http://127.0.0.1:18080/fixture/async/runs/{runId}` | Bounded lifecycle snapshot |
| Callback ingress | Local retained stack | `POST http://127.0.0.1:18080/fixture/callback/{alpha|beta}` | Called by the signed mock fixture; trigger through async initiation |
| WebFlux fixture | Local direct run | `http://127.0.0.1:8081/fixture/reactive/*` | Test-only reactive stream/callback endpoints |
| Grafana | Local retained stack | `http://127.0.0.1:13000/login` | Generated Viewer login |
| Partner dashboard | Local retained stack | `http://127.0.0.1:13000/d/partner-operations/partner-operations` | Read-only search/timeline/detail/SLI dashboard |
| Prometheus | Local retained stack | `http://127.0.0.1:19090` | Loopback operator diagnostics; `/-/ready` is health |
| Alloy | Local retained stack | `http://127.0.0.1:12345/-/ready` and `/metrics` | Readiness and self-metrics |
| Ingest gateway | Local retained stack | `http://127.0.0.1:14318/healthz` | HTTP 200 `ready`; `/v1/logs` is authenticated SDK ingest |
| Loki/query gateway | Local retained stack | Loki has no host URL; approved path is the Grafana `partner-loki` datasource proxy | Fixed-tenant query, never caller-selected tenant |
| Host service | Dev | Exact HTTPS health URL from the existing DEV release record | Must be supplied externally; no repository hostname/variable exists |
| Grafana | Dev | Exact HTTPS URL from the existing DEV release record | Must be supplied externally; stop if absent |
| Platform/Grafana | Stage | `log-group-and-health-check-identifiers` and `grafana-https-url-and-load-balancer-identifiers` central/GHA outputs | Exact access-controlled URLs; no guessed DNS |
| Callback | Stage | Host service's callback ALB/DNS/path release evidence | Real partner staging HTTPS callback; not owned by this platform contract |
| Platform/Grafana | Prod | Production values of the same required central/GHA outputs | Safe health and Viewer verification only |
| Callback | Prod | Host service's approved production callback ALB/DNS/path evidence | Verify only on a real production callback |

Braced path segments in this table are implemented route parameters, not unknown hostname/port
placeholders. Non-local URLs are intentionally named by their exact required output because the
repository contains no stable DNS value or shell variable.

### Troubleshooting

- **Wrong or missing Spring profile:** check `printf '%s\n' "$SPRING_PROFILES_ACTIVE"`. It must be
  exactly one of `local`, `dev`, `stage`, or `prod`. The common properties do not embed an active
  profile. Local HTTP is rejected outside the guarded `local` profile.
- **Gradle cannot find a project or dependency:** for standalone generic work, run from the
  repository root and confirm `./gradlew projects` lists all six modules. This repository itself is
  one multi-project Gradle build using `project(...)` dependencies. Only the explicit
  `TARGET_SERVICE` runner invokes a selected sibling service with `--include-build` so its starter
  and local-test-support dependencies resolve from source. Use a writable `GRADLE_USER_HOME` if the
  wrapper lock/cache path is read-only.
- **Docker component is unhealthy or a port is occupied:** inspect
  `docker ps --filter label=com.docker.compose.project=partner-observability-manual` and the bounded
  logs for the named container, for example
  `docker logs --tail 100 partner-observability-manual-grafana-1`. Stop the retained project before
  reusing ports 18080, 13000, 14318, 13101, 19090, or 12345.
- **Grafana starts but is not provisioned:** do not use bare Compose as a substitute for bootstrap.
  Rerun `./scripts/test-grafana.sh --validate-only` or the full retained end-to-end procedure.
  Check `/api/health`, source the generated credential file, then check
  `/api/dashboards/uid/partner-operations` and `/api/datasources` as a Viewer.
- **No Loki telemetry:** first confirm the application call succeeded, then allow the bounded
  dispatcher/query wait (the E2E default is 75 seconds). Check Alloy `/-/ready` and search
  `http://127.0.0.1:12345/metrics` for
  `otelcol_receiver_accepted_log_records` and exporter sent/failure counters. Query only through
  the authenticated Grafana Loki datasource; do not expose Loki or inject a tenant header.
- **No Prometheus metrics:** verify `/actuator/prometheus` contains
  `partner_observability_` meters, Prometheus `/-/ready` succeeds, and Alloy is scraping
  `test-app:8080`. Allow the E2E metric wait (90 seconds) and query both the raw metric and its
  five-minute recording rule. “No data” is not zero and is not a pass.
- **Callback does not appear:** inspect `GET /fixture/async/runs/$RUN_ID` before Grafana. The
  lifecycle must reach a callback terminal stage. Check
  `GET /fixture/async/security-counters` for unknown-run, authentication, wrong-partner, or
  oversized-body denials, then search the run's actual callback reference rather than a guessed
  value.
- **DEV/STAGE/PROD starts with no telemetry:** non-local checked-in profiles are fail-closed and
  observability is disabled by default. The approved runtime must set the exact profile, market,
  enable flag, fixed partner/API/callback manifest, and secret references. Do not copy the local
  synthetic manifest or its HTTP exception.
- **No non-local URL or missing base infrastructure:** stop the release. DEV needs its existing
  external release record; STAGE/PROD need every machine-contract output and the central
  infrastructure change reference. This repository cannot discover or create the missing value.
- **Dashboard/rule deployment fails in GitHub Actions:** the workflow is not tracked here, so first
  validate source assets locally with `./scripts/test-grafana.sh --validate-only`,
  `jq empty grafana/dashboards/partner-operations.json`, and
  `./test/integration/run-local-metrics-plane.sh`. The enterprise release must report the deployed
  artifact digest and roll back to the prior application artifact without changing base
  infrastructure or editing the dashboard manually.
- **Performance run is too short or B003 remains open:** only
  `SPRING_PROFILES_ACTIVE=local PERF_MODE=full ./scripts/test-performance.sh` counts. Smoke,
  shortened durations, one repetition, missing metrics, or an absent
  `overallPassed=true` full aggregate cannot close B003. Restore the required 8 CPUs/12 GiB,
  preserve evidence, and rerun without weakening the manifest or thresholds.

## Constraints

- Java 17; Spring Boot 2.7.x; Gradle Groovy DSL.
- SLF4J/Logback for application-side logging.
- Docker Compose locally; AWS ECS for STAGE/PROD, provisioned by the separate centralized
  enterprise Terraform repository under `docs/enterprise-infrastructure/`.
- No Kubernetes and no Helm.
- No production deployment, production credentials, or real partner/customer data.
- Agents may commit locally and may push completed, verified work from a `READY_FOR_REVIEW` or `COMPLETE` scope to an existing remote branch. Force pushes, merges, protected-branch bypass, releases, and deployments remain prohibited.

The project is licensed under Apache License 2.0; see [LICENSE](LICENSE).
