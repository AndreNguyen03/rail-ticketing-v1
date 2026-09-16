-- KEYS[1] = inv:{tripId}:{class}
-- KEYS[2] = hold:{tripId}:{holdId}
-- ARGV[1] = journeyMask
-- Returns 1 if released, 0 if hold not found
local holdKey = KEYS[2]
local v = redis.call('GET', holdKey)
if not v then return 0 end
local ids, jmask = v:match("([^|]+)|([^|]+)")
jmask = tonumber(jmask or ARGV[1])
-- for each berth, clear held bits
for id in string.gmatch(ids, "[^,]+") do
  local cur = redis.call('HGET', KEYS[1], id)
  if cur then
    local occ, held, level, carriage, berthNo, price = cur:match("([^,]+),([^,]+),([^,]+),([^,]+),([^,]+),([^,]+)")
    local newHeld = bit.band(tonumber(held), bit.bnot(jmask))
    redis.call('HSET', KEYS[1], id, occ .. ',' .. newHeld .. ',' .. level .. ',' .. carriage .. ',' .. berthNo .. ',' .. price)
  end
end
redis.call('DEL', holdKey)
return 1
