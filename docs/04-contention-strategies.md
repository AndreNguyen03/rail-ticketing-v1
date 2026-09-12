# 04 — Contention Strategies

> Four solutions to one problem. Each **fails differently**.
> You only understand that by building all four and watching them break.
>
> This is the most valuable exercise in the project.

---

## 1. Why adding servers makes it worse

Ordinary web intuition: slow ⇒ add pods. Under contention that intuition is **backwards**.

**The Universal Scalability Law:**

$$
C(N) = \frac{N}{1 + \alpha(N-1) + \beta N(N-1)}
$$

| Coefficient | Meaning | Here |
|---|---|---|
| $\alpha$ | **Serialisation** — work that cannot run in parallel | High: every request touches the same few rows |
| $\beta$ | **Crosstalk** — the cost of coordinating between threads | High: locks, retries, cache invalidation |

With $\beta > 0$, $C(N)$ **peaks and then declines**. Adding threads past the peak makes
throughput **fall**.

```
throughput
   │      ╭──╮
   │     ╱    ╲___          ← USL: peak, then decline
   │    ╱          ╲___
   │   ╱                ╲___
   │  ╱
   │ ╱     ┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈  ← linear (what you EXPECT to happen)
   │╱
   └──────────────────────────▶ concurrent threads
        ↑
     the peak — usually surprisingly low
```

**In practice:** 100 `inventory-service` pods contending for one row are slower than 10. You
will measure this yourself in §7 — and that is the moment the lesson sticks.

Only three things actually raise throughput:
1. **Reduce $\alpha$** — make the serial part shorter (80 µs of Lua instead of a 3 ms transaction)
2. **Reduce $\beta$** — remove coordination entirely (single-threaded ⇒ no locks)
3. **Partition** — turn one large contention domain into N independent ones ⭐

---

## 2. Approach 1 — `SELECT FOR UPDATE`

```sql
BEGIN;
SELECT berth_id, occupied_mask, held_mask
  FROM berth_inventory
 WHERE trip_id = ? AND class_code = ?
   AND (occupied_mask | held_mask) & ? = 0
 ORDER BY preference_score DESC
 LIMIT 1
   FOR UPDATE SKIP LOCKED;        -- ⭐ SKIP LOCKED is the crux

UPDATE berth_inventory SET held_mask = held_mask | ? WHERE berth_id = ?;
INSERT INTO berth_hold (...) VALUES (...);
COMMIT;
```

`SKIP LOCKED` saves this from disaster — without it every transaction queues behind the same
row and throughput collapses to ~1/lock_latency.

| | |
|---|---|
| ✅ | Absolutely correct; the database holds the invariant |
| ✅ | Durable immediately, no reconciliation needed |
| ✅ | Little code, easy to understand, easy to debug |
| ❌ | **~200–500 holds/second per trip** |
| ❌ | Holds a lock for the duration of a network round trip |
| ❌ | The connection pool drains under load ⇒ **the service hangs, not just slows** |
| ❌ | Without `SKIP LOCKED`: convoys and deadlocks |

**This is your correctness baseline.** Build it first. Every later approach must produce the
**same results** as this one.

---

## 3. Approach 2 — Optimistic locking with retry

```sql
UPDATE berth_inventory
   SET held_mask = held_mask | :jmask, version = version + 1
 WHERE berth_id = :berthId
   AND version  = :readVersion
   AND (occupied_mask | held_mask) & :jmask = 0;
-- 0 rows affected ⇒ someone got there first ⇒ retry
```

| | |
|---|---|
| ✅ | No locks held ⇒ reads scale well |
| ✅ | No deadlocks |
| ❌ | **Retry storm** — this is how it dies |

**The collapse mechanism:**

```
Low contention   (10 threads, 400 berths)
  → P(success on first try) ≈ 97%  → ~800 ops/second  ✅

High contention  (5,000 threads, 20 berths left)
  → P(success on first try) ≈ 0.4%
  → each request tries ~15 times
  → 5,000 × 15 = 75,000 failed writes
  → the database is busy doing NOTHING USEFUL
  → useful throughput COLLAPSES to ~50/second
```

Wasted work grows with the **square** of the number of contending threads. This is the most
insidious failure path: the system looks *busy* (100% CPU, high IOPS) while **achieving
almost nothing**.

> **If you use optimistic locking: a retry cap and jittered backoff are mandatory.**
> Unbounded retry is not fault tolerance — it is a self-inflicted denial of service.

---

## 4. Approach 3 — Redis + atomic Lua ⭐ (chosen)

Script detail in [03 §5](03-segment-inventory-engine.md).

**Why it is two orders of magnitude faster:**

| Factor | Detail |
|---|---|
| Serial section ~**80 µs** | vs ~3 ms for a PostgreSQL transaction. $\alpha$ is 37× smaller |
| **$\beta \approx 0$** | Redis is single-threaded ⇒ no locks, no coordination, no crosstalk |
| Whole state fits in **4 KB** | One read, one 8-byte write |
| No round trip between read and write | The decision happens **inside** Redis |

| | |
|---|---|
| ✅ | **~15,000–25,000 ops/second per shard** |
| ✅ | Naturally atomic, no distributed lock needed |
| ✅ | Idempotency is cheap (an `EXISTS` check at the top) |
| ❌ | **Not durable** — AOF `everysec` loses up to one second |
| ❌ | Needs PostgreSQL as source of truth plus reconciliation |
| ❌ | Business logic lives in Lua: hard to test, hard to debug, untyped |
| ❌ | A long script stalls Redis (single-threaded cuts both ways) |

> **Keep Lua scripts under ~100 lines with no unbounded loops.** Redis is single-threaded:
> a script that runs for 50 ms makes **every** other client wait 50 ms.

---

## 5. Approach 4 — Single writer via Kafka (event sourcing)

The "pure" option — and stronger than it looks.

```
Every hold command for trip X
        ↓ partition key = tripId
   Kafka partition (ordering guaranteed)
        ↓
   ONE consumer owns trip X
        ↓ state in RAM — NO contention, only one thread touches it
   Write the result to a reply topic
```

| | |
|---|---|
| ✅ | **No contention by construction** — one writer |
| ✅ | **The Kafka log IS the durability** — replay to rebuild state |
| ✅ | Perfect audit: every decision is in an immutable log |
| ✅ | Logic is plain Java: testable, typed — **not Lua** |
| ❌ | **Asynchronous** — a passenger is waiting, so you must bridge request/response over topics |
| ❌ | Higher p99 (~50–200 ms) from batching |
| ❌ | A rebalance stops serving that trip for seconds |
| ❌ | In-RAM state needs snapshots, or a restart costs minutes of replay |

**This is the right choice when correctness and auditability outrank latency.** For train
tickets a passenger is staring at a screen, so latency matters and we choose Redis. For batch
reservations or an internal system, this approach wins.

---

## 6. Comparison

| | FOR UPDATE | Optimistic | **Redis Lua** | Kafka single-writer |
|---|---|---|---|---|
| Throughput/trip | 200–500/s | 800/s → **collapse** | **15–25k/s** | 8–15k/s |
| p99 latency | 15–200 ms | 10 ms → **∞** | **2–5 ms** | 50–200 ms |
| Durability | ✅ immediate | ✅ immediate | ❌ loses ≤1s | ✅ the log |
| Atomicity | ✅ | ✅ | ✅ | ✅ |
| Needs reconciliation | ❌ | ❌ | ✅ | ❌ |
| Testable | ✅ | ✅ | ❌ Lua | ✅ |
| Type safe | ✅ | ✅ | ❌ | ✅ |
| How it breaks | Pool drained, hangs | **Retry storm** | Lost holds | Rebalance, latency |
| Operational complexity | Low | Low | **High** | High |

> These numbers are **estimates to compare your own measurements against**, not measured
> results. The most valuable part of the project is running §7 yourself and getting your own
> numbers on your own hardware.

---

## 7. How to measure — the core exercise

```javascript
// loadtest/hold-contention.js  — k6
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    // Sale opening: 0 → 10,000 VUs in 10 seconds, hold for 60
    tet_opening: {
      executor: 'ramping-arrival-rate',
      startRate: 0, timeUnit: '1s',
      preAllocatedVUs: 10000,
      stages: [
        { target: 5000,  duration: '10s' },
        { target: 20000, duration: '30s' },   // far past capacity — that is the point
        { target: 0,     duration: '20s' },
      ],
    },
  },
};

const TRIP = 'SE1-2026-02-14';        // ONE trip — maximum contention

export default function () {
  const res = http.post(`${__ENV.API}/api/v1/holds`, JSON.stringify({
    tripId: TRIP,
    fromStopIdx: 0, toStopIdx: 17,     // HN → SG, occupies all 17 legs
    classCode: 'SOFT_SLEEPER_4',
    strategy: __ENV.STRATEGY,          // SPECIFIC | BEST_FIT | FIRST_FIT
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `${__VU}-${__ITER}`,
      'X-Identity': `ID-${__VU}`,      // one identity per VU ⇒ quota does not interfere
    },
  });

  check(res, {
    'no 5xx': (r) => r.status < 500,
    'definite answer': (r) => r.status === 201 || r.status === 409,
  });
}
```

### Experiment matrix

| Experiment | Variable | What you will see |
|---|---|---|
| **A** | Four implementations, same load | Two orders of magnitude in throughput |
| **B** | 1 → 200 `inventory-service` pods | **The USL curve** — peak then decline. The most expensive lesson |
| **C** | `BEST_FIT` vs `FIRST_FIT` + random offset | [03 §6](03-segment-inventory-engine.md) — the "smart" algorithm is slower |
| **D** | 1 trip vs 200 trips, same total load | **Partitioning works**: same RPS, zero contention |
| **E** | Waiting room on/off | Product backpressure vs engineering backpressure |
| **F** | Kill Redis mid-test | How many holds are lost, how long reconciliation takes to repair |

**After every run, without exception:**

```bash
tools/verify   # the four invariant queries from 03 §12 — one red is a failure
```

An implementation that is 100× faster but oversells 3 tickets is **wrong**, not "faster".

---

## 8. The durability problem — and why we accept it

Redis is not as durable as PostgreSQL. AOF configuration:

| `appendfsync` | Max loss | Throughput |
|---|---|---|
| `always` | ~0 | **10–20× slower** — the whole advantage is gone |
| **`everysec`** ⭐ | **1 second** | Unchanged |
| `no` | Up to 30 seconds | Fastest |

Choose `everysec` and **design so that losing one second does not matter**:

| State | Losable? | Why | Stored in |
|---|---|---|---|
| Unpaid hold | ✅ | The passenger retries, losing 5 seconds | Redis (TTL) |
| Order awaiting payment | ⚠️ | Written to PostgreSQL asynchronously | Redis + PG async |
| **Paid ticket** | ❌ **Never** | Money has changed hands | **PostgreSQL, synchronously** |

### The confirmation flow — the order is mandatory

```
1. Payment succeeds (VNPay webhook)
        ↓
2. ⭐ WRITE POSTGRESQL SYNCHRONOUSLY
   INSERT ticket ... with the EXCLUDE constraint against leg overlap
   ├─ SUCCESS ⇒ continue
   └─ CONSTRAINT VIOLATION ⇒ Redis lost state and someone else took the berth
        ⇒ REFUND IMMEDIATELY + apologise + suggest alternatives
        ⇒ raise an operational alert — this is an incident, not routine
        ↓
3. Update Redis: held → occupied
        ↓
4. Confirm to the passenger
```

**Step 2 before step 4.** Never say "booking confirmed" before PostgreSQL has durably
written it.

Step 2 failing is **rare but real**. A system without that branch is a system that will one
day put two passengers in one bunk.

---

## 9. Hold expiry — the leak trap

A hold has a TTL. But **the `held` bit in the inventory hash does not expire.**

```
hold:{trip}:{holdId}  ──TTL 15 min──▶ deleted  ✅
inv:{trip}:{class}    field berth 05-12  held=0b11111  ──▶ ???

Nothing clears this bit ⇒ the bunk is permanently unsellable.
No error is ever thrown. A silent leak.
```

One trip leaking 30 berths is 30 tickets of lost revenue, and **no ordinary monitoring
catches it** — every service reports healthy.

### Three layers of defence

**Layer 1 — Lazy expiry inside the scan script** ⭐ *self-healing*

Store a `heldUntil` alongside each berth's value. The scan script treats an expired hold as
**free** and cleans it up in passing:

```lua
local now = tonumber(ARGV[8])
if b.heldUntil > 0 and b.heldUntil < now then
  b.held      = 0         -- treat as free
  b.heldUntil = 0
  dirty[#dirty+1] = b     -- written back at the end of the script
end
```

The big advantage: **it depends on no background process.** A leak heals itself on the next
scan.

**Layer 2 — Keyspace notifications**

Redis emits an event when a key expires ⇒ a consumer clears the bit. Fast, but
**fire-and-forget**: if the consumer is restarting, the event is gone. Never rely on it
alone.

**Layer 3 — Sweep job plus reconciliation**

Every 60 seconds, reconciliation compares Redis with PostgreSQL. A `held` bit with no
corresponding hold is cleared and audited.

> Three layers sounds redundant. It is not: layer 1 handles 99%, layer 2 cuts latency, layer
> 3 is the final net for what both miss. Inventory leaks are a **silent** class of bug —
> discovered only as revenue that is short for no visible reason.

---

## 10. Reconciliation — the referee between two datastores

```
Every 60 seconds, for each trip on sale:

  1. Read every bitmask from Redis
  2. Rebuild the expected masks from PostgreSQL:
       expected_occupied = OR(journey_mask) over ISSUED tickets
       expected_held     = OR(journey_mask) over unexpired holds
  3. Compare berth by berth
  4. On a mismatch, POSTGRESQL WINS — repair Redis
  5. Write an immutable audit record and alert
```

| Drift type | Cause | Action | Severity |
|---|---|---|---|
| Redis has `held`, PG has no hold | A leak (§9) | Clear the bit | 🟡 Info |
| PG has a ticket, Redis lacks `occupied` | **Redis lost data** | Set the bit **immediately** | 🔴 Critical |
| Redis has `occupied`, PG has no ticket | Phantom Redis state / missed write | Investigate; default to clearing | 🔴 Critical |
| Both agree | Normal | — | — |

**The drift rate is the single most important health metric.** Alert thresholds:

```
any 🔴 drift within 5 minutes             ⇒ Critical, page someone
🟡 drift > 0.1% of berths                 ⇒ Warning
reconciliation fails 3 cycles in a row    ⇒ Critical
```

> Reconciliation is **not a nice-to-have**. It is the price of choosing Redis — and it must
> be written **before** Redis goes to production, not after.

---

## 11. The final architecture

```
                    ┌──── waiting-room (Go) ────┐
  500k people ──────▶  absorb surge, 2k/second  │
                    └────────────┬──────────────┘
                                 ▼
                    ┌─── booking-service ───┐
                    │   saga orchestrator   │
                    └───────────┬───────────┘
                                ▼
                    ┌── inventory-service ──┐
                    │  partitioned by trip  │
                    └───────────┬───────────┘
                     ┌──────────┴──────────┐
                     ▼                     ▼
            ┌─────────────────┐   ┌──────────────────┐
            │ REDIS (hot)     │   │ POSTGRESQL       │
            │ atomic Lua      │   │ EXCLUDE gist     │
            │ 15–25k ops/s    │   │ source of truth  │
            │ AOF everysec    │   │ 200–500 ops/s    │
            └────────┬────────┘   └────────┬─────────┘
                     └──────────┬──────────┘
                                ▼
                    ┌─── reconciliation ────┐
                    │  every 60s · PG wins  │
                    │  every repair audited │
                    └───────────────────────┘
```

| Decision | Reason |
|---|---|
| Redis on the hot path | Contention needs small $\alpha$ and $\beta = 0$ |
| PostgreSQL as truth | Money and tickets must be durable, behind a constraint that cannot be bypassed |
| `EXCLUDE USING gist` in PG | The final net: even if Redis is wrong, the database refuses an overlapping write |
| Mandatory reconciliation | The price of a two-datastore architecture |
| Partition by `tripId` | The only thing that genuinely scales a contention problem |
| Virtual waiting room | Spreading demand over time beats every engineering optimisation |

---

## 12. Build order — from correct to fast

| Step | Do | Learn |
|---|---|---|
| 1 | **`SELECT FOR UPDATE`**, one trip, no cache | The correctness baseline. Everything later must match it |
| 2 | Write the four invariant queries + `tools/verify` | A safety net before optimising |
| 3 | Load test, measure throughput | Baseline numbers |
| 4 | Add pods → **see the USL** | The most expensive lesson, and counter-intuitive |
| 5 | Try optimistic → **see the retry storm** | Why retry is not a solution |
| 6 | Move to Redis Lua | ~50× faster |
| 7 | Kill Redis mid-test → **lose holds** | Why reconciliation is needed |
| 8 | Write reconciliation | The durability trade |
| 9 | Partition across 200 trips | **Partitioning beats replication** |
| 10 | Add the waiting room | Backpressure at the product layer |

**Do not jump straight to step 6.** The learning is in steps 4, 5 and 7 — the times you
*watch* it break. Reading about retry storms is nothing like watching throughput collapse
while CPU sits at 100%.

---

**Back to:** [README](../README.md) · [03 — Segment Inventory Engine](03-segment-inventory-engine.md)
