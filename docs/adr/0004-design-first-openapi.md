# ADR-0004 — Design-first OpenAPI, enforced by CI

**Status:** Accepted · **Date:** 2026-09-12

## Decision
`contracts/openapi.yaml` is hand-written and is the source of truth; code must
match it. We do NOT generate the spec from annotations (code-first) — that makes
the spec a snapshot of the implementation, so `openapi-diff` could never go red.
CI runs `openapi-diff` against `main` and fails on a breaking change.

## Consequences
**Gain:** the API is the one invariant that survives stages 0-11, machine-checked
the same way the four inventory invariant queries are.
**Cost:** ~4 hours to write the spec; changing the API becomes a deliberate act.
**Revisit when:** — (project invariant).
