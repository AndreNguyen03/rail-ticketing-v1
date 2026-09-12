# ADR-0003 — No platform-commons at stage 0

**Status:** Accepted · **Date:** 2026-09-12

## Context
Infrastructure code (correlation id, RFC 9457 ProblemDetail, idempotency) is
about 150 lines and repeats in every service.

## Decision
No shared module. Infrastructure code is copied into each service from
`templates/service-template/` (step 6). Error-format consistency is enforced by
contract tests against `contracts/openapi.yaml`, not by a shared class.

## Consequences
**Gain:** no shared build artifact at all; the rule also binds a service written
in Go (stage 6), which a Java jar never could.
**Cost:** an infrastructure bug must be fixed in N services.
**Revisit when:** one piece of infrastructure has changed >=3 times AND a copy
was once missed, causing a bug. Expected at stage 3 (timeout/retry/circuit breaker).
