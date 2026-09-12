# Rail Ticketing — Tết train booking system

> 500,000 people press "Book" in the same second. 500 berths on SE1 on the 26th day of the lunar year.
> Sell exactly 500 tickets. Not one more. Nobody charged twice. No bot takes the lot.
>
> A microservices system built to learn **distributed contention** — the problem that
> adding servers does not solve.

Stack: **Java 21 / Spring Boot 4.1** · **Redis** (hot inventory path) · **PostgreSQL** (source of truth) ·
**Kafka** · **React** · **k3d** (local, $0)

---

## 1. The architectural claim

Most ticketing systems are designed for **scale**. This problem is not scale — it is
**contention**. They are different in kind and pull architecture in opposite directions:

| | Scale | Contention |
|---|---|---|
| Load shape | 500k req/s across 500k different items | 500k req/s for **the same 500 berths** |
| Solved by | Sharding, replicas, cache | **No number of servers helps** |
| Bottleneck | Bandwidth, CPU | **One row of data** |
| Failure mode | Slow | **Overselling — a business error** |

Adding 100 pods to the booking service **makes it worse**: 100 processes fight over one
database row, locks queue, throughput drops. That paradox is unique to contention, and it
is why this domain teaches what CRUD cannot.

### Four properties that shape the whole design

```
1. INVENTORY IS AN INTERVAL, NOT A BOOLEAN
   Berth 12A on SE1:
   HN ──── Vinh ──── Hue ──── DN ──── NT ──── SG
   │                  │       │               │
   └─ passenger A ────┘       └─ passenger B ─┘
        (no conflict — one berth, two passengers)

2. LOAD IS ABSURDLY UNEVEN
   360 days a year:    ~50 req/s
   Tết sale opening:   ~500,000 req in 60 seconds
   Ratio 10,000:1

3. THE ADVERSARY IS REAL
   Scalper bots rotate IPs, rotate accounts, call the API directly.
   Defending with infrastructure (IP rate limits) loses. Defend by IDENTITY.

4. CORRECTNESS IS BINARY AND PROVABLE
   SELECT count(*) FROM ticket WHERE train='SE1' AND date='2026-02-14';
   Exactly 500. Or you are wrong.
```

### Consequence: split services by **load shape**

```
QUEUE PLANE      waiting-room · gateway              ← absorb the surge, release evenly
                          │
CATALOG PLANE    schedule · fare · station           ← 10,000:1 reads, cacheable, cold data
                          │
CONTENTION PLANE inventory · booking · quota         ← ⭐ HIGH CONTENTION, strong consistency
                          │                             partitioned BY TRIP
FULFILLMENT      payment · ticket · notification     ← async, tolerates delay
                          │
RECOVERY         refund · reconciliation             ← background, repairs drift
```

`inventory-service` is **the heart and the bottleneck**. Every other architectural
decision exists to protect it.

---

## 2. The core problem: segment inventory

A trip has stations $S_0, S_1, \dots, S_n$ and $n$ **legs**.
A passenger travelling $S_a \to S_b$ occupies legs $L_a, L_{a+1}, \dots, L_{b-1}$.

Represent that as a **bitmask**:

```
SE1:  HN(0) ── Vinh(1) ── Hue(2) ── DN(3) ── NT(4) ── SG(5)
Legs:      L0        L1       L2       L3       L4        → 5 bits

Passenger A: HN → Hue   = L0,L1        = 0b00011
Passenger B: DN → SG    = L3,L4        = 0b11000
Passenger C: Vinh → NT  = L1,L2,L3     = 0b01110

A & B = 0  ⇒ NO conflict, they can share a berth
A & C ≠ 0  ⇒ conflict
```

**A conflict check is one AND. A reservation is one OR.** Both are single CPU instructions.

### The observation that decides the architecture

Vietnam's longest line (Hanoi–Saigon) has ~30 stations ⇒ **29 legs ⇒ fits in one `int32`**.

A trip has ~500 berths. Its entire inventory:

$$
500 \text{ berths} \times (4 + 4) \text{ bytes} = \mathbf{4\ KB}
$$

Each berth needs **two** masks — `occupied` (sold) and `held` (temporarily reserved).

**A whole trip's inventory fits in 4 KB.** It fits in one Redis write, one atomic Lua
script, one CPU cache line. That detail is what makes this extreme contention problem
solvable — and you would never see it if you modelled inventory as one SQL row per seat.

Full detail: [03 — Segment Inventory Engine](docs/03-segment-inventory-engine.md).

---

## 3. Four ways to solve contention — you will build all four

This is the most valuable exercise in the project. Each approach **fails differently**,
and you only understand that by watching it fail.

| Approach | Throughput (1 trip) | How it breaks |
|---|---|---|
| `SELECT FOR UPDATE` on PostgreSQL | ~200/s | Locks queue, connection pool drains, the service hangs |
| Optimistic lock + retry | ~800/s | **Retry storm** — more contention means more retries, throughput collapses |
| **Redis + atomic Lua** | **~25,000/s** | Fast, but **loses durability** — Redis dies, holds are gone |
| Single-writer per trip (Kafka partition) | ~8,000/s | Correct and ordered, but high p99 latency |

The final design uses **Redis Lua + PostgreSQL as source of truth + background
reconciliation**. Detail and measurement: [04 — Contention Strategies](docs/04-contention-strategies.md).

---

## 4. Service map

| Service | Plane | Language | Datastore | Why it is separate |
|---|---|---|---|---|
| `api-gateway` | Queue | Java | — | Auth, rate limiting by identity |
| **`waiting-room`** | Queue | **Go** | Redis | Holds 500k waiting connections — Java needs 6× the RAM |
| `schedule-service` | Catalog | Java | PostgreSQL + Redis | 10,000:1 reads, 24h cache |
| `fare-service` | Catalog | Java | PostgreSQL | Pricing rules, precomputed |
| **`inventory-service`** | Contention | **Java** | **Redis + PostgreSQL** | ⭐ The heart. Partitioned by trip |
| `booking-service` | Contention | Java | PostgreSQL | Saga orchestrator |
| `quota-service` | Contention | Java | Redis + PostgreSQL | Anti-scalping by national ID |
| `payment-service` | Fulfillment | Java | PostgreSQL | VNPay/MoMo, asynchronous |
| `ticket-service` | Fulfillment | Java | PostgreSQL + S3 | QR codes, PDFs |
| `notification-service` | Fulfillment | Java | PostgreSQL | SMS/Zalo/email |
| `refund-service` | Recovery | Java | PostgreSQL | Reverse saga |
| **`reconciliation`** | Recovery | Java | PostgreSQL | ⭐ Redis ↔ PostgreSQL drift repair |

Twelve services, and **every one of them orbits a single hard problem**: keeping inventory
correct under contention. `inventory-service` is the centre; the other eleven exist to
serve it, protect it, or repair it.

---

## 5. Data — generated, not curated

Every piece of seed data is either **generated by code** or **produced by using the
system**. Nothing has to be collected, cross-checked, or verified against the real world:

| Data | How | Effort |
|---|---|---|
| ~20 stations on the North–South line | Public list, typed by hand | **15 min** |
| Carriage layouts (soft seat 64, 6-berth × 42, 4-berth × 28) | **A loop** | **1 hour** |
| ~200 trips × 20 Tết days | Generator | **1 hour** |
| Prices by class and bunk level | Formula | **30 min** |
| Passengers, orders, payments | **Produced by using the system** | **0** |
| 500k-user load | **Load generator (k6)** — and it is the fun part | 4 hours |
| | **Total** | **~7 hours** |

**Seven hours, and you write code from day one.** That was a deliberate criterion for
choosing this domain: the lesson lives in *system behaviour under load*, not in data
entry — so the data has to be cheap.

---

## 6. Documents

| # | Document | Contents |
|---|---|---|
| 01 | [Domain Model](docs/01-domain-model.md) | Event storming, bounded contexts, ubiquitous language |
| 02 | [Architecture](docs/02-architecture.md) | Five planes, C4, technology choices |
| **03** | [**Segment Inventory Engine**](docs/03-segment-inventory-engine.md) | ⭐ Bitmasks, berth selection, the 4 KB insight |
| **04** | [**Contention Strategies**](docs/04-contention-strategies.md) | ⭐ Four approaches, benchmarks, how each one breaks |
| **05** | [**Build Progression**](docs/05-build-progression.md) | ⭐ **Start here** — ordinary API first, infrastructure added against measurements |

Decisions taken while building are recorded in [docs/adr/](docs/adr/). Commands are in
[docs/COMMANDS.md](docs/COMMANDS.md).

> **First read?** The architecture in [02](docs/02-architecture.md) is the **destination**,
> not the starting point. Read [**05 — Build Progression**](docs/05-build-progression.md)
> for what to build first, what comes later, and **what must be measured before each piece
> of infrastructure is allowed in**.

---

## 7. What you learn

| Topic | By doing |
|---|---|
| **Distributed contention** | Building all four solutions and watching each one break ([04](docs/04-contention-strategies.md)) |
| **Universal Scalability Law** | Going from 1 to 200 pods and watching throughput **fall** |
| **Partitioning beats replication** | Same RPS, 200 trips vs 1 trip — two orders of magnitude apart |
| **Atomicity without locks** | Single-threaded Redis Lua instead of a distributed lock |
| **The durability trade** | Redis is fast and loses data; design so that losing one second does not matter |
| **Reconciling two datastores** | The unavoidable price of a writable cache |
| **Sagas and compensation** | Hold → pay → issue, and every failure branch |
| **Distributed timers** | 100,000 holds expiring, and the silent leak ([04 §9](docs/04-contention-strategies.md)) |
| **Backpressure at the product layer** | A virtual waiting room beats every circuit breaker |
| **Designing against an adversary** | Scalpers are an actor in the context diagram, not a security footnote |
| **Machine-checked invariants** | Four SQL queries after every load test in CI |

The three most counter-intuitive lessons, and therefore the most valuable:

1. **Adding servers makes it slower** when the bottleneck is contention, not resources
2. **A smarter berth-selection algorithm makes the system slower** ([03 §6](docs/03-segment-inventory-engine.md))
3. **Reducing contention by product design** beats every lock optimisation

---

## 8. Running it

```bash
docker compose up -d postgres                    # database only — the normal dev loop
docker compose --profile services up -d --build  # everything in containers
```

Full command list, including the contract lint and the per-service ports, is in
[docs/COMMANDS.md](docs/COMMANDS.md).

Once stage 1 exists, `tools/verify` runs the four invariant queries
([03 §12](docs/03-segment-inventory-engine.md)) after every load test, and a red one fails
the build — **an implementation that is 100× faster but sells 3 extra tickets is wrong,
not "faster"**.
