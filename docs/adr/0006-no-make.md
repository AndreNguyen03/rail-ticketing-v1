# ADR-0006 — No make

**Status:** Accepted · **Date:** 2026-09-12

## Context
README §8 specifies `make infra-up` / `make seed` / `make verify` / `make loadtest`.
The development machine runs Windows. GNU make on Windows defaults to `cmd.exe`
as SHELL, so a recipe that works on CI (Linux/sh) breaks on the dev machine.

## Decision
Stages 0-2 use no task runner. Commands are invoked directly and documented in
`docs/COMMANDS.md`. CI invokes exactly the same commands.

## Consequences
**Gain:** nothing to install, and no behavioural gap between dev machine and CI.
**Cost:** longer commands; README and docs/05 must be updated.
**Revisit when:** a command needs multiple steps or a dependency (e.g. `verify`
must `build` first) — move to Taskfile, not back to make.
