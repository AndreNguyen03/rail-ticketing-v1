/**
 * Stage-1 load test: high-concurrency hold contention.
 *
 * Simulates N virtual users all trying to book berths on the same trip at the
 * same time. With 506 berths and 1,000 VUs the majority will get a "sold out"
 * response — that is expected and not a test failure. The real assertion is
 * that verify.sh stays green afterwards: no double-sold berths, no price
 * mismatches, no corrupt bitmasks.
 *
 * Usage (local):
 *   k6 run loadtest/k6/hold-contention.js
 *
 * Env overrides:
 *   GATEWAY_URL   http://localhost:8080   (default)
 *   TRIP_ID       1                       (SE1 2026-02-14, seeded by schedule-service)
 *   VUS           1000                    (virtual users)
 *   DURATION      60s
 */

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

// ── Config ───────────────────────────────────────────────────────────────────

const GATEWAY  = __ENV.GATEWAY_URL || 'http://localhost:8080';
const TRIP_ID  = parseInt(__ENV.TRIP_ID  || '1');
const VUS      = parseInt(__ENV.VUS      || '1000');
const DURATION = __ENV.DURATION          || '60s';

export const options = {
  vus: VUS,
  duration: DURATION,
  thresholds: {
    // 5xx errors must stay below 5% — sold-out 409/422 are expected and not counted here
    server_errors:      ['rate<0.05'],
    // At least one booking must complete successfully end-to-end
    booking_confirmed:  ['count>0'],
    // Hold call p99 under 2 s (the hold is the hottest path)
    'http_req_duration{name:hold}': ['p(99)<2000'],
  },
};

// ── Custom metrics ────────────────────────────────────────────────────────────

const holdSoldOut       = new Rate('hold_sold_out');       // 409 / 422 — berths gone
const serverErrors      = new Rate('server_errors');       // 5xx — unexpected failures
const bookingConfirmed  = new Counter('booking_confirmed'); // full end-to-end successes
const holdLatency       = new Trend('hold_latency_ms', true);

// ── Helpers ───────────────────────────────────────────────────────────────────

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

// Deterministic 12-digit national ID unique per (VU, iteration)
function idNumber() {
  const n = ((__VU - 1) * 100000 + __ITER) % 1000000000000;
  return String(n).padStart(12, '0');
}

// Valid Vietnamese mobile number: 0 + 9 digits
function phone() {
  const n = 300000000 + ((__VU * 997 + __ITER * 31) % 600000000);
  return '0' + String(n);
}

const BASE_HEADERS = {
  'Content-Type': 'application/json',
  'X-Identity': 'load-test',
};

function headers() {
  return { ...BASE_HEADERS, 'Idempotency-Key': uuidv4() };
}

// ── Main scenario ─────────────────────────────────────────────────────────────

export default function () {
  // ── Step 1: hold (the contention point) ───────────────────────────────────
  const holdRes = http.post(
    `${GATEWAY}/api/v1/holds`,
    JSON.stringify({
      tripId:            TRIP_ID,
      fromStationIndex:  0,   // Hà Nội (index 0)
      toStationIndex:    19,  // Sài Gòn (index 19) — full journey, maximum contention
      quantity:          1,
    }),
    { headers: headers(), tags: { name: 'hold' } }
  );

  holdLatency.add(holdRes.timings.duration);

  const soldOut = holdRes.status === 409 || holdRes.status === 422;
  holdSoldOut.add(soldOut);
  serverErrors.add(holdRes.status >= 500);

  check(holdRes, {
    'hold: 201 or sold-out': r => r.status === 201 || r.status === 409 || r.status === 422,
  });

  if (holdRes.status !== 201) return;

  const holdId = holdRes.json('holdId');
  if (!holdId) return;

  // ── Step 2: create booking ─────────────────────────────────────────────────
  const bookRes = http.post(
    `${GATEWAY}/api/v1/bookings`,
    JSON.stringify({
      holdId: holdId,
      contact: {
        fullName: `Passenger ${__VU}`,
        phone:    phone(),
      },
      passengers: [{
        fullName:      `Passenger ${__VU}`,
        idNumber:      idNumber(),
        passengerType: 'ADULT',
      }],
    }),
    { headers: headers(), tags: { name: 'booking_create' } }
  );

  serverErrors.add(bookRes.status >= 500);
  check(bookRes, { 'booking: 201': r => r.status === 201 });
  if (bookRes.status !== 201) return;

  const bookingId = bookRes.json('bookingId');
  if (!bookingId) return;

  // ── Step 3: confirm booking ────────────────────────────────────────────────
  const confirmRes = http.post(
    `${GATEWAY}/api/v1/bookings/${bookingId}/confirm`,
    JSON.stringify({ paymentMethod: 'MOCK' }),
    { headers: headers(), tags: { name: 'booking_confirm' } }
  );

  serverErrors.add(confirmRes.status >= 500);
  check(confirmRes, { 'confirm: 200': r => r.status === 200 });

  if (confirmRes.status === 200) {
    bookingConfirmed.add(1);
  }
}

// ── Summary ───────────────────────────────────────────────────────────────────

export function handleSummary(data) {
  const confirmed  = data.metrics.booking_confirmed?.values?.count  ?? 0;
  const soldOut    = data.metrics.hold_sold_out?.values?.passes      ?? 0;
  const srvErrors  = data.metrics.server_errors?.values?.passes      ?? 0;
  const holdP99    = data.metrics['http_req_duration{name:hold}']?.values?.['p(99)'] ?? 0;

  const lines = [
    '',
    '┌─────────────────────────────────────────┐',
    '│       Hold-contention load test         │',
    '├─────────────────────────────────────────┤',
    `│  Bookings confirmed : ${String(confirmed).padStart(6)}              │`,
    `│  Holds sold-out     : ${String(soldOut).padStart(6)}  (expected)    │`,
    `│  Server errors (5xx): ${String(srvErrors).padStart(6)}              │`,
    `│  Hold p99 latency   : ${String(Math.round(holdP99)).padStart(4)} ms             │`,
    '└─────────────────────────────────────────┘',
    '',
    'Run tools/verify/verify.sh next to check invariants.',
    '',
  ];

  return { stdout: lines.join('\n') };
}
