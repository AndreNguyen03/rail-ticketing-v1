# 03 — Segment Inventory Engine

> The heart of the system. Every other service exists to serve it or protect it.
>
> The problem: **one berth, several passengers, no overlapping legs** — under 10,000
> concurrent requests.

---

## 1. Why "available / sold out" is the wrong model

An airline seat: one seat, one flight, one passenger. A boolean.

A North–South train berth: **one bunk can be sold to five different passengers on the same
trip.**

```
SE1 · 2026-02-14 · Bunk 12, carriage 5, level 1

HN ─── Vinh ─── Hue ─── DN ─── NT ─── SG
 0      1       2       3      4      5
 └─ Passenger A ┘
                 └──────┘ free
                          └─ Passenger B ┘

One bunk. Two tickets. Entirely valid.
A leaves at Hue, staff change the linen, B boards at Da Nang.
```

An `available: true/false` model cannot express this. And if the model is wrong here,
**everything above it is wrong** — wrong display, wrong reservations, and ~40% of revenue
lost by selling a bunk to one passenger instead of three.

---

## 2. Three ways to model it — and why the third wins

### Option 1 — One row per (berth × leg)

```sql
CREATE TABLE berth_leg (
    trip_id  TEXT, berth_id INT, leg_idx SMALLINT,
    status   TEXT,             -- FREE | HELD | SOLD
    PRIMARY KEY (trip_id, berth_id, leg_idx)
);
```

| Problem | Number |
|---|---|
| Rows | 500 berths × 29 legs = **14,500 rows/trip** · 200 trips × 20 days = **58 million rows** |
| One hold | `UPDATE` of 5–29 rows in one transaction |
| Deadlock | Two requests lock legs **in different orders** ⇒ deadlock. Lock ordering must be forced |
| Counting availability | `GROUP BY berth_id HAVING count(*) = 29` — a full scan |

**Rejected.** Logically correct, but every operation touches dozens of rows — exactly what
you do not want with 10,000 competing requests.

### Option 2 — Ranges plus a PostgreSQL exclusion constraint

```sql
CREATE TABLE berth_reservation (
    trip_id   TEXT   NOT NULL,
    berth_id  INT    NOT NULL,
    leg_range int4range NOT NULL,
    status    TEXT   NOT NULL,
    EXCLUDE USING gist (
        trip_id WITH =, berth_id WITH =, leg_range WITH &&
    ) WHERE (status IN ('HELD','SOLD'))
);
```

PostgreSQL **guarantees** that no two reservations overlap on the same bunk. The constraint
lives in the database and cannot be bypassed.

| Pro | Con |
|---|---|
| ✅ Absolutely correct, no code needed to hold the invariant | ❌ ~200 holds/s under high contention |
| ✅ One row per reservation | ❌ The GiST index is a write bottleneck |
| ✅ Very easy to understand | ❌ Counting availability is still expensive |

**Kept — but for the cold path.** This is the durable constraint in PostgreSQL
([02 §4](02-architecture.md)). It is the last safety net: even if Redis is wrong, the
database refuses an overlapping write.

### Option 3 — Bitmask ⭐

Per berth: **two 32-bit integers**.

```
occupiedMask  bit i = 1  ⇔  leg L_i is SOLD
heldMask      bit i = 1  ⇔  leg L_i is temporarily HELD
```

| Operation | Expression |
|---|---|
| Journey from station `a` to station `b` | `mask = (1 << b) - (1 << a)` |
| Is the berth free for this journey? | `((occupied \| held) & mask) == 0` |
| Hold | `held \|= mask` |
| Confirm (paid) | `held &= ~mask ; occupied \|= mask` |
| Release (expiry / cancel) | `held &= ~mask` |
| Refund | `occupied &= ~mask` |

Checking the mask formula:

```
a=0, b=2  (HN → Hue)   (1<<2)-(1<<0) = 4-1  = 3  = 0b00011  → legs L0, L1  ✓
a=3, b=5  (DN → SG)    (1<<5)-(1<<3) = 32-8 = 24 = 0b11000  → legs L3, L4  ✓
0b00011 & 0b11000 = 0                                       → no conflict  ✓
```

**Every operation is a single CPU instruction.** No joins, no scans, no row locks, no
deadlocks.

---

## 3. The observation that decides the architecture

Vietnam's longest line — Hanoi ↔ Saigon — has about 30 stops ⇒ **29 legs ⇒ fits in
`int32`**.

A trip has ~500 berths:

$$
500 \text{ berths} \times (4 + 4) \text{ bytes} = \mathbf{4\ KB}
$$

**A whole trip's inventory is 4 KB.**

The consequences chain:

```
4 KB
 ├─▶ fits in ONE Redis value
 │    └─▶ one Lua script reads, modifies and writes it whole
 │         └─▶ Redis is single-threaded ⇒ ATOMIC, NO LOCK NEEDED
 │              └─▶ no deadlocks, no retries, no lock expiring at the wrong moment
 │                   └─▶ ~25,000 operations/second/trip
 └─▶ fits in CPU L2 cache ⇒ scanning 500 berths takes microseconds
```

This is why this extreme contention problem is **solvable**. And you only see it if you
model inventory as bitmasks instead of SQL rows.

> **A limit worth knowing:** `int32` holds 32 legs. Vietnamese lines top out at ~29 — just
> enough, with little margin. A longer line needs `int64` (63 legs) or two `int32`. Write
> that limit into a database constraint; do not discover it at runtime.

---

## 4. Redis data layout

```
Key:   inv:{SE1-2026-02-14}:SOFT_SLEEPER_4        ← the {tripId} hashtag forces one slot
Type:  Hash
Field: berthId                                     ← "05-12" = carriage 5, bunk 12
Value: "occ,held,level,carriage,berthNo"           ← "3,0,1,5,12"

Key:   hold:{SE1-2026-02-14}:{holdId}              ← TTL 15 minutes
Value: "berthId,journeyMask,bookingId"

Key:   meta:{SE1-2026-02-14}
Value: JSON { legCount, stops[], classCodes[], saleStatus }
```

**The `{tripId}` hashtag is mandatory, not optional.** Redis Cluster assigns slots by
hashing the key, counting only what is inside `{}`. Without it, a trip's classes land in
different slots and **the Lua script cannot run** — Lua may only touch keys in one slot.

**Separate keys per class** (`SOFT_SLEEPER_4`, `SOFT_SEAT`, …) so the script scans only
genuinely eligible candidates instead of all 500 berths.

---

## 5. The hold Lua script

```lua
-- KEYS[1] = inv:{tripId}:{classCode}
-- KEYS[2] = hold:{tripId}:{holdId}
-- ARGV[1] = journeyMask       (integer)
-- ARGV[2] = strategy          BEST_FIT | FIRST_FIT | SPECIFIC
-- ARGV[3] = requestedBerthId  (SPECIFIC only)
-- ARGV[4] = holdId
-- ARGV[5] = ttlSeconds
-- ARGV[6] = scanOffset        (random — see §6)
-- ARGV[7] = bookingId

local jmask    = tonumber(ARGV[1])
local strategy = ARGV[2]

-- Idempotency: this holdId already exists ⇒ replay the previous result
if redis.call('EXISTS', KEYS[2]) == 1 then
  local prev = redis.call('GET', KEYS[2])
  return {1, prev, 'IDEMPOTENT_REPLAY'}
end

local inv = redis.call('HGETALL', KEYS[1])       -- ~4 KB, one read

-- Collect candidates
local berths = {}
for i = 1, #inv, 2 do
  local id = inv[i]
  local occ, held, level, carriage, no = inv[i+1]:match("(%d+),(%d+),(%d+),(%d+),(%d+)")
  berths[#berths+1] = {
    id = id, occ = tonumber(occ), held = tonumber(held),
    level = tonumber(level), carriage = tonumber(carriage), no = tonumber(no)
  }
end

-- Pick a berth
local chosen = nil

if strategy == 'SPECIFIC' then
  for _, b in ipairs(berths) do
    if b.id == ARGV[3] then
      if bit.band(bit.bor(b.occ, b.held), jmask) == 0 then chosen = b end
      break
    end
  end

else
  local n      = #berths
  local offset = tonumber(ARGV[6]) % n          -- ⭐ random starting point
  local best, bestScore = nil, -1

  for k = 0, n - 1 do
    local b = berths[((offset + k) % n) + 1]

    if bit.band(bit.bor(b.occ, b.held), jmask) == 0 then
      if strategy == 'FIRST_FIT' then
        chosen = b                               -- ⭐ peak load: take it, do not score
        break
      end
      local score = (4 - b.level) * 10           -- level 1 > 2 > 3
      if b.no > 4 and b.no < 30 then score = score + 3 end   -- avoid carriage ends
      if score > bestScore then best, bestScore = b, score end
    end
  end
  chosen = chosen or best
end

if chosen == nil then
  return {0, '', 'NO_BERTH_AVAILABLE'}
end

-- Set the bits — re-check defensively
if bit.band(bit.bor(chosen.occ, chosen.held), jmask) ~= 0 then
  return {0, '', 'RACE_DETECTED'}                -- impossible: Lua is single-threaded
end

local newHeld = bit.bor(chosen.held, jmask)
redis.call('HSET', KEYS[1], chosen.id,
  string.format("%d,%d,%d,%d,%d",
    chosen.occ, newHeld, chosen.level, chosen.carriage, chosen.no))

redis.call('SET', KEYS[2],
  string.format("%s,%d,%s", chosen.id, jmask, ARGV[7]),
  'EX', tonumber(ARGV[5]))

return {1, chosen.id, 'HELD'}
```

**Four details worth noting:**

| Detail | Why |
|---|---|
| `EXISTS KEYS[2]` at the top | Idempotency. Three taps produce one berth, not three |
| `RACE_DETECTED` can never happen | Redis Lua is single-threaded. Kept so a **test can assert the assumption** — if it ever fires, a foundational assumption is wrong |
| Random `scanOffset` | §6 — more important than it looks |
| `FIRST_FIT` breaks immediately | §6 |

---

## 6. Why "pick the best berth" breaks the system at peak

The most counter-intuitive lesson in the project.

```
BEST_FIT — 10,000 concurrent requests
  Every script scans from the start, scores identically,
  and concludes that "bunk 05-12, level 1" is best.
  → Request #1 gets it.
  → The other 9,999 finish their computation and find it gone.
  → 9,999 full scans wasted.
```

The paradox: a **smarter** berth-selection algorithm makes the system **slower**, because it
makes everyone contend for the same resource.

```
FIRST_FIT + random scanOffset
  Each request starts scanning somewhere different.
  Request #1 starts at berth 37  → takes berth 37.
  Request #2 starts at berth 412 → takes berth 412.
  → Collisions approach zero. Each script stops after ~1 attempt.
```

Two small changes — drop the scoring, start at random — turn average complexity from
$O(n)$ wasted scanning into $O(1)$ immediate hits.

**Strategy by load:**

| Occupancy | Mode | Reason |
|---|---|---|
| < 60% | `SPECIFIC` (passenger picks on the map) | Good experience, low contention |
| 60–85% | `BEST_FIT` | Enough slack left to optimise |
| **> 85%, or rejections > 30%** | **`FIRST_FIT` + random offset** | **Surviving matters more than optimising** |

Switch automatically on metrics, and **tell the passenger**:
*"Peak demand — we will pick the best available berth for you."*

---

## 7. Counting availability — the subtle part

A question that sounds simple: *"How many berths are left HN→SG?"*

**Per-leg counters give the WRONG answer.** With a capacity of 100:

```
Bunk #1: occupied on leg L0 only
Bunk #2: occupied on leg L4 only
All other bunks: entirely free

Per-leg counters:  L0 has 99, L4 has 99, every other leg has 100
min(per-leg) = 99   ← WRONG

Reality for HN→SG (which needs EVERY leg):
  Bunk #1 unusable (blocked on L0)
  Bunk #2 unusable (blocked on L4)
  = 98 berths        ← RIGHT
```

Per-leg counters only give an **upper bound**. Correctness requires a real count:

$$
\text{available}(J) = \bigl|\{\, b : (\text{occ}_b \mid \text{held}_b) \,\&\, J = 0 \,\}\bigr|
$$

For 500 berths that is a ~200 µs Lua loop. Cheap. Called 10,000 times a second, not cheap.

**A three-tier answer:**

| Tier | Used for | Accuracy | Cost |
|---|---|---|---|
| Redis cache, 3s TTL | Trip list display | Up to 3s stale | ~0 |
| Real Lua scan | Passenger opens one trip's berth map | Accurate at that moment | 200 µs |
| **The hold script** | **Pressing "Hold"** | **The only truth** | 80 µs |

> **Product rule:** the displayed number is a **suggestion**, not a promise, and the UI must
> say so. Every ticketing system has the "someone just took it" moment — the difference is
> whether you *design* for it or *pretend* it does not exist.

---

## 8. Group booking — a family in one compartment

Requirement R7. A family of four wants a whole 4-berth compartment.

The constraint: $k$ berths **in the same compartment**, all free for the same `journeyMask`.

```lua
-- Scan by compartment rather than by berth.
-- A compartment is a group of 4 or 6 consecutive bunks in one carriage.
for _, compartment in ipairs(compartments) do
  local free = {}
  for _, b in ipairs(compartment.berths) do
    if bit.band(bit.bor(b.occ, b.held), jmask) == 0 then
      free[#free+1] = b
    end
  end
  if #free >= groupSize then
    -- take the first groupSize berths, set bits for all of them in the SAME script
    return reserve_all(free, groupSize, jmask)
  end
end
```

**Fallback ladder when there is not enough room:**

```
1. Whole compartment, same level         ← ideal
2. Same compartment, different levels
3. Same carriage, adjacent compartments
4. Same carriage
5. Anywhere — say clearly "we cannot seat you together, continue?"
```

> **Do not use an optimisation solver here.** Greedy first-fit down that ladder is enough
> and runs inside the same Lua script. Adding OR-Tools for "optimal allocation" is
> over-engineering: it breaks atomicity, puts a network call on the hottest path, and
> passengers cannot tell "optimal" from "good enough". This is the easiest place in the
> whole system to over-engineer.

---

## 9. Exchanges — the most dangerous operation

Exchanging trip A for trip B means **releasing the old mask and taking a new one**. If it
fails halfway, the passenger loses both.

**Same trip** (changing bunks) — one Lua script, naturally atomic:

```
old.occupied &= ~oldMask
new.held     |= newMask        ← same script, cannot end up half-done
```

**Different trips** — two different Redis shards, **atomicity is impossible**. A saga is
required:

```
1. Hold the NEW berth on trip B          (old ticket untouched)
   ✗ fails ⇒ stop; the old ticket is intact, the passenger loses nothing
2. Write PostgreSQL: old ticket EXCHANGING, new ticket PENDING   (one transaction)
3. Confirm the new berth (held → occupied)
4. Release the old berth
   ✗ fails ⇒ reconciliation picks it up later; the passenger ALREADY has the new ticket
```

**Step 1 before step 4 is mandatory.** Releasing the old berth first and then failing to
hold the new one leaves the passenger with nothing — and the old berth may be gone within
milliseconds.

The general rule: **always take the new one before releasing the old.** The worst case is
holding two berths for a few seconds, which is acceptable. The worst case of the reverse
order is a passenger with no berth at all.

---

## 10. The critical invariant: station indexes are frozen

```
journeyMask is computed from a station's INDEX in the stop list.
Insert or remove a stop ⇒ EVERY sold mask becomes meaningless.
```

Example: a trip is on sale and operations insert Tam Ky between Da Nang(3) and Nha Trang(4).
Every later station shifts by +1. A HN→SG ticket with mask `0b11111` now points at the wrong
legs. **The entire inventory is silently corrupt** — no error is ever thrown.

**The rule:**

| Trip status | May the stop list change? |
|---|---|
| `SCHEDULED` (not yet on sale) | ✅ Freely |
| `SELLING` · `CLOSED` · `DEPARTED` | ❌ **Absolutely not** |

Rescheduling after the sale opens means **creating a new trip**, migrating tickets, and
notifying passengers. Painful, but safe.

Enforced at the data layer with a `BEFORE INSERT/UPDATE/DELETE` trigger on `trip_stop` —
a `CHECK` constraint cannot query another table.

---

## 11. Generating the data — 7 hours, nothing curated

```
20 stations on the North–South line  →  typed by hand, 15 minutes
   HN · Phu Ly · Nam Dinh · Ninh Binh · Thanh Hoa · Vinh · Dong Hoi
   Dong Ha · Hue · Da Nang · Tam Ky · Quang Ngai · Dieu Tri · Tuy Hoa
   Nha Trang · Thap Cham · Bien Hoa · Sai Gon

Train composition                    →  a loop
   Carriages 1–2:  air-conditioned soft seats   64 seats
   Carriages 3–6:  6-berth compartments         42 bunks (7 × 6, levels 1/2/3)
   Carriages 7–10: 4-berth compartments         28 bunks (7 × 4, levels 1/2)
   Carriage 11: dining · Carriage 12: baggage
   → 128 + 168 + 112 = 408 berths per trip

Trips                                →  generator
   5 services (SE1/3/5/7, TN1) × 2 directions × 20 Tết days = 200 trips

Fares                                →  formula
   base × distance_factor × class_factor × level_factor × peak_factor
```

**A real asymmetry worth simulating:** before Tết, **South → North** sells out (workers in
Saigon going home); after Tết it reverses. The load test should reflect that rather than
spreading evenly — otherwise you optimise for a load shape that does not exist.

---

## 12. How to prove you got it right

The greatest strength of this domain: **correctness is machine-checkable.**

```bash
# k6: 10,000 VUs contending for 408 berths on SE1-2026-02-14, then:
tools/verify
```

```sql
-- ⛔ INVARIANT 1: no overlapping legs on one berth
SELECT t1.berth_id, t1.ticket_id, t2.ticket_id
FROM ticket t1 JOIN ticket t2
  ON  t1.trip_id  = t2.trip_id
  AND t1.berth_id = t2.berth_id
  AND t1.ticket_id < t2.ticket_id
  AND (t1.journey_mask & t2.journey_mask) <> 0
WHERE t1.status = 'ISSUED' AND t2.status = 'ISSUED';
-- MUST return 0 rows

-- ⛔ INVARIANT 2: no leg exceeds capacity
SELECT leg_idx, count(*) AS sold, capacity
FROM ticket_leg JOIN trip_capacity USING (trip_id, leg_idx)
GROUP BY leg_idx, capacity
HAVING count(*) > capacity;
-- MUST return 0 rows

-- ⛔ INVARIANT 3: Redis matches PostgreSQL
-- (reconciliation runs and reports zero drift)

-- ⛔ INVARIANT 4: money matches tickets
SELECT b.booking_id FROM booking b
WHERE b.status = 'CONFIRMED'
  AND b.total_vnd <> (SELECT COALESCE(sum(fare_vnd),0) FROM ticket
                      WHERE booking_id = b.booking_id AND status <> 'REFUNDED');
-- MUST return 0 rows
```

These four run after **every** load test, in CI. One red query fails the build.

> **Correctness here is binary, not a matter of opinion.** "Did we oversell?" is answered in
> 30 seconds, automatically, on every commit — no real users, no waiting for feedback, no
> argument. That is why you can change the inventory architecture four times in
> [04 §12](04-contention-strategies.md) without fear: after each change, these four queries
> tell you immediately whether you are still right.

---

**Next:** [04 — Contention Strategies](04-contention-strategies.md)
