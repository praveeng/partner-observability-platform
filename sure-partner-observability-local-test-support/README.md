# Sure Partner Observability local test support

This non-production helper connects one explicitly configured `local` partner identity to the
isolated Docker tenant gateway. It exists for a selected real service's local test runtime; normal
partner services still consume only `sure-partner-observability-spring-boot-starter` in deployed
artifacts.

Use this module only through a Gradle development/local configuration. Its auto-configuration is
inactive unless all of the following are true:

- the active Spring profile is exactly `local`;
- `partner-observability.local-synthetic=true`;
- `partner-observability.local-test-transport.enabled=true`;
- the endpoint is the fixed local `/v1/logs` gateway on `localhost`, loopback, or
  `tenant-gateway`;
- the fixed partner key is one of the application's configured, server-trusted partners.

The username/password and route are runtime inputs. Do not commit even synthetic runtime
credentials to a selected service. Publishing remains on the starter's bounded dispatcher thread;
gateway failure drops telemetry through the existing availability boundary and never introduces a
business-thread network call.
