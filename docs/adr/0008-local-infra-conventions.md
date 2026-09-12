# ADR-0008 — Local infrastructure conventions

**Status:** Accepted · **Date:** 2026-09-12

## Context
docker-compose.yaml is a local development fixture. Kubernetes replaces it at
stage 10, where secrets come from K8s Secrets and restarts from the kubelet.

## Decision
1. Credentials are hardcoded in compose and in `infra/postgres/init/*.sql`.
   `.env` carries machine-specific config only (`POSTGRES_PORT`), never secrets.
2. No restart policy. Stages 4 and 11 kill infrastructure on purpose
   (`docker kill redis`) to observe failure; `unless-stopped` would defeat that.

## Consequences
**Gain:** `git clone && docker compose up -d` works with no setup step.
Failure injection behaves as written.
**Cost:** dev passwords are in version control — acceptable, the database holds
only generated data and is never exposed beyond localhost.
**Revisit when:** stage 8 (Keycloak) introduces the first secret that matters,
and stage 10 moves to Kubernetes.
