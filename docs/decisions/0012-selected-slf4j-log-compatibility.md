# ADR 0012: Selected SLF4J log compatibility

- Status: Accepted and implemented for the M3 compatibility slice
- Date: 2026-08-17
- Decision owners: SDK architecture and application security

## Context

Existing Spring Boot services already contain operational SLF4J statements and should not have to
rewrite every approved statement to a dedicated logger. However, a rendered message is an
unstructured mixture of literal text, argument rendering, exception data, and potentially hostile
customer input. Regex redaction cannot prove that such a string is partner-safe. Copying a package,
logger, or level wholesale would also turn Partner Loki into a second application-log sink and
violate the data-class boundary in ADR 0001.

## Decision

Provide an optional Logback observer appender in the Spring Boot auto-configuration module. It is
globally disabled by default and is created only when Logback is present, observability is enabled,
`logs-enabled=true`, and at least one startup-validated selection exists.

Each selection must declare:

- a bounded exact logger name or trailing package selector using `.*` or `.**`;
- one exact unformatted SLF4J message template;
- a configured partner-safe category and journey stage;
- a minimum level;
- an optional exact marker and fixed error code/outcome;
- zero or more unique argument indexes with configured safe field name, scalar type, and
  allow/remove/mask policy.

Logger/package selection alone is never sufficient. The appender does not call
`getFormattedMessage()`, does not inspect throwable proxies, exception messages, stack traces, MDC
identity, or arbitrary argument `toString()`, and never copies the raw template. Only configured
argument indexes are projected into a bounded map and passed through the existing first-stage
sanitizer. Unsupported objects and throwable arguments are omitted. Secret, Authorization, card,
OTP, binary, Base64, and oversized values retain the existing fail-closed treatment.

A selected statement emits a configured `PartnerBusinessEventRecord` only when the current
immutable `PartnerObservationContext` contains a trusted context that exactly matches the startup
partner registry. Missing or foreign context drops the copy with `NO_TRUSTED_CONTEXT`; MDC and log
fields can never select a partner. The already-safe record uses `TelemetryChannel.LOG`, normal
priority, and the same bounded non-blocking dispatcher as other telemetry.

The observer appender is added alongside the root logger's existing appenders. It does not replace
appenders, change logger levels, filters, additivity, formatting, or the event delivered to
CloudWatch. It performs no remote I/O. All publisher work stays on the dispatcher daemon, and every
copy failure is contained from the original logging call and other appenders.

## Security and availability consequences

- Existing approved statements can be reused without copying arbitrary application logs.
- The template/category registry and argument schemas bound cardinality and disclosure surface.
- A selected unsafe argument can reduce to safe category metadata, but its raw value never enters
  a telemetry queue.
- Logs outside a trusted scoped partner interaction are intentionally not copied.
- Services with non-additive loggers must keep the selected event on the normal root-appender path
  or explicitly use the structured safe-event API; the bridge does not mutate service logging
  topology to force capture.

## Alternatives considered

- Tail every application/CloudWatch log: rejected because it duplicates internal-only data.
- Regex-redact rendered messages: rejected because aliases, formatting, encoding, and exceptions
  make completeness unprovable.
- Logger/package allowlist alone: rejected because one approved logger can still emit unsafe data.
- Require all services to rewrite statements to a dedicated logger and marker: safe but needlessly
  blocks selected legacy compatibility.
- Replace the service's Logback configuration: rejected because it changes normal logging
  semantics and CloudWatch behavior.

## Implementation and migration impact

Application teams inventory candidate statements and configure exact categories/templates and
argument schemas. They first deploy with logging capture disabled, then enable one synthetic DEV
selection under trusted context. Any template change fails closed as non-selected until the
manifest is reviewed. Markers remain an optional narrowing control for services that already use
them.

## Verification evidence required

Automated tests cover a safe selected statement, non-selected internal statement, marker mismatch,
stack trace, exception containing synthetic customer data, Authorization embedded in a selected
message, two partners, missing context, a large non-Base64 line, Base64, disabled mode, publisher
failure, partner-pure batching, unchanged existing-appender output, and dispatcher-thread
publication.

## Verification traceability

| Requirement / failure mode | Automated evidence | Layer |
| --- | --- | --- |
| Exact logger and unformatted template are both required | `loggerPatternWithoutAnExactMessageTemplateFailsStartupClosed`, `nonSelectedInternalLogProducesNoCopy` | Configuration / negative unit |
| Selected safe scalar becomes a category event | `selectedExistingLogProducesSafeCopyAndKeepsExistingAppenderSemantics` | Framework integration |
| Marker narrows selection and partners remain separate | `multiplePartnersStaySeparatedAndMarkerCanNarrowSelection` | Isolation / negative integration |
| Missing or foreign trusted context drops the whole copy | `missingOrForeignPartnerContextProducesNoCopy` | Partner-security / negative integration |
| Stack, exception type, and customer-bearing exception message stay internal | `stackTraceRemainsOnlyOnTheOriginalLogEvent`, `exceptionContainingCustomerDataIsNeverCopied` | Payload-security / negative integration |
| Authorization, Base64, and oversize values never enter the safe payload | `authorizationEmbeddedInSelectedMessageIsNeverCopied`, `base64InSelectedLogLineIsNeverCopied`, `largeSelectedLogLineIsOmittedAsAWhole` | Payload-security / boundary integration |
| First-stage masking is applied and arbitrary objects are never rendered | `firstStageSanitizationMasksSelectedCustomerDataArgument`, `unsupportedArgumentIsOmittedWithoutCallingToString` | Payload-security / negative integration |
| Disabled mode and publisher failure preserve the original appender event | `disabledOrFailingPartnerObservabilityDoesNotAffectExistingLogbackPath` | Fault injection / framework integration |
| Logback remains an optional classpath integration | `missingOptionalLogbackClassesLeaveTheApplicationContextHealthy` | Conditional auto-configuration |

## References and supersession

This ADR refines the log-compatibility portion of ADR 0001 without weakening its data-class,
sanitization, or arbitrary-rendered-log prohibitions. Normative details remain in
`../architecture.md`, `../payload-policy.md`, `../security-invariants.md`, and
`../telemetry-contract.md`.
