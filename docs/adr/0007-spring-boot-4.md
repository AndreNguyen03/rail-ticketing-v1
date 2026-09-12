# ADR-0007 — Spring Boot 4.1, not 3.3

**Status:** Accepted · **Date:** 2026-09-12

## Context
README pins Spring Boot 3.3. start.spring.io now serves only `>= 4.0.0`
(available: 4.0.8, 4.1.1) — 3.x is out of OSS support. Staying on 3.3 would mean
hand-writing every service pom and forgoing Initializr from step 4 onwards.

## Decision
Spring Boot 4.1.1 with Spring Cloud 2025.1.3 and Java 21. Testcontainers is left
to Boot's BOM (2.0.5); no separate testcontainers-bom import.

## Consequences
**Gain:** supported versions, Initializr usable, no hand-maintained poms.
**Cost:** most tutorials target Boot 3.x, and several artifact ids were renamed
in Boot 4 — see the table in docs/COMMANDS.md.
**Revisit when:** — (greenfield project, no reason to stay behind).
