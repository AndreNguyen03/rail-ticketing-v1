# ADR-0005 — Bitmask segment inventory

**Status:** Accepted · **Date:** 2026-09-12

## Decision
Each berth carries two `int32` values, `occupied_mask` and `held_mask`. A conflict
check is `(occupied | held) & journeyMask == 0`. A `berth_reservation` table with
an `EXCLUDE USING gist` constraint over `int4range` acts as a second safety net.

## Consequences
**Gain:** a whole trip's inventory fits in ~4 KB; the move to Redis Lua at stage 4
needs no change to the data model.
**Cost:** capped at 29 legs (the longest Vietnamese line has ~30 stations).
**Revisit when:** a line needs more than 29 stations.
