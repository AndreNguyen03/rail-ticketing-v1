# baseline.md — Stage 2 (một phần: experiment A)

> Đo trên topology Stage 0: 4 service × 1 instance, Hikari pool 10,
> trip 1 (SE1 2026-02-14, 506 berths), k6 `loadtest/k6/hold-contention.js`
> `VUS=1000 DURATION=60s`. Mỗi run reset DB (`down -v`, seed lại 1012 berths).
> Chưa làm: B (scale instance → USL), C (1 vs 200 trips), D (network hop).

## 1. Kết quả tổng hợp (3 runs 1000 VU)

| Run | Confirm-200 (k6) | Sold-out | 5xx | CONFIRMED (DB) | PAYMENT_FAILED | Verify |
|---|---|---|---|---|---|---|
| 2 | 565 | 79,996 | 0 | 506 | 59 | INV-1..4 GREEN |
| 3 | 557 | 61,272 | 0 | 506 | 51 | INV-1..4 GREEN |
| 4 | 561 | 69,737 | 0 | 506 | 55 | INV-1..4 GREEN |

> `confirm-200` của k6 gồm cả PAYMENT_FAILED (confirm luôn trả 200 theo thiết kế
> stage 0). DB là số thật: **3/3 lần bán đúng 506 vé, không oversell.**

## 2. Số Grafana (run 3 vs run 4 — khoảng dao động giữa các lần chạy)

| Metric | Run 3 | Run 4 |
|---|---|---|
| Total RPS peak | 1.28K | 2.34K |
| inventory RPS (mean / max) | 733 / 1.12K | 602 / 1.19K |
| booking-service RPS | 12.1 / 23.6 | 0.676 / 1.02 |
| schedule-service RPS | 0.047 | 0.058 |
| gateway p99 / p50 | 4.61s / 661ms | 5.16s / 859ms (max 8.5s) |
| booking p99 / p50 | 10.7s / 4.96s | 7.16s / 1.14s |
| inventory p99 / p50 | 4.95s / 807ms | 5.06s / 1.12s |
| schedule p99 | 111ms | 128ms |
| 409 rate (mean / max) | — | 1.44K / 2.33K |
| holds CLIENT_ERROR (mean / max) | 725 / 1.12K | 798 / 1.19K |
| holds SUCCESS | 0.64 | 4.10 / 6.15 |
| inventory pool active / pending max | 10 / 980 (mean 524) | 10 / 971 (mean 588) |
| booking pool pending max | 149 | 0 (đói traffic) |
| gateway heap max | 742 MiB | 525 MiB |
| 5xx | không có series | không có series |

## 3. Đọc số (bottleneck ở đâu)

1. **Pool + row lock là trần.** `inventory active` ghim 10, `pending` 500–1000
   qua cả 3 runs. Pool 10 cố tình để nhỏ (`application.yml`) và nó bão hòa
   đúng như thiết kế thí nghiệm.
2. **Booking là mắt xích chậm nhất** (p99 7–11s): 1 confirm đi qua
   hold → insert → commit 3 service + chờ lock inventory.
3. **Hết vé thì booking đói.** Sau khi 506 vé hết, iteration chết ở hold-409
   (return sớm trong script) → booking RPS rơi về ~0, hệ thống chỉ còn trả
   sold-out nhanh. Không phải booking hỏng.
4. **Schedule ngủ** (0.05 rps, p99 ~120ms): catalog lạnh — chia service theo
   load shape đúng.
5. **Front-door sập dưới spike.** Cả 3 runs: ~5s đầu `connection refused`
   khi 1000 VU ập vào cùng lúc, rồi tự hồi. Vật liệu Stage 3/6.
6. **Correctness giữ vững.** 0 5xx, verify xanh, đúng 506 vé mọi lần.

## 4. Chưa đo (TODO Stage 2)

- [ ] B: inventory 1 → 2 → 4 → 8 → 16 instances — đường cong USL (đỉnh rồi giảm)
- [ ] C: 1 trip vs 200 trips cùng tổng RPS — contention biến mất khi dàn trải
- [ ] D: % latency là network hop giữa các service

## 5. Chạy lại

```bash
docker compose --profile services down -v
docker compose --profile services up -d
# đợi berth_inventory = 1012 (DataSeeder cần SCHEDULE_URL, đã fix trong compose)
docker run --rm -v "${PWD}/loadtest/k6:/scripts" grafana/k6 run \
  -e GATEWAY_URL=http://host.docker.internal:8080 \
  -e VUS=1000 -e DURATION=60s /scripts/hold-contention.js
# verify (trong container postgres, host không có psql):
docker exec rt-postgres psql "postgresql://inventory_user:inventory_pw@localhost:5432/inventorydb" \
  -t -A -c "SELECT count(*) FROM berth_inventory WHERE (held_mask & occupied_mask) <> 0;"
docker exec rt-postgres psql "postgresql://booking_user:booking_pw@localhost:5432/bookingdb" \
  -t -A -c "SELECT status, count(*) FROM booking GROUP BY status;"
```
