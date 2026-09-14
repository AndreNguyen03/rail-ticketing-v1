#!/usr/bin/env bash
# Stage-1 invariant checker.
#
# Reads inventorydb and bookingdb SEPARATELY (no cross-DB JOIN — different
# PostgreSQL users cannot see each other's schema). Cross-DB checks read both
# sides and compare in memory via shell variables.
#
# Exit 0 = all invariants green.
# Exit 1 = at least one invariant failed.
#
# Usage:
#   ./tools/verify/verify.sh
#
# Environment (defaults match docker-compose):
#   BOOKING_DB_URL    postgresql://booking_user:booking_pw@localhost:5432/bookingdb
#   INVENTORY_DB_URL  postgresql://inventory_user:inventory_pw@localhost:5432/inventorydb

set -euo pipefail

BOOKING="${BOOKING_DB_URL:-postgresql://booking_user:booking_pw@localhost:5432/bookingdb}"
INVENTORY="${INVENTORY_DB_URL:-postgresql://inventory_user:inventory_pw@localhost:5432/inventorydb}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'

FAILURES=0

pass() { printf "${GREEN}✓ PASS${NC}  %s\n" "$1"; }
fail() { printf "${RED}✗ FAIL${NC}  %s\n" "$1"; FAILURES=$((FAILURES + 1)); }
skip() { printf "${YELLOW}⊘ SKIP${NC}  %s\n" "$1"; }

# Run SQL against a database URL, return first cell of first row, trimmed.
scalar() { psql "$1" -t -A -c "$2" 2>/dev/null | head -1 | tr -d '[:space:]'; }

printf "\n${BOLD}=== Rail Ticketing — invariant check ===${NC}\n\n"

# ── INV-1 (inventorydb) ───────────────────────────────────────────────────────
# A bit set in both held_mask and occupied_mask means the same leg on the same
# berth is simultaneously held (pending) and occupied (sold) — impossible by
# design, fatal if seen.
printf "INV-1  ${BOLD}inventorydb${NC}: (held_mask & occupied_mask) = 0 for every berth\n"
n=$(scalar "$INVENTORY" \
  "SELECT count(*) FROM berth_inventory WHERE (held_mask & occupied_mask) <> 0;")
if [ "${n:-0}" -eq 0 ]; then
  pass "INV-1: no berth has a leg simultaneously held and occupied"
else
  fail "INV-1: ${n} berth(s) have overlapping held/occupied bits — inventory is corrupt"
  psql "$INVENTORY" -c \
    "SELECT berth_id, trip_id, to_hex(held_mask) AS held, to_hex(occupied_mask) AS occupied
     FROM berth_inventory WHERE (held_mask & occupied_mask) <> 0 LIMIT 10;" 2>/dev/null || true
fi

printf "\n"

# ── INV-2 (cross-DB) ─────────────────────────────────────────────────────────
# A CONFIRMED booking should have released its hold. If hold_id is still non-null
# on a CONFIRMED booking AND that hold_id still exists in inventorydb, the
# booking saga left a dangling hold.
printf "INV-2  ${BOLD}cross-DB${NC}: no CONFIRMED booking has a live hold in inventorydb\n"
hold_csv=$(scalar "$BOOKING" \
  "SELECT string_agg(quote_literal(hold_id::text), ',')
   FROM booking
   WHERE status = 'CONFIRMED' AND hold_id IS NOT NULL;")

if [ -z "$hold_csv" ]; then
  pass "INV-2: no confirmed booking carries a hold_id"
else
  n=$(scalar "$INVENTORY" \
    "SELECT count(*) FROM hold WHERE hold_id::text IN (${hold_csv});")
  if [ "${n:-0}" -eq 0 ]; then
    pass "INV-2: hold_ids from confirmed bookings are gone from inventorydb"
  else
    fail "INV-2: ${n} hold(s) still active in inventorydb for CONFIRMED booking(s)"
  fi
fi

printf "\n"

# ── INV-3 (bookingdb) ────────────────────────────────────────────────────────
# The booking total must equal the sum of its non-refunded ticket prices.
# A mismatch means either a double-charge or a ticket was silently lost.
printf "INV-3  ${BOLD}bookingdb${NC}: total_price_vnd = sum(ticket.price_vnd) for every CONFIRMED booking\n"
n=$(scalar "$BOOKING" "
  SELECT count(*) FROM booking b
  WHERE b.status = 'CONFIRMED'
    AND b.total_price_vnd <> COALESCE(
          (SELECT sum(price_vnd) FROM ticket
           WHERE booking_id = b.booking_id AND status <> 'REFUNDED'), 0);")
if [ "${n:-0}" -eq 0 ]; then
  pass "INV-3: all confirmed bookings balance"
else
  fail "INV-3: ${n} booking(s) have a price mismatch — potential double-charge or silent data loss"
  psql "$BOOKING" -c "
    SELECT b.booking_id, b.total_price_vnd AS booking_total,
           COALESCE((SELECT sum(price_vnd) FROM ticket
                     WHERE booking_id = b.booking_id AND status <> 'REFUNDED'), 0) AS ticket_total
    FROM booking b
    WHERE b.status = 'CONFIRMED'
      AND b.total_price_vnd <> COALESCE(
            (SELECT sum(price_vnd) FROM ticket
             WHERE booking_id = b.booking_id AND status <> 'REFUNDED'), 0)
    LIMIT 10;" 2>/dev/null || true
fi

printf "\n"

# ── INV-4 (cross-DB) ─────────────────────────────────────────────────────────
# Every ISSUED ticket references a berth_id. That berth must have a non-zero
# occupied_mask in inventorydb — otherwise a ticket exists for a berth that
# was never actually marked as occupied in the inventory engine.
printf "INV-4  ${BOLD}cross-DB${NC}: every ISSUED ticket's berth has occupied_mask > 0 in inventorydb\n"
berth_csv=$(scalar "$BOOKING" \
  "SELECT string_agg(DISTINCT berth_id::text, ',') FROM ticket WHERE status = 'ISSUED';")

if [ -z "$berth_csv" ]; then
  pass "INV-4: no ISSUED tickets yet"
else
  n=$(scalar "$INVENTORY" \
    "SELECT count(*) FROM berth_inventory
     WHERE berth_id IN (${berth_csv}) AND occupied_mask = 0;")
  if [ "${n:-0}" -eq 0 ]; then
    pass "INV-4: all ISSUED-ticket berths are marked occupied in inventorydb"
  else
    fail "INV-4: ${n} berth(s) referenced by ISSUED tickets have occupied_mask = 0 in inventorydb"
  fi
fi

printf "\n"

# ── INV-5 (Stage 4 — skipped) ────────────────────────────────────────────────
skip "INV-5: Redis ↔ PostgreSQL bitmask reconciliation — added at stage 4"

printf "\n${BOLD}════════════════════════════════════════${NC}\n"
if [ "$FAILURES" -eq 0 ]; then
  printf "${GREEN}${BOLD}All invariants GREEN ✓${NC}\n\n"
  exit 0
else
  printf "${RED}${BOLD}${FAILURES} invariant(s) FAILED ✗${NC}\n\n"
  exit 1
fi
