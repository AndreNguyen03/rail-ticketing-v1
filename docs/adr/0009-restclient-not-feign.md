# ADR-0009 — RestClient for service-to-service calls, not Feign

**Status:** Accepted · **Date:** 2026-09-12

## Context

`booking-service` calls `inventory-service`. `api-gateway` does not need a
client at all — it is a reverse proxy that forwards requests at the HTTP level
and never deserializes a payload. Giving the gateway a client would force it to
know every DTO, so a schema change would require redeploying the gateway.

Three options for the one real call site:

1. Plain `RestClient` — no magic, every call visible in code.
2. `@HttpExchange` interface clients — declarative, Spring-native, auto-configured
   by `HttpServiceClientAutoConfiguration` in `spring-boot-restclient`, built on
   top of `RestClient`.
3. OpenFeign (`spring-cloud-starter-openfeign`, 5.0.3 is in the 2025.1.3 train)
   — declarative, but a Spring Cloud dependency, and OpenFeign has been in
   maintenance mode for years while Spring's own direction is `@HttpExchange`.

## Decision

Plain `RestClient` with the base URL from the environment, as
`docs/05-build-progression.md` §2 specifies. `spring-boot-starter-restclient` is
added to `booking-service` only — `spring-boot-starter-webmvc` does not bring it
in under Boot 4's modular autoconfiguration, so without it there is no
auto-configured `RestClient.Builder`.

No client dependency in `api-gateway`, `schedule-service` or `inventory-service`:
none of them calls another service at stage 0.

## Consequences

**Gain:** stage 3 adds timeouts, retry, circuit breakers and bulkheads to these
calls. With a plain client you attach each one yourself and see exactly what it
costs — which is the point of that stage. A declarative client would hide the
wiring behind annotations at the moment you are trying to learn it.

**Cost:** more boilerplate per call site. With two call sites, that is a few
dozen lines.

**Revisit when:** call sites reach roughly ten, or stage 5 introduces enough
service-to-service traffic that the boilerplate hurts. Move to `@HttpExchange`
then, not to Feign: it sits on `RestClient`, so everything configured at stage 3
keeps working and no Spring Cloud dependency is added.
