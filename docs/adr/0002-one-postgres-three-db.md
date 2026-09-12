# ADR-0002 — One Postgres instance, three databases, three users

**Status:** Accepted · **Date:** 2026-09-12

## Decision
A single Postgres container hosting `scheduledb`, `inventorydb` and `bookingdb`.
Three users, each able to `CONNECT` only to its own database.
`REVOKE CONNECT ... FROM PUBLIC` on every database.

## Consequences
**Gain:** Postgres cannot JOIN across databases, so the service boundary is
enforced by the engine rather than by discipline. Less RAM than three containers.
**Cost:** shared failure domain — if Postgres dies, all three services die.
**Revisit when:** real fault isolation is needed, or one database starves another.

## Verification
    docker compose exec postgres psql -U booking_user -d inventorydb -c "select 1"
    # must fail: FATAL: permission denied for database "inventorydb"
