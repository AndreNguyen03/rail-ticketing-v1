/**
 * Stage-6 load test: same 1000-VU spike as loadtest/k6/hold-contention.js,
 * but this time through the waiting-room front door instead of hitting
 * /api/v1/holds directly. Point of the test: prove the "front-door
 * collapse under spike" observed repeatedly in docs/baseline.md (Stage 2
 * baseline §3, Stage 2 Experiment C) is actually smoothed out — the
 * backend should see a controlled, even release rate instead of 1000
 * connections arriving at once.
 *
 * Flow per VU: join waiting-room queue -> poll /verify until admitted
 * (bounded) -> only THEN call gateway's /api/v1/holds with the ticket.
 *
 * Usage:
 *   k6 run loadtest/k6/stage6-waiting-room.js
 *
 * Env overrides:
 *   WAITING_ROOM_URL  http://localhost:8085
 *   GATEWAY_URL       http://localhost:8080
 *   TRIP_ID           1
 *   VUS               1000
 *   DURATION          60s
 *   QUEUE_RESOURCE    stage6-trip-1        (waiting-room resource name — separate from TRIP_ID on purpose,
 *                                            so multiple test runs don't collide in the same Redis sorted set)
 *   POLL_INTERVAL_MS  500
 *   MAX_POLL_ATTEMPTS 60                   (60 * 500ms = 30s max wait before giving up)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const WAITING_ROOM   = __ENV.WAITING_ROOM_URL || 'http://localhost:8085';
const GATEWAY        = __ENV.GATEWAY_URL      || 'http://localhost:8080';
const TRIP_ID        = parseInt(__ENV.TRIP_ID || '1');
const VUS            = parseInt(__ENV.VUS     || '1000');
const DURATION       = __ENV.DURATION         || '60s';
const QUEUE_RESOURCE = __ENV.QUEUE_RESOURCE   || 'stage6-trip-1';
const POLL_INTERVAL  = parseInt(__ENV.POLL_INTERVAL_MS  || '500') / 1000;
const MAX_POLLS      = parseInt(__ENV.MAX_POLL_ATTEMPTS || '60');

export const options = {
  vus: VUS,
  duration: DURATION,
  thresholds: {
    server_errors: ['rate<0.05'],
    // Đúng điểm cần chứng minh: front-door (waiting-room join) không được sập
    // như /api/v1/holds trực tiếp đã từng sập ở baseline (connection refused ~5s đầu).
    'http_req_duration{name:queue_join}': ['p(99)<2000'],
  },
};

const holdSoldOut      = new Rate('hold_sold_out');
const serverErrors     = new Rate('server_errors');
const queueGaveUp      = new Rate('queue_gave_up');       // không được admit trong MAX_POLLS lần thử
const queueWaitMs      = new Trend('queue_wait_ms', true); // thời gian join -> admitted
const holdAfterAdmit   = new Counter('hold_after_admit');  // số request thật sự chạm tới /holds

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

const BASE_HEADERS = { 'Content-Type': 'application/json', 'X-Identity': 'load-test-stage6' };

export default function () {
  // ── Step 1: join hàng đợi ──────────────────────────────────────────────────
  const joinStart = Date.now();
  const joinRes = http.post(
    `${WAITING_ROOM}/queue/${QUEUE_RESOURCE}/join`,
    null,
    { tags: { name: 'queue_join' } }
  );
  check(joinRes, { 'join: 201': r => r.status === 201 });
  if (joinRes.status !== 201) return;

  const ticketId = joinRes.json('ID');
  if (!ticketId) return;

  // ── Step 2: poll tới khi admitted (thay cho SSE — k6 không có client SSE sẵn) ─
  let admitted = false;
  for (let i = 0; i < MAX_POLLS; i++) {
    const v = http.get(`${WAITING_ROOM}/tickets/${ticketId}/verify`, { tags: { name: 'queue_verify' } });
    if (v.status === 200 && v.json('admitted') === true) {
      admitted = true;
      break;
    }
    sleep(POLL_INTERVAL);
  }
  queueWaitMs.add(Date.now() - joinStart);
  queueGaveUp.add(!admitted);
  if (!admitted) return;

  // ── Step 3: gọi /holds thật, kèm ticket — chỉ chạm backend SAU khi được release ─
  holdAfterAdmit.add(1);
  const holdRes = http.post(
    `${GATEWAY}/api/v1/holds`,
    JSON.stringify({ tripId: TRIP_ID, fromStationIndex: 0, toStationIndex: 19, quantity: 1 }),
    {
      headers: { ...BASE_HEADERS, 'Idempotency-Key': uuidv4(), 'X-Queue-Ticket': ticketId },
      tags: { name: 'hold' },
    }
  );

  const soldOut = holdRes.status === 409 || holdRes.status === 422;
  holdSoldOut.add(soldOut);
  serverErrors.add(holdRes.status >= 500);
  check(holdRes, { 'hold: 201 or sold-out': r => r.status === 201 || r.status === 409 || r.status === 422 });
}
