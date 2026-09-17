-- Stage 4 — Hold Lua script (docs/03 §5-6: HGETALL + FIRST_FIT + random scanOffset,
-- generalized from the doc's single-berth example to hold `quantity` berths in one
-- atomic call — a group booking needs all-or-nothing across several berths)
-- KEYS[1] = inv:{tripId}:{class}  (Hash: berthId -> "occ,held,level,carriage,berthNo,price")
-- KEYS[2] = hold:{tripId}:{holdId} (String, TTL)
-- ARGV[1] = journeyMask (int)
-- ARGV[2] = quantity (int)
-- ARGV[3] = holdId
-- ARGV[4] = ttlSeconds
-- ARGV[5] = scanOffset (int random)
-- Returns: {1, "berthId1,berthId2", "HELD"} or {0, "", "NO_BERTH_AVAILABLE"} or {1, prev, "IDEMPOTENT_REPLAY"}

local jmask = tonumber(ARGV[1])
local qty = tonumber(ARGV[2])
local holdKey = KEYS[2]

if redis.call('EXISTS', holdKey) == 1 then
  local prev = redis.call('GET', holdKey)
  return {1, prev, 'IDEMPOTENT_REPLAY'}
end

local inv = redis.call('HGETALL', KEYS[1])
if #inv == 0 then
  return {0, '', 'NO_INVENTORY'}
end

-- parse hash
local berths = {}
for i=1, #inv, 2 do
  local id = inv[i]
  local v = inv[i+1]
  local occ, held, level, carriage, berthNo, price = v:match("([^,]+),([^,]+),([^,]+),([^,]+),([^,]+),([^,]+)")
  berths[#berths+1] = {id=id, occ=tonumber(occ), held=tonumber(held), level=tonumber(level), carriage=tonumber(carriage), berthNo=tonumber(berthNo), price=price}
end

local n = #berths
local offset = tonumber(ARGV[5]) % n
local chosen = {}
local seen = 0
for k=0, n-1 do
  local b = berths[((offset + k) % n) + 1]
  if bit.band(bit.bor(b.occ, b.held), jmask) == 0 then
    chosen[#chosen+1] = b
    if #chosen == qty then break end
  end
end

if #chosen < qty then
  return {0, '', 'NO_BERTH_AVAILABLE'}
end

-- Defensive re-check (docs/03 §5): impossible in practice — Lua runs this whole
-- script atomically, single-threaded, so no other script can touch these keys
-- between the scan above and the HSET below. Kept anyway so a test can assert
-- the assumption; if this ever fires, that assumption is wrong.
for _, b in ipairs(chosen) do
  if bit.band(bit.bor(b.occ, b.held), jmask) ~= 0 then
    return {0, '', 'RACE_DETECTED'}
  end
end

-- commit held bits
local ids = {}
for _, b in ipairs(chosen) do
  local newHeld = bit.bor(b.held, jmask)
  redis.call('HSET', KEYS[1], b.id, b.occ .. ',' .. newHeld .. ',' .. b.level .. ',' .. b.carriage .. ',' .. b.berthNo .. ',' .. b.price)
  ids[#ids+1] = b.id
end

local idStr = table.concat(ids, ',')
redis.call('SET', holdKey, idStr .. '|' .. jmask, 'EX', tonumber(ARGV[4]))

return {1, idStr, 'HELD'}
