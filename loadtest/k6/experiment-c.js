/**
 * Stage-2 Experiment C: 1 trip vs 49 trips at the same total VU count.
 *
 * Theory: contention is proportional to (concurrent requests / berths per trip).
 * Spreading 1 000 VUs across 49 trips means each trip sees ~20 VUs instead of 1 000
 * — row-level lock queue shrinks by 49x, throughput rises nearly linearly.
 *
 * Run twice and compare:
 *
 *   # A — single trip, maximum contention (same as baseline)
 *   k6 run -e TRIP_POOL_SIZE=1 loadtest/k6/experiment-c.js
 *
 *   # B — 49 trips, contention partitioned
 *   k6 run -e TRIP_POOL_SIZE=49 loadtest/k6/experiment-c.js
 *
 * Env overrides:
 *   GATEWAY_URL     http://localhost:8080
 *   TRIP_BASE_ID    1     first trip ID in the pool (SE1 2026-02-14)
 *   TRIP_POOL_SIZE  1     how many consecutive trip IDs to spread across
 *   VUS             1000
 *   DURATION        60s
 */

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

// ── Config ────────────────────────────────────────────────────────────────────

const GATEWAY       = __ENV.GATEWAY_URL     || 'http://localhost:8080';
const TRIP_BASE_ID  = parseInt(__ENV.TRIP_BASE_ID  || '1');
const TRIP_POOL     = parseInt(__ENV.TRIP_POOL_SIZE || '1');
const VUS           = parseInt(__ENV.VUS            || '1000');
const DURATION      = __ENV.DURATION                || '60s';

export const options = {
  vus:      VUS,
  duration: DURATION,
  thresholds: {
    server_errors:                        ['rate<0.05'],
    booking_confirmed:                    ['count>0'],
    'http_req_duration{name:hold}':       ['p(99)<10000'],
  },
};

// ── Metrics ───────────────────────────────────────────────────────────────────

const holdSoldOut      = new Rate('hold_sold_out');
const serverErrors     = new Rate('server_errors');
const bookingConfirmed = new Counter('booking_confirmed');
const holdLatency      = new Trend('hold_latency_ms', true);

// ── Helpers ───────────────────────────────────────────────────────────────────

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

function idNumber() {
  const n = ((__VU - 1) * 100000 + __ITER) % 1000000000000;
  return String(n).padStart(12, '0');
}

function phone() {
  const n = 300000000 + ((__VU * 997 + __ITER * 31) % 600000000);
  return '0' + String(n);
}

function pickTripId() {
  // Uniform random across the pool: each VU independently picks a trip.
  // With TRIP_POOL=1 this always returns TRIP_BASE_ID (single-trip scenario).
  return TRIP_BASE_ID + Math.floor(Math.random() * TRIP_POOL);
}

const BASE_HEADERS = { 'Content-Type': 'application/json', 'X-Identity': 'load-test' };
function headers() { return { ...BASE_HEADERS, 'Idempotency-Key': uuidv4() }; }

// ── Scenario ──────────────────────────────────────────────────────────────────

export default function () {
  const tripId = pickTripId();

  // Step 1: hold
  const holdRes = http.post(
    `${GATEWAY}/api/v1/holds`,
    JSON.stringify({
      tripId,
      fromStationIndex: 0,
      toStationIndex:   19,
      quantity:         1,
    }),
    { headers: headers(), tags: { name: 'hold' } }
  );

  holdLatency.add(holdRes.timings.duration);
  holdSoldOut.add(holdRes.status === 409 || holdRes.status === 422);
  serverErrors.add(holdRes.status >= 500);

  check(holdRes, { 'hold: 201 or sold-out': r => r.status === 201 || r.status === 409 || r.status === 422 });
  if (holdRes.status !== 201) return;

  const holdId = holdRes.json('holdId');
  if (!holdId) return;

  // Step 2: create booking
  const bookRes = http.post(
    `${GATEWAY}/api/v1/bookings`,
    JSON.stringify({
      holdId,
      contact:    { fullName: `Passenger ${__VU}`, phone: phone() },
      passengers: [{ fullName: `Passenger ${__VU}`, idNumber: idNumber(), passengerType: 'ADULT' }],
    }),
    { headers: headers(), tags: { name: 'booking_create' } }
  );

  serverErrors.add(bookRes.status >= 500);
  check(bookRes, { 'booking: 201': r => r.status === 201 });
  if (bookRes.status !== 201) return;

  const bookingId = bookRes.json('bookingId');
  if (!bookingId) return;

  // Step 3: confirm
  const confirmRes = http.post(
    `${GATEWAY}/api/v1/bookings/${bookingId}/confirm`,
    JSON.stringify({ paymentMethod: 'MOCK' }),
    { headers: headers(), tags: { name: 'booking_confirm' } }
  );

  serverErrors.add(confirmRes.status >= 500);
  check(confirmRes, { 'confirm: 200': r => r.status === 200 });
  if (confirmRes.status === 200) bookingConfirmed.add(1);
}

// ── Summary ───────────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const confirmed = data.metrics.booking_confirmed?.values?.count                        ?? 0;
  const soldOut   = data.metrics.hold_sold_out?.values?.passes                           ?? 0;
  const srvErrors = data.metrics.server_errors?.values?.passes                           ?? 0;
  const holdP99   = data.metrics['http_req_duration{name:hold}']?.values?.['p(99)']      ?? 0;
  const holdP50   = data.metrics['http_req_duration{name:hold}']?.values?.['p(50)']      ?? 0;
  const holdRps   = data.metrics['http_req_duration{name:hold}']?.values?.count          ?? 0;

  const scenario  = TRIP_POOL === 1
    ? `1 trip  (tripId=${TRIP_BASE_ID}) — maximum contention`
    : `${TRIP_POOL} trips (IDs ${TRIP_BASE_ID}–${TRIP_BASE_ID + TRIP_POOL - 1}) — partitioned`;

  const lines = [
    '',
    '┌──────────────────────────────────────────────────────┐',
    '│         Experiment C — contention partition          │',
    '├──────────────────────────────────────────────────────┤',
    `│  Scenario          : ${scenario.padEnd(30)} │`,
    `│  Bookings confirmed: ${String(confirmed).padStart(6)}                        │`,
    `│  Holds sold-out    : ${String(soldOut).padStart(6)}  (expected)              │`,
    `│  Server errors(5xx): ${String(srvErrors).padStart(6)}                        │`,
    `│  Hold p50 / p99    : ${String(Math.round(holdP50)).padStart(4)} ms / ${String(Math.round(holdP99)).padStart(4)} ms          │`,
    '└──────────────────────────────────────────────────────┘',
    '',
    'Compare p99 and confirmed count between the two runs.',
    'Run tools/verify/verify.sh after each run to check invariants.',
    '',
  ];

  return { stdout: lines.join('\n') };
}
