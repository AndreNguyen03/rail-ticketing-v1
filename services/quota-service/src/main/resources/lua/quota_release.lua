-- Stage 7 — Quota release Lua script.
-- Atomically decrements N passenger quota counters and deletes idem markers.
-- Uses KEEPTTL so the key expiry (tied to sale window end) is preserved.
-- Safe on single-instance Redis (redis:7-alpine).
--
-- KEYS[1..N]    = quota:{saleWindowId}:{fromStation}:{toStation}:{passengerIdNumber}
-- KEYS[N+1..2N] = quota:idem:{bookingId}:{passengerIdNumber}
-- ARGV[1] = N   (number of passengers)
--
-- Returns: 1

local n = tonumber(ARGV[1])

for i = 1, n do
  local cur = tonumber(redis.call('GET', KEYS[i]) or '0')
  if cur > 1 then
    -- KEEPTTL: preserve existing TTL (Redis 6+, supported by redis:7-alpine).
    redis.call('SET', KEYS[i], tostring(cur - 1), 'KEEPTTL')
  else
    redis.call('DEL', KEYS[i])
  end
  redis.call('DEL', KEYS[n + i])
end

return 1
