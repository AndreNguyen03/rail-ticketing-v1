# ADR-0001 — Maven aggregator + parent for version management only

**Status:** Accepted · **Date:** 2026-09-12

## Decision
Root pom is `packaging=pom` and holds `<modules>`, `<dependencyManagement>` (BOM
imports) and `<pluginManagement>`. It does NOT hold `<dependencies>` or
`<build><plugins>`. Each service declares its own dependencies without versions.

## Consequences
**Gain:** consistent versions, one place to bump Spring Boot; a parent already in
place for extracting `platform-commons` at stage 3.
**Cost:** all services move to a new Spring Boot version together.
**Revisit when:** a service is not written in Java (`waiting-room`, Go, stage 6).
