# 05 — Build Progression

> The architecture in [02](02-architecture.md) is the **destination**, not the starting point.
>
> But the starting point is **still microservices** — several services, deployed separately,
> with separate databases, calling each other over the network. What is deferred is the
> **technique inside and between** them, not the topology itself.

---

## 1. The governing principle

> ### Full topology from the first commit. Technique added against measurements.

Two halves, do not confuse them:

| Present from commit one | Deferred until measured |
|---|---|
| Several services, deployed independently | Redis, caching |
| A database per service | Kafka, events, sagas |
| Real HTTP calls between them | Circuit breakers, retry, bulkheads |
| API gateway | Virtual waiting room |
| Inventory bitmask | Reconciliation |
| Idempotency keys | Service mesh, Kubernetes |

The reason for that split: **the goal is to learn distributed systems.** Start with a
monolith and you spend six weeks learning Spring Boot — which you already know. Start with
several services and you hit failing network calls, no transaction across services, and
logs in three places on day one. **That is the part worth learning.**

But do not add Redis, Kafka and a mesh at once, or you will not know which problem came
from where.

### What never changes: the public API

```
Client  ──────────────────────────────────────────────▶  gateway
                                                            │
   Stage 0:  3 services · synchronous REST · PostgreSQL     │
   Stage 3:  3 services · + timeouts, retry, idempotency    │  CONTRACT
   Stage 4:  3 services · + Redis inside inventory          │  UNCHANGED
   Stage 5:  5 services · + Kafka, outbox, saga             │
   Stage 7:  12 services · + waiting room, Kubernetes       │
```

The client neither knows nor needs to know what changed inside. That is why the **API is
designed first**.

---

## 2. Stage 0 — Microservices skeleton, plain CRUD

**Goal: book one ticket through three real services, with no technique at all.**

### Three services plus a gateway

```
                    ┌──────────────┐
   Client ─────────▶│ api-gateway  │   routing, nothing else
                    └──────┬───────┘
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐
  │  schedule   │  │  inventory   │  │   booking   │
  │   service   │  │   service    │◀─│   service   │
  └──────┬──────┘  └──────┬───────┘  └──────┬──────┘
         │                │                 │
     scheduledb      inventorydb        bookingdb   ← THREE SEPARATE DATABASES
```

| Service | Owns | Endpoints |
|---|---|---|
| `schedule-service` | Stations, trains, trips, carriages, berths, prices | `GET /trips`, `GET /trips/{id}` |
| `inventory-service` | Segment inventory, holds | `GET /availability`, `POST /holds`, `DELETE /holds/{id}` |
| `booking-service` | Orders, tickets, payment (mock) | `POST /bookings`, `POST /bookings/{id}/confirm`, `GET /bookings/{id}` |
| `api-gateway` | Routing | — |

Why exactly these three: they are **three different planes** from
[02 §3](02-architecture.md) — catalog (read-heavy, cold), contention, and orchestration.
Those boundaries follow load shape, and load shape does not change — so they are the three
lowest-risk boundaries available.

### API surface

| Method | Path | Service |
|---|---|---|
| `GET` | `/api/v1/trips?from=HN&to=SG&date=2026-02-14` | schedule |
| `GET` | `/api/v1/trips/{tripId}` | schedule |
| `GET` | `/api/v1/trips/{tripId}/availability?from=0&to=17` | inventory |
| `POST` | `/api/v1/holds` | inventory |
| `DELETE` | `/api/v1/holds/{holdId}` | inventory |
| `POST` | `/api/v1/bookings` | booking |
| `POST` | `/api/v1/bookings/{id}/confirm` | booking |
| `GET` | `/api/v1/bookings/{id}` | booking |

Eight endpoints, three services. The machine-readable version is
[contracts/openapi.yaml](../contracts/openapi.yaml).

### Deliberately absent

```
✗ Redis                ✗ Circuit breakers      ✗ Virtual waiting room
✗ Kafka / events       ✗ Retry / backoff       ✗ Anti-scalping quotas
✗ Saga framework       ✗ Service discovery     ✗ Keycloak
✗ Caching of any kind  ✗ Kubernetes            ✗ Distributed tracing
```

Call other services with a bare `RestClient` and a URL from the environment; Compose
handles DNS. Payment is `if (random() < 0.9) success else fail` inside `booking-service`.
Authentication is an `X-Identity` header that nothing verifies.

### The subtle part: no cross-service transaction

This is what you hit **on day one** by choosing microservices first, and the main reason
to choose it.

```
booking-service receives POST /bookings
  1. call inventory  POST /holds          → OK, holdId returned
  2. write bookingdb INSERT booking       → FAILS (constraint, DB down, pod killed…)
  3. call inventory  DELETE /holds/{id}   → naive compensation
```

The obvious hole: **the pod dies between step 2 and step 3** ⇒ an orphaned hold, berths
locked for nobody.

**And at stage 0 that is acceptable — because holds have a TTL.**

> **The hold TTL is what makes naive compensation work at stage 0.**
> An orphaned hold dies on its own after 15 minutes. You lose at most 15 minutes on a few
> berths. No saga, no outbox, no Kafka.
>
> This is a clean example of **business design covering for absent infrastructure**. Note
> it, because at stage 5 you replace it with a real saga and will understand exactly what
> the saga buys: the loss window shrinks from 15 minutes to seconds, and "silent loss"
> becomes "a durable record".

### Technical decisions inside `inventory-service`

| Concern | Choice | Why |
|---|---|---|
| Inventory | `SELECT FOR UPDATE SKIP LOCKED` | Absolutely correct; PostgreSQL holds the invariant for you |
| Overlap prevention | `EXCLUDE USING gist` | Cannot be bypassed, even by buggy code |
| Hold expiry | `@Scheduled` sweep every 30s | Enough. Distributed timers are a later problem |
| Berth selection | Simple scan, first free berth | Priority algorithms are [03 §6](03-segment-inventory-engine.md)'s business |

### Four things to get right immediately — cheap now, very expensive later

| Item | Why it cannot wait |
|---|---|
| **Leg bitmask** | Changing the inventory model later means rewriting `inventory-service` ([03 §2](03-segment-inventory-engine.md)) |
| **Idempotency key** on every write POST | Retrofitting means touching every client and every handler. And you **will** need it at stage 3 when retry arrives |
| **API conventions** — units in field names, calendar date vs instant, RFC 9457 errors | Changing the contract once clients exist is painful |
| **Genuinely separate databases** — one DB, one user, no cross queries | This is the **real** microservices boundary. A shared database is a monolith in disguise |

> The last one is the most commonly cheated. "One Postgres for now, it's simpler" and one
> day you `JOIN` from `booking` into `inventory` and all independence is gone.
> Use **separate users with separate grants** — so that the database refuses the cheat.

### Done when

- [ ] Booking HN→SG works via `curl`, through all three services
- [ ] A double booking of the same berth on overlapping legs is rejected
- [ ] An expired hold returns its berths to inventory
- [ ] Stopping `inventory-service` makes `booking` return a clear error and **not hang forever**
- [ ] Three separate databases; a cross-database `JOIN` is refused on privileges
- [ ] `docker compose up` brings up four containers and three databases

**Duration: ~3 weeks.** One week more than a monolith — that is the tuition for starting
in the right topology.

---

## 3. The trade-off of choosing microservices first

Both sides, plainly:

| | Microservices first ⭐ | Monolith, split later |
|---|---|---|
| Time to first ticket | ~3 weeks | ~2 weeks |
| First distributed problem | **Day one** | Week eight |
| Service boundaries | Fixed early — **expensive if wrong** | Cheap to change (move a package) |
| Debugging | Hard from the start, 3 log streams | Easy at first |
| No shared transaction | **Faced immediately** — TTL covers it | `@Transactional` hides it |
| What the first 8 weeks teach | Distributed systems | Spring Boot (which you know) |
| Biggest risk | Wrong boundaries | Never actually splitting |

**Mitigating "wrong boundaries":** the three stage-0 services are chosen by **load shape**
([02 §3](02-architecture.md)), not by entity. Load shape is the most stable thing in this
system — catalog will always be read-heavy, inventory will always be the contention point.
Those three boundaries are almost certainly right.

Boundaries that **might** be wrong and that you will fix later: should `fare` split from
`schedule`? Should `quota` live inside `booking`? Leave them in the larger service at
stage 0 and split when you can measure the need.

---

## 4. Stage 1 — Safety net, before optimising anything

**Goal: an automatic red/green signal.**

| Component | Content |
|---|---|
| **Four invariant queries** | [03 §12](03-segment-inventory-engine.md) — run against `inventorydb` and `bookingdb` |
| **`tools/verify`** | Non-zero exit if any query returns a row |
| **k6 load test** | 1,000 VUs contending for 408 berths on one trip |
| **CI** | Load test and verify on every PR |

You are about to change `inventory-service` **four times**. Without this net, every change
is a prayer.

One note specific to a multi-service architecture: the invariant queries now span **two
databases** (`booking` holds tickets, `inventory` holds masks). You cannot `JOIN`, so the
verify script reads both and compares in memory. **That is your first lesson in distributed
consistency**, and it arrives in week four.

- [ ] Verify catches a **deliberate** break — remove `FOR UPDATE` and watch it go red
- [ ] CI blocks merge when verify is red

**Duration: ~5 days.**

---

## 5. Stage 2 — Measure, do not guess

**Goal: baseline numbers, and knowing where the bottleneck is.**

| # | Do | You will see |
|---|---|---|
| **A** | Raise VUs: 100 → 500 → 2,000 → 10,000 | Throughput hits a ceiling (expect 200–500/s per trip) |
| **B** | **Raise `inventory-service` instances: 1 → 2 → 4 → 8 → 16** | ⭐ **The USL curve** — a peak, then a **decline** |
| **C** | 1 trip vs 200 trips at the same total RPS | Contention disappears when spread out |
| **D** | Measure **network time** between services | What share of latency is a hop, not real work |

Experiment D only exists because you chose microservices first, and it teaches something
important: **each network hop is 1–3 ms you never get back.** That is the price of
independence, and knowing the number stops you splitting services carelessly later.

```
baseline.md
├── Peak throughput:      ??? holds/s/trip
├── Optimal instances:    ???  (surprisingly low)
├── p50 / p95 / p99:      ???
├── Where time goes:      ???% lock wait · ???% query · ???% network hop
└── Bottleneck:           ???
```

**This file is the licence for every later stage.**

**Duration: ~1 week** — mostly waiting for tests to run.

---

## 6. Stage 3 — Harden the calls you already have

**Entry condition: you have seen them fail at stage 2.**

At stage 0 you called other services with a bare `RestClient`. Now patch the **observed**
failure modes:

| Add | Fixes |
|---|---|
| **Timeouts decreasing inward** | gateway 8s > booking 5s > inventory 800ms > DB 200ms |
| **Retry with jitter, idempotent operations only** | Transient network errors. **Requires the idempotency key from stage 0** |
| **Circuit breaker** | A slow `inventory` must not drag `booking` down |
| **Bulkhead** | A separate pool for outbound calls |
| **Correlation ID across three services** | Now genuinely needed — logs live in three places |

This turns "three services calling each other" into "a fault-tolerant distributed system".
It is short but changes the character of the thing.

> Note the order: **retry comes after idempotency keys**. Retrying a non-idempotent
> operation is how you create duplicate tickets. That is why the key is on the stage-0
> "get right immediately" list.

- [ ] Stopping `inventory-service` opens the breaker after a few failures; `booking` fails fast
- [ ] Injecting 500 ms of latency trips the timeout instead of hanging
- [ ] One `correlation-id` is traceable across all three services' logs

**Duration: ~1 week.**

---

## 7. Stage 4 — Contention: Redis plus reconciliation

**Entry condition: `baseline.md` proves PostgreSQL is the bottleneck.**

| Add | Why |
|---|---|
| Redis + Lua script inside `inventory-service` | [04 §4](04-contention-strategies.md) |
| PostgreSQL stays the **source of truth** | The `EXCLUDE` constraint stays, it is not dropped |
| **Reconciliation** | ⚠️ **Not optional** |

The key lesson: **every solution creates a new class of problem.**

```
Add Redis  ─┬─▶ ~50× faster                              ✅
            ├─▶ two datastores ⇒ they WILL diverge      ❌ new
            ├─▶ Redis dies ⇒ holds are lost             ❌ new
            └─▶ held bits leak silently                 ❌ new
```

Reconciliation and lazy expiry ([04 §9](04-contention-strategies.md)) are written **in this
same stage**.

The good part: this change is **entirely inside `inventory-service`**. `booking-service`
knows nothing, the API does not move. That is the payoff for having split the services
correctly at stage 0.

```bash
docker kill redis && sleep 5 && docker start redis
```

- [ ] Throughput up ≥20× against baseline
- [ ] Verify still green
- [ ] Killing Redis mid-test loses **no paid ticket**
- [ ] Reconciliation repairs drift in under 2 minutes

**Duration: ~2 weeks.**

---

## 8. Stage 5 — Asynchrony and a real saga

**Entry condition: a synchronous call is making a user wait for nothing.**

Payment is the obvious candidate — a 3–30 second webhook wait. This is also when the
stage-0 **naive compensation** is replaced by a real saga.

| Add | Why only now |
|---|---|
| Kafka | Only now is there genuinely asynchronous work |
| **Outbox pattern** | Writing the DB and publishing an event must be atomic |
| **Saga + compensation** | Replaces stage 0's try/catch and REST call |
| `payment-service` split out | Waits on a third party, has its own lifecycle |
| `ticket-service`, `notification-service` | Event consumers, they split off naturally |

**What the saga buys you** — now you can answer precisely:

| | Stage 0 (naive compensation + TTL) | Stage 5 (saga) |
|---|---|---|
| Pod dies mid-flow | Orphaned hold for 15 minutes | Saga recovers in seconds |
| Is there a record? | No — silent loss | Yes, durable saga state |
| Multi-step compensation | Not possible | Possible, and ordered |
| Complexity | Very low | High |

```
Payment fails                    ⇒ release berths
Payment times out                ⇒ reconcile with the gateway, refund if charged
Ticket issuance fails after pay  ⇒ retry, do NOT release berths, alert
Hold expires during payment      ⇒ ⚠️ the hardest one — [04 §8](04-contention-strategies.md)
```

This stage teaches the most about microservices. Do not rush it.

**Duration: ~3 weeks.**

---

## 9. Stage 6+ — Against measured need

| Stage | Add | Entry condition |
|---|---|---|
| **6** | Virtual waiting room (`waiting-room`, Go) | Load test shows the front door collapsing at N req/s |
| **7** | `quota-service` anti-scalping | You can simulate scalper behaviour |
| **8** | Keycloak, real authentication | Preparing for real users |
| **9** | Split `fare`, `refund`, exchanges | Completing the lifecycle |
| **10** | Kubernetes | Compose starts to strain at ≥6 services |
| **11** | Full observability, chaos | Enough services that incidents are hard to trace |

> **Stopping anywhere is fine.** After stage 5 you have a real distributed system with
> sagas, events, solved contention, resilience and a verification net — further than most
> microservices learning projects ever get.

---

## 10. Summary

| Stage | Theme | Adds | Services | Duration |
|---|---|---|---|---|
| **0** | CRUD skeleton | Spring Boot × 3 + gateway + 3 PostgreSQL | 3+1 | 3 weeks |
| **1** | Safety net | 4 invariants + k6 + CI | 3+1 | 5 days |
| **2** | Measure | `baseline.md`, USL, hop cost | 3+1 | 1 week |
| **3** | Fault tolerance | timeouts, retry, breaker, correlation ID | 3+1 | 1 week |
| **4** | Contention | Redis Lua + reconciliation | 3+1 | 2 weeks |
| **5** | Asynchrony | Kafka + outbox + saga | 5+1 | 3 weeks |
| 6–11 | As needed | waiting room, quota, K8s, chaos | up to 12 | varies |

**Through stage 5: ~11 weeks**, and you have touched every core lesson.

---

## 11. Seven ways to ruin this plan

| Mistake | Result |
|---|---|
| Building all 12 services at stage 0 | Three months and no ticket booked. Three is enough |
| **One shared database "for now"** | A monolith in disguise. You learn **nothing** |
| Adding Redis before measuring | You cannot tell what it helped, and now you have two datastores to sync |
| Skipping stage 1 | Every later change is guesswork |
| Skipping experiment B (USL) | You miss the most expensive lesson and believe "more pods is faster" forever |
| Adding retry before idempotency keys | You manufacture duplicate tickets |
| Adding Kafka with no real async work | Complexity bought with nothing |

> The second is the most dangerous. It **looks** like microservices, it runs, it demos —
> and it teaches you nothing about distributed systems, because `@Transactional` hides
> every hard problem.

---

**Back to:** [README](../README.md) · [04 — Contention Strategies](04-contention-strategies.md)
