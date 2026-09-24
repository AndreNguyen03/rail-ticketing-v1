-- Stage 7 — Quota check-and-reserve Lua script (docs/05 §9, Stage 7).
-- Atomically checks N passengers against their quota limits, then increments
-- all counters. All-or-nothing: if any passenger would exceed the limit, no
-- counter is modified. Safe on single-instance Redis (redis:7-alpine).
--
-- KEYS[1..N]    = quota:{saleWindowId}:{fromStation}:{toStation}:{passengerIdNumber}
-- KEYS[N+1..2N] = quota:idem:{bookingId}:{passengerIdNumber}  (idempotency markers)
-- ARGV[1] = N  (number of passengers)
-- ARGV[2] = quota limit (e.g. "4")
-- ARGV[3] = ttlSeconds  (sale window end - now, for EXPIRE)
-- ARGV[4] = bookingId   (stored in idem marker value)
--
-- Returns:
--   {1, "RESERVED"}          — all incremented, idem markers set
--   {1, "IDEMPOTENT_REPLAY"} — booking already reserved, no-op
--   {0, "<1-based index>"}   — passenger at index would exceed limit

local n     = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local ttl   = tonumber(ARGV[3])
local bookingId = ARGV[4]

-- Phase 1: idempotency — if any idem marker exists, this booking was already
-- reserved. Return success without touching quota counters.
for i = 1, n do
  if redis.call('EXISTS', KEYS[n + i]) == 1 then
    return {1, 'IDEMPOTENT_REPLAY'}
  end
end

-- Phase 2: check all counts. No counters are modified here.
for i = 1, n do
  local cur = tonumber(redis.call('GET', KEYS[i]) or '0')
  if cur + 1 > limit then
    return {0, tostring(i)}
  end
end

-- Phase 3: commit. Increment every quota counter and set idem markers.
for i = 1, n do
  redis.call('INCR', KEYS[i])
  redis.call('EXPIRE', KEYS[i], ttl)
  redis.call('SET', KEYS[n + i], bookingId, 'EX', ttl)
end

return {1, 'RESERVED'}
