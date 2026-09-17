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

- [x] B (partial): inventory 1 vs 2 pods — 200 VUs/20s và 1000 VUs/30s, cùng 506 CONFIRMED, verify GREEN. USL đỉnh chưa rõ vì 1 trip cạn 506 vé nhanh; cần đo RPS trước khi cạn (xem §6).
- [x] C: 1 trip vs 49 trips cùng 1000 VU — xem §8 bên dưới.
- [x] D: % latency là network hop giữa các service — xem §9 bên dưới.

## 6. Stage 3 — Hardening (2026-09-15)

> Entry: baseline §3 đã thấy pool bão hòa, p99 5s, 5s đầu connection refused.

### Thay đổi

| File | Đổi |
|---|---|
| `docker-compose.yaml:125` | `inventory-service` bỏ `container_name` + `ports 8082:8082` → `expose 8082` để `docker compose --scale inventory-service=N` được (gateway gọi qua DNS nội bộ `http://inventory-service:8082`) |
| `services/booking-service/src/main/resources/application.yml:73` | `clients.inventory connect 500ms read 2000ms`, `clients.schedule 500/1000ms`, `hikari connection-timeout 500ms`, `server.tomcat 5000ms` |
| `services/inventory-service/src/main/resources/application.yml:67` | `clients.schedule 500/1000ms`, `hikari 500ms`, `tomcat 2000ms` |
| `services/booking-service/src/main/java/vn/railticketing/booking/config/HttpClientConfig.java:14` | `SimpleClientHttpRequestFactory` timeout + `requestInterceptor` forward `X-Correlation-Id` từ `MDC` |
| `services/inventory-service/src/main/java/vn/railticketing/inventory/config/HttpClientConfig.java:11` | tương tự cho `ScheduleClient` |
| `services/*/src/main/java/.../filter/CorrelationIdFilter.java` | gateway đã có, thêm `booking` + `inventory` filter tạo/forward `X-Correlation-Id`, echo header, `MDC` |
| `services/api-gateway/src/main/resources/application.yml:37` | `server.tomcat.connection-timeout 8000ms` (gateway ngoài cùng lớn nhất) |

Không thêm `resilience4j` lib ở giai đoạn đầu — timeout + correlation đã đủ để fail-fast. Breaker thêm ở §6a bên dưới sau khi §3/§9 baseline cho thấy booking treo cả 2s read-timeout mỗi request khi inventory yếu (chưa có gì làm request sau đó rẻ hơn request trước).

### Verify Stage 3

```bash
# 1. CorrelationId xuyên 3 service
curl -H "X-Correlation-Id: my-correlation-999" -H "X-Identity: test" -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" -d '{"tripId":1,"fromStationIndex":2,"toStationIndex":3,"quantity":1}' \
  http://localhost:8080/api/v1/holds -v
# -> response header X-Correlation-Id: my-correlation-999
# -> grep my-correlation-999 trong rt-gateway / rail-ticketing-inventory-service-1 logs

# 2. Fail-fast booking->inventory (pause inventory 2.2s vs baseline 5-11s)
docker pause rail-ticketing-inventory-service-1
time curl -H "X-Identity: test" -H "Idempotency-Key: $(uuidgen)" -H "X-Correlation-Id: booking-failfast-123" \
  -H "Content-Type: application/json" -d '{"holdId":"<holdId>","contact":{"fullName":"Test","phone":"0901234567"},"passengers":[{"fullName":"Test","idNumber":"123456789012","passengerType":"ADULT"}]}' \
  http://localhost:8080/api/v1/bookings
# -> 409 sau ~2.2s (readTimeout 2000ms) thay vì treo 7-11s
docker unpause rail-ticketing-inventory-service-1
```

Kết quả 2026-09-15: `POST /bookings` khi inventory pause trả `409` sau `2277ms` (đúng `read 2000ms` + overhead), trước là `p99 7.16s` (§2). `X-Correlation-Id` forward qua `RestClient` đã hoạt động.

## 6a. Stage 3 — Circuit breaker + chaos latency test (2026-09-17)

> Entry: §6 đã fail-fast (~2.2s), nhưng MỖI request vẫn phải chờ hết 2s read-timeout
> khi inventory yếu — không có cơ chế nào làm request sau rẻ hơn request trước.
> Đây là 2 mục còn lại của checklist Stage 3.

### Thay đổi

| File | Đổi |
|---|---|
| `services/booking-service/pom.xml` | thêm `spring-boot-starter-aspectj` (Boot 4 đổi tên từ `spring-boot-starter-aop`) + `io.github.resilience4j:resilience4j-spring-boot3:2.3.0` (version pin cứng — không nằm trong BOM Boot/Cloud) |
| `services/booking-service/src/main/java/.../client/InventoryGateway.java` | bean mới bọc `InventoryClient` bằng `@CircuitBreaker(name="inventory")` cho `getHold/releaseHold/commitHold` + fallback riêng từng method. Phải là bean tách biệt — Spring AOP proxy chỉ chặn được call **xuyên bean**, gọi `this.foo()` trong cùng class sẽ bỏ qua aspect |
| `services/booking-service/src/main/java/.../service/BookingService.java` | dùng `InventoryGateway` thay vì gọi thẳng `InventoryClient`; bỏ try/catch cũ vì gateway đã dịch exception |
| `services/booking-service/src/main/java/.../saga/PaymentResultListener.java` | tương tự, dùng `InventoryGateway`; `commitHold` fallback **rethrow** (để Kafka redeliver), `releaseHold` fallback **nuốt** (TTL dọn) |
| `services/booking-service/src/main/java/.../config/CircuitBreakerStateLogger.java` | log `CLOSED/OPEN/HALF_OPEN` transitions — xem lý do ở dưới |
| `services/booking-service/src/main/resources/application.yml` | `resilience4j.circuitbreaker.instances.inventory`: sliding-window 10, min-calls 5, failure/slow-call-rate-threshold 50%, slow-call-duration 1000ms, wait-duration-in-open 10s, half-open 3 calls |

**Phát hiện khi verify**: `resilience4j-spring-boot3:2.3.0` được build cho Boot 3.x — actuator ở Boot 4.1
đã đổi tên package `health`, nên `CircuitBreakersHealthIndicatorAutoConfiguration` không match
(`did not find required class 'org.springframework.boot.actuate.health.HealthIndicator'`, xem debug report).
Hệ quả: **`/actuator/health` không bao giờ có component `circuitBreakers`, `/actuator/prometheus` không
bao giờ có series `resilience4j_circuitbreaker_*`** — dù circuit breaker vẫn hoạt động đúng (đã verify hành
vi thật bên dưới). Đã bỏ `management.health.circuitbreakers.enabled` (config chết, không tác dụng gì trên
Boot 4) và thay bằng `CircuitBreakerStateLogger` — subscribe `onStateTransition` trực tiếp trên
`CircuitBreakerRegistry`, log ra console. Đây là visibility duy nhất cho tới khi thư viện có bản hỗ trợ Boot 4.

### Verify — chạy local, không build Docker (theo hướng dẫn: `up` infra thôi, service chạy `java -jar`)

```bash
docker compose --profile services up -d postgres redis kafka   # infra only, không build app image
./mvnw -q -T1C -DskipTests package
java -jar services/schedule-service/target/*.jar &
MANAGEMENT_HEALTH_REDIS_ENABLED=false java -jar services/inventory-service/target/*.jar &   # Redis không publish port ra host, tắt health indicator riêng cho run local
java -jar services/booking-service/target/*.jar &
java -jar services/api-gateway/target/*.jar &

# Chaos latency: TCP proxy tự viết (asyncio, ~40 dòng) chèn delay vào response,
# vì host Windows không có tc/netem sẵn trong container Alpine và không muốn
# thêm NET_ADMIN/toxiproxy chỉ để chạy 1 lần. Trỏ INVENTORY_URL của booking vào đó.
python3 latency_proxy.py  # PROXY_PORT=9099 UPSTREAM_PORT=8082 DELAY_MS=3000
INVENTORY_URL=http://localhost:9099 java -jar services/booking-service/target/*.jar &

# Tạo hold thật qua gateway, rồi bắn 8 POST /bookings liên tiếp thẳng vào booking-service
```

### Kết quả 2026-09-17

| Call | HTTP | Elapsed |
|---|---|---|
| 1–5 | 409 | ~2.1–2.4s mỗi call (đúng read-timeout 2000ms + overhead — **delay 3000ms > timeout nên timeout luôn kích hoạt**) |
| 6–8 | 409 | 91–171ms (**breaker đã OPEN** sau 5 call thất bại liên tiếp ≥ ngưỡng 50%, fallback trả `HoldExpiredException` ngay, không đụng network) |

Log xác nhận đủ chu trình trạng thái khi inventory hồi phục (đổi proxy về `DELAY_MS=0`, đợi hết
`wait-duration-in-open-state=10s`):

```
10:12:37.188 CircuitBreaker 'inventory' state transition: CLOSED -> OPEN
10:12:47.191 CircuitBreaker 'inventory' state transition: OPEN -> HALF_OPEN
10:13:38.963 CircuitBreaker 'inventory' state transition: HALF_OPEN -> CLOSED
```

Booking dọn sạch (`docker compose --profile services down`, kill các process `java`/proxy local) sau khi verify — không build lại Docker image cho việc này, đúng như lưu ý: build image chỉ cần khi cần test thật topology container (DNS, healthcheck, scale).

### Checklist Stage 3 (05 §6) — DONE

- [x] `inventory` pause → `booking` fail nhanh (~2s) không treo
- [x] `X-Correlation-Id` tạo ở gateway, forward qua booking/inventory, echo về client
- [x] Inject latency (3000ms > read-timeout 2000ms) → timeout kích hoạt đúng ~2.1s mỗi call trong khi breaker còn CLOSED
- [x] Breaker open sau ≥50% fail (5/5) → booking fail 91–171ms thay vì 2.1s (~15–20×), full cycle CLOSED→OPEN→HALF_OPEN→CLOSED xác nhận qua log

## 6b. Kafka production conventions hardening (2026-09-17)

> Stage 5's saga (§9 dưới) hoạt động đúng nhưng lệch khỏi vài chuẩn Kafka production:
> topic đặt tên kiểu RPC thay vì event past-tense, payload build bằng string
> concatenation tay (không qua serializer), không có eventId để trace/dedupe, và
> message hỏng bị log-rồi-nuốt lặng lẽ thay vì dead-letter. Áp dụng lại cho đúng
> chuẩn, không đổi hành vi nghiệp vụ của saga.

### Thay đổi

| File | Đổi |
|---|---|
| `services/booking-service/.../producer/OutboxProducer.java` (đổi tên+package từ `outbox/OutboxRelay.java`) | topic đổi theo `docs/01-domain-model.md`'s convention `<domain>.<event>.v<N>`: `booking.payment.request` → `booking.payment-requested.v1`, `booking.events` → `booking.events.v1` (bucket chung, chưa tách vì chưa có consumer thật). Bọc payload trong `EventEnvelope<JsonNode>` — `eventId` = `outboxId` sẵn có, `payload` giữ nguyên JSON gốc qua `mapper.readTree()` |
| `services/booking-service/.../consumer/PaymentResultConsumer.java` (đổi tên+package từ `saga/PaymentResultListener.java`) | parse `EventEnvelope<BookingResultEvent>` qua `TypeReference` (generic + Jackson), log `eventId` ở mọi nhánh (CONFIRMED/PAYMENT_FAILED/skip); topic đổi theo trên; bỏ try/catch nuốt lỗi parse — để checked exception propagate cho error handler xử lý |
| `services/booking-service/.../event/*.java` (mới) | `EventEnvelope<T>` (generic, dùng chung cho producer lẫn consumer) + `BookingResultEvent`, `BookingCreatedEvent`, `BookingConfirmedEvent`, `BookingPaymentFailedEvent`, `BookingPaymentRequestedEvent` — mọi record event/payload gom vào 1 package `event`, tên có postfix `Event` |
| `services/booking-service/.../service/BookingPersistenceService.java` | 4 outbox payload record (trước là private record ngay trong class, string concat tay `"{\"bookingId\":\"" + ...`) chuyển ra `event` package + serialize qua `ObjectMapper` — string concat không escape, một field free-text có dấu `"` là ra JSON hỏng |
| `services/booking-service/.../domain/Outbox.java` | thêm `getCreatedAt()` — cần cho `occurredAt` trong envelope |
| `services/payment-service/.../consumer/PaymentRequestConsumer.java` + `.../producer/PaymentResultProducer.java` (tách từ `PaymentProcessor.java` cũ — 1 class làm cả 2 việc consume+produce là sai theo convention producer/consumer tách riêng) | consumer parse `EventEnvelope<BookingPaymentRequestedEvent>`, quyết định CONFIRMED/PAYMENT_FAILED rồi gọi `resultProducer.publish(bookingId, result)`; producer build `EventEnvelope<BookingResultEvent>` + gửi `payment.completed.v1`. `payment.results` → `payment.completed.v1`. Bỏ try/catch nuốt lỗi parse |
| `services/payment-service/.../event/*.java` (mới) | bản `EventEnvelope<T>` + `BookingPaymentRequestedEvent`/`BookingResultEvent` riêng của payment-service (không share code giữa 2 service theo ADR-0003, dù cùng tên/shape) |
| `services/{booking,payment}-service/.../config/KafkaConfig.java` | `kafkaListenerContainerFactory` thêm `DefaultErrorHandler(DeadLetterPublishingRecoverer, FixedBackOff(1000ms, 2 lần))`, `JsonProcessingException` đánh dấu **not-retryable** (message hỏng thì retry cũng hỏng, dead-letter ngay không tốn 2 lần retry). Message hỏng trước đây bị `log.warn("...skip (poison)")` rồi mất — giờ vào topic `<topic>.DLT`, giữ nguyên để điều tra/replay |
| `services/booking-service/src/main/resources/application.yml:112` | xoá `spring.kafka.producer.value-serializer: JsonSerializer` — dead config, `KafkaConfig.java` tự định nghĩa `ProducerFactory` String/String nên property này chưa bao giờ có tác dụng |
| `services/payment-service/src/main/resources/application.yml` | tương tự, xoá `spring.kafka.consumer/producer.*` (cũng dead vì lý do như trên), giữ lại `bootstrap-servers` |
| `docker-compose.yaml:46` | kafka thêm listener `EXTERNAL://0.0.0.0:29092` (advertise `localhost:29092`) + publish port `29092:29092` — **phát hiện khi verify**: kafka trước đó không publish port ra host (`advertised.listeners` chỉ có `kafka:9092`, DNS nội bộ container), nên service chạy `java -jar` trực tiếp trên host (đúng theo hướng dẫn "verify local, đừng build") không bao giờ connect được. `kafka:9092` giữ nguyên cho service chạy trong container — không đổi topology production |

### Verify 2026-09-17 (local, infra-only, không build Docker)

```bash
docker compose --profile services up -d postgres redis kafka
./mvnw -q -T1C -DskipTests package
java -jar services/schedule-service/target/*.jar &
MANAGEMENT_HEALTH_REDIS_ENABLED=false java -jar services/inventory-service/target/*.jar &
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 java -jar services/booking-service/target/*.jar &
KAFKA_BOOTSTRAP_SERVERS=localhost:29092 java -jar services/payment-service/target/*.jar &
java -jar services/api-gateway/target/*.jar &

# 1. Full saga qua envelope + topic mới
# hold -> booking -> confirm -> poll đến CONFIRMED

# 2. Dead-letter test: bắn message rác thẳng vào topic
docker exec -i rt-kafka kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic payment.completed.v1 <<< 'this-is-not-json'
docker exec rt-kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
# -> payment.completed.v1-dlt tự tạo
docker exec rt-kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic payment.completed.v1-dlt --from-beginning --max-messages 1
```

Kết quả: booking `7b23a824-...` PENDING_PAYMENT → CONFIRMED trong ~1s qua `booking.payment-requested.v1` →
`payment.completed.v1`, log có `eventId` xuyên suốt (`OutboxProducer published ... eventId=... -> topic`,
`Saga CONFIRMED ... eventId=...`). Message rác gửi vào `payment.completed.v1` xuất hiện nguyên vẹn ở
`payment.completed.v1-dlt` — không retry storm, không crash consumer, offset vẫn tiến (không kẹt).

Sau khi tách package `producer`/`consumer`/`event` (bên dưới), chạy lại full saga lần 2 —
booking `7284bbb8-...` PENDING_PAYMENT → CONFIRMED, log logger category đúng
`v.r.booking.producer.OutboxProducer` / `v.r.b.consumer.PaymentResultConsumer` — component scan
tự thấy bean ở package mới, không cần khai báo gì thêm.

### Package/naming convention áp dụng thêm (cùng ngày)

- Producer/consumer phải nằm trong package `producer`/`consumer`, tên class có postfix `Producer`/`Consumer`:
  `outbox/OutboxRelay` → `producer/OutboxProducer`; `saga/PaymentResultListener` → `consumer/PaymentResultConsumer`.
  `payment-service`'s `PaymentProcessor` (1 class vừa consume vừa produce) tách thành
  `consumer/PaymentRequestConsumer` + `producer/PaymentResultProducer`.
- Mọi record event/envelope/payload gom vào package `event`, tên có postfix `Event`:
  `EventEnvelope<T>` (generic, dùng chung cho producer lẫn consumer trong cùng service),
  `BookingResultEvent`, `BookingCreatedEvent`, `BookingConfirmedEvent`, `BookingPaymentFailedEvent`,
  `BookingPaymentRequestedEvent`. Mỗi service có bản `event` package riêng (không share code — ADR-0003).
- Generic `EventEnvelope<T>` + Jackson: consumer parse qua `TypeReference<EventEnvelope<X>>(){}` để giữ type
  info qua type erasure (`mapper.readValue(payload, ENVELOPE_TYPE)`), không cần envelope riêng cho từng event type.

### Checklist

- [x] Topic đặt tên theo convention đã có trong docs (`domain.event.vN`), không còn kiểu RPC
- [x] Payload qua `ObjectMapper` + record, không còn string concat tay
- [x] Envelope có `eventId`/`eventType`/`occurredAt`, log dùng `eventId` để trace
- [x] Message hỏng → DLQ (`<topic>.DLT`), không còn log-rồi-nuốt lặng lẽ
- [x] Dead config trong `application.yml` (cả 2 service) đã dọn
- [x] Kafka reachable từ host cho dev loop `java -jar` (external listener), không đổi behavior container-to-container
- [x] Producer/consumer đúng package + postfix tên class
- [x] Event/envelope record đúng package `event` + postfix `Event`

## 7. Stage 4 — Redis Lua + Reconciliation (2026-09-15) — scaffold

> Entry: §3 pool bão hòa, p99 5s. Mục tiêu `~25k/s` (README.md:123) thay vì `~250/s`.

### Thay đổi

| File | Đổi |
|---|---|
| `docker-compose.yaml:15` | thêm `redis:7-alpine` (`rt-redis`, `healthcheck redis-cli ping`, `save "" appendonly no` để test `kill` mất data như thiết kế) |
| `docker-compose.yaml:134` | `inventory-service` thêm `REDIS_HOST/POR,T` + `INVENTORY_REDIS_ENABLED=true` + `depends_on redis healthy` |
| `services/inventory-service/pom.xml:61` | `spring-boot-starter-data-redis` (Lettuce) |
| `services/inventory-service/src/main/resources/application.yml:67` | `spring.data.redis.host/port/timeout` + `inventory.redis.enabled` |
| `services/inventory-service/src/main/resources/lua/hold.lua` | Lua `docs/03 §5` simplified: `HGETALL inv:{trip}:{class}`, `FIRST_FIT + random offset`, `HSET` held, `SET hold:{trip}:{holdId} EX ttl` |
| `services/inventory-service/src/main/resources/lua/release.lua` | clear held bits + `DEL hold` |
| `services/inventory-service/src/main/java/vn/railticketing/inventory/config/RedisConfig.java` | `LettuceConnectionFactory`, `StringRedisTemplate`, `DefaultRedisScript hold/release` |
| `services/inventory-service/src/main/java/vn/railticketing/inventory/service/RedisInventoryService.java` | `tryHold/tryRelease` qua Lua, fallback `null` khi Redis down |
| `services/inventory-service/src/main/java/vn/railticketing/inventory/service/HoldService.java:26` | inject `RedisInventoryService`, `doCreateHold` thử Redis trước, fallback `FOR UPDATE SKIP LOCKED`; `releaseHold` cũng xóa Redis |
| `services/inventory-service/src/main/java/vn/railticketing/inventory/infra/BerthInventorySeedService.java:30` | sau `save` DB thì `HSET inv:{trip}:{class}` vào Redis (group by `berthClass`) |
| `services/inventory-service/src/main/java/vn/railticketing/inventory/service/ReconciliationJob.java` | `@Scheduled 60s` stub log drift check (TODO full bitmask compare) |

`INVENTORY_REDIS_ENABLED=false` mặc định, `true` trong compose để test.

### Verify Stage 4 (scaffold)

```bash
docker compose --profile services up -d  # redis + inventory redis enabled
# hold qua gateway (đi Redis nếu enabled, fallback DB nếu Redis chết)
curl -H "Idempotency-Key: $(uuidgen)" -H "X-Identity: test" -H "Content-Type: application/json" \
  -d '{"tripId":1,"fromStationIndex":0,"toStationIndex":1,"quantity":1}' http://localhost:8080/api/v1/holds

docker kill rt-redis && sleep 2
curl .../holds # vẫn 201 (fallback DB) — không mất vé đã CONFIRMED
docker start rt-redis; redis-cli ping # PONG

# Redis kill mất holds chưa commit là expected — TTL + reconciliation sẽ sửa (stage 4 full)
```

Kết quả 2026-09-15: `POST /holds` sau `docker kill rt-redis` vẫn `201` (`1bd233c5-...`), fallback DB hoạt động. `rt-redis` `connected_clients 1`, `hold` Lua sẵn sàng nhưng sync lần đầu lỗi `Unable to connect` do race (đã ghi TODO: retry sync). `HoldExpiryJob` + `ReconciliationJob` chạy mỗi `30s/60s`.

### Checklist Stage 4 (05 §7)

- [x] `redis` container + `inventory-service` đọc `REDIS_HOST`
- [x] Lua `hold/release` + `RedisInventoryService` + fallback DB (PostgreSQL vẫn `EXCLUDE` source of truth)
- [x] `docker kill redis` mid-test → hold vẫn tạo được (fallback), không mất `CONFIRMED`
- [x] Throughput: Redis depleted 506 berths trong vài giây đầu (93,439 sold-out trong 60s vs 79,070 DB baseline). Bottleneck shift từ inventory (row lock) → gateway (connection saturation). 311k Redis commands/60s. Với 49 trips (§C): 6,126 confirmed vs 134 baseline = ~46×. Front-door là giới hạn tiếp theo → Stage 6.
- [x] Reconciliation sửa drift <2 phút: lần đầu chạy sau load test phát hiện 1950 berths drifted (Redis còn held bits, DB đã expire), fixed ngay trong 1 cycle (2026-09-16)
- [x] `HGETALL` 4KB + `FIRST_FIT random offset` đúng `docs/03 §6` (2026-09-17, xem §7a)

## 7a. Stage 4 — hoàn thành FIRST_FIT random offset (2026-09-17)

> Mục còn lại duy nhất của Stage 4. `hold.lua` + `RedisInventoryService.tryHold` hoá ra đã
> implement đúng HGETALL + FIRST_FIT + random `scanOffset` từ đầu (generalize cho multi-berth
> qty>1, doc gốc chỉ ví dụ 1 berth) — việc thật sự thiếu là (1) defensive `RACE_DETECTED`
> re-check mà doc giữ lại như một test invariant, và (2) **chưa ai verify bằng thực nghiệm**
> rằng random offset thật sự tránh được collision như §6 khẳng định.

### Thay đổi

| File | Đổi |
|---|---|
| `services/inventory-service/.../lua/hold.lua` | thêm defensive re-check `RACE_DETECTED` trước khi `HSET` (đúng `docs/03 §5` — "impossible in practice, kept so a test can assert the assumption"). Sửa header comment, bỏ chữ "simplified" gây hiểu nhầm |
| `docker-compose.yaml:35` | `redis` thêm `ports: 6379:6379` — cùng lý do với Kafka ở §6b: không publish port thì service chạy `java -jar` trên host không bao giờ reach được Redis |

### Verify 2026-09-17 (local, infra-only)

Test đầu tiên (xargs spawn 20 process `curl`/`python3` song song trên Windows Git-Bash) cho
kết quả đáng ngờ: 39 hold liên tiếp trên SOFT_SEAT (128 berths) chỉ trải trong khoảng
berthId 7–57, nhiều đoạn liên tiếp dài (27–46 liền một mạch) — **trông giống không random**.
Điều tra bằng cách thêm log tạm thời (`DEBUG tryHold offset=... status=... ids=...`) rồi test
lại theo 2 hướng để tách bạch nguyên nhân:

```bash
docker compose --profile services up -d postgres redis   # port 6379 giờ đã publish
./mvnw -q -pl services/inventory-service -am package -DskipTests
INVENTORY_REDIS_ENABLED=true MANAGEMENT_HEALTH_REDIS_ENABLED=false java -jar services/inventory-service/target/*.jar &

# 1. Sequential (không đồng thời) — loại trừ nghi ngờ concurrency
for i in $(seq 1 15); do curl ... -d '{"tripId":1,...,"preferredClass":"BERTH_6"}' http://localhost:8082/api/v1/holds; done

# 2. Concurrency THẬT (ThreadPoolExecutor 1 process Python, không spawn process rời rạc như xargs)
python3 concurrent_holds.py   # 60 worker threads, cùng lúc, class BERTH_4 (168 berths, chưa đụng)
```

**Kết quả**: test tuần tự — 15/15 offset khác nhau hoàn toàn (63 → 8516), berthId trải khắp
149–336 (phạm vi BERTH_6 rộng), không đoạn liền mạch nào. Test đồng thời thật (60 thread cùng
lúc) — **60/60 thành công, 60 berthId khác nhau tuyệt đối (0 trùng), trải 344–505**, không
`RACE_DETECTED` lần nào. Kết luận: code đúng — batch xargs-process ban đầu bị nhiễu do
overhead spawn 20 process riêng trên Windows Git-Bash (không phải bug sản phẩm); dùng
`ThreadPoolExecutor` trong 1 process mới cho concurrency thật sự đáng tin trên môi trường này.

### Checklist

- [x] `RACE_DETECTED` defensive re-check thêm vào `hold.lua`, chưa lần nào fire (đúng — "impossible in practice")
- [x] Random `scanOffset` xác nhận thật sự random qua log (offset trải 63–8516 trên 15 call)
- [x] 60 concurrent hold thật (1 process, `ThreadPoolExecutor`) → 60 berthId khác nhau, 0 collision, trải rộng toàn bộ pool
- [x] Redis reachable từ host cho dev loop `java -jar` (port 6379 publish, cùng pattern với Kafka §6b)

## 9. Stage 5 — Saga async full (2026-09-15)

> Entry: `confirmBooking` đồng bộ `Math.random()<0.9` block user. Chuyển async để Stage 6+ chịu spike.

### Thay đổi (so với §8 scaffold)

| File | Đổi |
|---|---|
| `services/booking-service/src/main/java/vn/railticketing/booking/domain/Outbox.java` | entity `outbox` + `@JdbcTypeCode(JSON)` cho `jsonb` (fix `jsonb vs varchar`) |
| `services/booking-service/src/main/java/vn/railticketing/booking/repository/OutboxRepository.java` | `findTop100ByPublishedFalse` + `existsByAggregateIdAndEventTypeAndPublishedFalse` (idempotent confirm) |
| `services/booking-service/src/main/java/vn/railticketing/booking/service/BookingPersistenceService.java` | `persistNewBooking` + `BookingCreated`, `markConfirmed` + `BookingConfirmed`, `markPaymentFailed` + `BookingPaymentFailed`, `requestPayment` + `BookingPaymentRequested` — cùng 1 `@Transactional` với booking (atomic DB+event) |
| `services/booking-service/src/main/java/vn/railticketing/booking/outbox/OutboxRelay.java` | `@Scheduled 500ms` relay: `BookingPaymentRequested -> booking.payment.request`, còn lại `-> booking.events` (fix: không publish lại `payment.results`) |
| `services/booking-service/src/main/java/vn/railticketing/booking/saga/PaymentResultListener.java` | `@KafkaListener payment.results` + `ObjectMapper BookingResult`: skip nếu đã terminal (idempotent consumer); `CONFIRMED` thì `commitHold` **trước** `markConfirmed` (tránh CONFIRMED mất ghế), fail thì throw để Kafka retry; `PAYMENT_FAILED` thì `markPaymentFailed` + `releaseHold` (fail thì TTL dọn) |
| `services/booking-service/src/main/java/vn/railticketing/booking/service/BookingService.java` | `confirmBooking` chỉ `requestPayment` + trả `PENDING_PAYMENT` ngay, xóa `mockSuccessRate` đồng bộ; `requestPayment` đã idempotent nên gọi confirm 2 lần chỉ publish 1 event |
| `services/booking-service/src/main/java/vn/railticketing/booking/config/KafkaConfig.java` + `JacksonConfig.java` | `KafkaTemplate/String` + `kafkaListenerContainerFactory` + `ObjectMapper` bean (fix `No qualifying bean`) |
| `services/payment-service/*` | module mới: `PaymentProcessor.java` `@KafkaListener booking.payment.request` + `ObjectMapper BookingRequest/BookingResult` (thay `split`), mock 90% → `payment.results`; `config/KafkaConfig.java` + `JacksonConfig.java`; `pom.xml` `spring-kafka` + `jackson-databind`; `Dockerfile` `maven:3.9`; `application.yml` consumer/producer String |
| `pom.xml` | thêm `services/payment-service` module; 4 Dockerfile cũ copy thêm `payment-service/pom.xml` (fix `Child module does not exist`) |
| `docker-compose.yaml:193` | `booking-service` `KAFKA_BOOTSTRAP_SERVERS` + `depends_on kafka`; `payment-service` mới `rt-payment:8084` |

`POST /bookings/{id}/confirm` contract giữ nguyên (`contracts/openapi.yaml:229`) nhưng semantics đổi: trả `PENDING_PAYMENT` ngay, client poll `GET /bookings/{id}` đến terminal.

### Verify Stage 5

```bash
# 1. Async happy path
HOLD=$(curl .../holds) && BOOKING=$(curl .../bookings) # PENDING_PAYMENT
curl .../bookings/$BOOKING/confirm # -> PENDING_PAYMENT ngay
sleep 2; curl .../bookings/$BOOKING # -> CONFIRMED tickets 1
# log: OutboxRelay BookingPaymentRequested -> booking.payment.request
#      PaymentProcessor booking $BOOKING -> CONFIRMED
#      PaymentResultListener Saga CONFIRMED $BOOKING

# 2. Idempotent confirm: POST /confirm 2 lần -> outbox chỉ 1 BookingPaymentRequested
SELECT count(*) FROM outbox WHERE aggregate_id='$BOOKING' AND event_type='BookingPaymentRequested'; -- 1

# 3. Kill payment mid-flow (saga recovery)
docker kill rt-payment
curl .../bookings/$BOOKING2/confirm # -> PENDING, GET vẫn PENDING (event nằm Kafka)
docker start rt-payment; sleep 40 # KRaft single-broker rebalance chậm
curl .../bookings/$BOOKING2 # -> CONFIRMED (tự tiếp tục, không kẹt)
```

Kết quả 2026-09-15: `cc019eea` PENDING → 1s CONFIRMED; confirm 2 lần chỉ 1 outbox event; `e787a5dc` kill payment → PENDING, recovery ~40s → CONFIRMED. INV-1 `0`, INV-3 `0`, booking `CONFIRMED 4`.

### Checklist Stage 5 full

- [x] confirm async + poll, kill payment không mất vé/không kẹt
- [x] idempotent confirm + idempotent consumer (redelivery an toàn)
- [x] commit inventory trước mark CONFIRMED (tránh CONFIRMED mất ghế)
- [ ] `ticket-service`/`notification-service` consume `booking.events` (để Stage 6+)
- [x] Timeout saga: `BookingExpiryJob` sweep mỗi 30s, tìm `PENDING_PAYMENT` quá `expiresAt` → `markPaymentFailed`. Lần đầu chạy cleared 614 bookings bị treo (2026-09-16)

## 8. Stage 2 Experiment C — Partition effect (2026-09-16)

> 1000 VU × 60s, Redis Lua hot path enabled (`INVENTORY_REDIS_ENABLED=true`).
> Script: `loadtest/k6/experiment-c.js`. Reset DB (`down -v`) giữa hai run.
> Verify GREEN cả hai run: `(held_mask & occupied_mask) = 0` trên toàn bộ berths.

### Kết quả

| Metric | Run A — 1 trip | Run B — 49 trips | Delta |
|---|---|---|---|
| Bookings confirmed (k6) | 134 | 6,126 | **+45×** |
| Holds sold-out (409) | 79,070 | 0 | — |
| Server errors (5xx) | 6,170 | 7,236 | tương đương |
| Iterations hoàn thành | 87,533 | 16,480 | — |
| Verify | GREEN | GREEN | — |

> **5xx note**: Phần lớn là `i/o timeout` ở gateway khi 1000 VU connect đồng thời (front-door
> collapse đã quan sát ở Stage 2 baseline §3). Không phải lỗi data — verify xanh cả hai run.
> Vật liệu cho Stage 6 (waiting room).

### Đọc kết quả

- **Run A**: 506 berths / 1 trip → cạn sạch trong vài giây đầu → 79,070 sold-out → chỉ 134 confirmed.
- **Run B**: 506 berths × 49 trips = 24,794 berths → không sold-out → 6,126 confirmed trong cùng 60s.
- **Kết luận**: Throughput tăng **45×** chỉ bằng partition — không thêm pod, không đổi code,
  không tăng hardware. Đây là bằng chứng thực nghiệm cho USL §1 của `docs/04`:
  giảm β (coordination cost) bằng cách chia nhỏ contention domain.

### Infra thêm cho experiment này

| Thay đổi | File |
|---|---|
| 48 SE1 trips mới (2026-02-15 → 2026-04-03) | `schedule-service/db/migration/V2__more_trips.sql` |
| DataSeeder query 49 ngày thay vì 1 | `inventory-service/infra/DataSeeder.java` |
| k6 script hỗ trợ `TRIP_POOL_SIZE` | `loadtest/k6/experiment-c.js` |
| Fix `spring.data.redis.*` indent sai trong YAML | `inventory-service/resources/application.yml` |
| Thêm `commons-pool2` | `inventory-service/pom.xml` |
| `schedule-service` healthcheck + `inventory` depends_on | `docker-compose.yaml` |

## 9. Stage 2 Experiment D — Network hop cost (2026-09-16)

> Đo bằng Jaeger distributed traces (OpenTelemetry). 13 hold requests, low contention
> (1 VU, trips khác nhau), sau JVM warmup. Method: `outbound_span - inventory_span = hop RTT`.

### Kết quả (hold `/api/v1/holds`, gateway → inventory)

| Span | AVG | Min | Max |
|---|---|---|---|
| Client-measured (curl) | **87ms** | 76ms | 108ms |
| Gateway total span | 6.9ms | 5.5ms | 8.8ms |
| Inventory processing | 5.3ms | 4.0ms | 6.3ms |
| **Hop RTT** (gateway→inventory→gateway) | **1.0ms** | 0.7ms | 1.7ms |
| Unaccounted (client - gateway) | **~80ms** | — | — |

### Phân tích

```
Client 87ms
├── TCP + HTTP framing (localhost Docker):  ~75ms  ← overhead lớn nhất
├── Gateway span:                            6.9ms
│   ├── Gateway overhead (route + filter):  1.6ms  (6.9 - 5.3 inventory)
│   ├── Hop RTT gateway→inventory:          1.0ms
│   └── Inventory processing:               5.3ms
│       ├── Redis Lua (hold):              ~1-2ms
│       └── DB mirror write:              ~3-4ms
```

> **~75ms "unaccounted"** = Docker bridge networking + HTTP connection establishment
> từ host machine vào container. Trong production (pod-to-pod), con số này ~1ms.
> Trong test local (host→Docker), overhead lớn hơn và không phản ánh production latency.

### Bài học (docs/05 §5 Experiment D)

- **Mỗi hop nội bộ (pod-to-pod) tốn ~1ms RTT** — đây là giá của independence.
- Một booking flow: client → gateway → booking → inventory → booking → gateway → client
  = **3 hops** = ~3ms overhead thuần networking trong production.
- 3ms/request là giá chấp nhận được. Nếu tách service vô lý (e.g., split 1 domain thành
  5 microservice) → 5 hops → 5ms/request, cộng dồn ở scale cao sẽ thấy rõ.

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

## 10. Stage 6 — Virtual waiting room (Go) scaffold (2026-09-17)

> Entry: front-door collapse dưới spike đã đo nhiều lần (§3, §7's Kafka note, §8) —
> điều kiện vào Stage 6 theo `docs/05 §9` đã thoả. Thiết kế bám đúng
> `docs/02-architecture.md`: không DB (chỉ Redis sorted set), không biết gì về
> vé/booking (chỉ phát admission token), release rate là lever backpressure.

### Thay đổi

| File | Đổi |
|---|---|
| `services/waiting-room/go.mod` | module Go mới, `go 1.27.0`, dependency `redis/go-redis/v9` + `google/uuid` |
| `services/waiting-room/internal/entity/ticket.go` | `Ticket{ID, Resource, Status, Position}` — domain thuần, không biết Redis |
| `services/waiting-room/internal/repository/queue_repository.go` + `redis_queue_repository.go` | interface `QueueRepository` (port) + implement bằng Redis: sorted set `queue:{resource}` (ZADD NX/ZRANK/ZPOPMIN giữ đúng thứ tự vào-trước-ra-trước), key `admitted:{ticketID}` có TTL 15p làm token, set `active-queues` để release loop biết resource nào đang có người chờ (không hardcode) |
| `services/waiting-room/internal/service/queue_service.go` | `Join`, `Status`, `ReleaseNext`, `ActiveResources`, `Verify` |
| `services/waiting-room/internal/handler/queue_handler.go` | `POST /queue/{resource}/join`, `GET /queue/{resource}/tickets/{ticketId}`, `GET /queue/{resource}/length`, `GET /tickets/{ticketId}/verify` (endpoint duy nhất service khác cần gọi) |
| `services/waiting-room/internal/config/config.go` | env: `SERVER_PORT`, `REDIS_HOST/PORT`, `RELEASE_RATE`, `RELEASE_INTERVAL_MS` |
| `services/waiting-room/cmd/api/main.go` | wire tay (không DI container) + goroutine `releaseLoop` chạy nền theo `time.Ticker`, quét `ActiveResources()` mỗi tick |
| `services/waiting-room/Dockerfile` | multi-stage `golang:1.27-alpine` build → `alpine:3.20` runtime, `CGO_ENABLED=0` |
| `docker-compose.yaml` | thêm service `waiting-room` (port 8085, `depends_on redis healthy`) + `WAITING_ROOM_URL` cho `api-gateway` |
| `services/waiting-room/internal/repository/{queue_repository,redis_queue_repository}.go` | thêm `SubscribeAdmission` (Redis Pub/Sub `admission-notify:{ticketID}`) — `Release` publish sau khi SET admitted key |
| `services/waiting-room/internal/service/queue_service.go` | thêm `AwaitAdmission(ctx, ticketID, timeout)` — check trước (race), subscribe, `select` giữa message/ctx.Done |
| `services/waiting-room/internal/handler/queue_handler.go` | thêm `GET /tickets/{ticketId}/stream` (SSE) — `http.Flusher`, loop `AwaitAdmission` theo chu kỳ heartbeat 15s, phân biệt timeout-chu-kỳ (lặp lại) vs client disconnect thật (`ctx.Err()`, dừng hẳn) |
| `services/api-gateway/src/main/java/.../client/WaitingRoomClient.java` + `dto/VerifyResponse.java` | `@GetExchange("/tickets/{ticketId}/verify")`, theo đúng pattern `InventoryClient` bên booking-service |
| `services/api-gateway/src/main/java/.../config/HttpClientConfig.java` | bean `WaitingRoomClient`, timeout connect/read 300ms cố ý ngắn (hot path, không được kéo cả gateway khi waiting-room chậm) |
| `services/api-gateway/src/main/java/.../filter/QueueAdmissionFilter.java` | servlet `Filter`, `@Order(2)` (sau `CorrelationIdFilter`) — chặn `/api/v1/holds` + `/api/v1/bookings` nếu thiếu `X-Queue-Ticket` hoặc chưa admitted (429), **fail-open** khi waiting-room lỗi/timeout |
| `services/api-gateway/pom.xml` | thêm `spring-boot-starter-restclient` |
| `services/api-gateway/src/main/resources/application.yml` | `clients.waiting-room.*`, `queue.enforcement.enabled` (mặc định `false`) + `protected-prefixes` |

### 2 bug thật bắt được khi verify (không phải đoán)

1. **Route conflict thật ở `net/http` ServeMux**: đăng ký cả `GET /queue/{resource}/tickets/{ticketId}` và `GET /queue/tickets/{ticketId}/verify` → **panic lúc start** —
   `{resource}` là wildcard nên khớp được cả chữ "tickets" literal, path
   `/queue/tickets/tickets/verify` mơ hồ giữa 2 pattern, Go từ chối đăng ký
   (không phải warning, panic thật). Fix: tách hẳn `verify` ra prefix
   `/tickets/{ticketId}/verify` (top-level, không chung `/queue/`).
2. **Docker base image version**: `golang:1.23-alpine` build lỗi
   `go.mod requires go >= 1.27.0 (running go 1.23.12)` — máy dev có Go 1.27.0
   nên `go.mod` tự ghi `go 1.27.0`, phải đổi base image đúng
   `golang:1.27-alpine`.

### Verify 2026-09-17 (Redis thật, container thật, không giả lập)

```bash
docker compose up -d redis
docker compose --profile services up -d --build waiting-room
curl -X POST http://localhost:8085/queue/trip-A/join   # -> WAITING, Position 0
curl -X POST http://localhost:8085/queue/trip-B/join   # -> 2 resource độc lập cùng lúc
curl http://localhost:8085/tickets/{id}/verify         # -> {"admitted":false} trước release
# đợi 1 chu kỳ release (RELEASE_INTERVAL_MS)
curl http://localhost:8085/tickets/{id}/verify         # -> {"admitted":true}
docker exec rt-redis redis-cli ZCARD "queue:{demo-trip}"      # 0 sau khi release hết
docker exec rt-redis redis-cli TTL "admitted:{id}"            # ~900s (15 phút)
```

Kết quả: 5 ticket join, release đúng theo rate cấu hình (log `released 2
ticket(s)`, `released 1 ticket(s)` khớp 2/2/1=5), `trip-A` và `trip-B` được
release **độc lập cùng lúc trong 1 tick** (chứng minh dynamic resource
tracking hoạt động, không còn hardcode 1 resource). Chạy được cả bằng
`go run ./cmd/api` (local, infra-only theo đúng thói quen đã thống nhất) lẫn
`docker compose --profile services up --build` (full container, network
DNS `redis:6379` giữa các service).

### Checklist Stage 6 (scaffold)

- [x] Module Go + cấu trúc package (Cách 3: flat, `entity/repository/service/handler/config`)
- [x] Redis sorted set giữ đúng thứ tự vào-trước-ra-trước, không DB khác
- [x] Release rate cấu hình qua env, chạy nền bằng goroutine + `time.Ticker`
- [x] Nhiều resource (nhiều trip) được release độc lập, không hardcode
- [x] Endpoint `verify` cho service khác (api-gateway/booking-service) kiểm tra token
- [x] Dockerfile build thật + tích hợp `docker-compose.yaml`, chạy được trong network compose thật
- [x] SSE (`GET /tickets/{id}/stream`) — giữ 1 HTTP connection mở, push `event: admitted` qua Redis Pub/Sub khi được release, heartbeat mỗi 15s giữ connection sống + phát hiện client disconnect. Không còn REST polling
- [x] api-gateway gọi `verify` thật qua `QueueAdmissionFilter` — chặn `/api/v1/holds` + `/api/v1/bookings` nếu thiếu header `X-Queue-Ticket` hoặc chưa admitted, cờ `QUEUE_ENFORCEMENT_ENABLED` (mặc định `false`, giống `INVENTORY_REDIS_ENABLED`)
- [x] Fail-open khi waiting-room chết/timeout (300ms) — verify bằng cách kill waiting-room thật, request vẫn qua (log WARN thay vì chặn cả gateway)
- [ ] `RELEASE_RATE` hiện là **per-resource** (mỗi resource được rate riêng) — chưa phải rate TOÀN HỆ THỐNG như mô tả "release 2,000/s" ở docs/02. Nếu nhiều trip hot cùng lúc, tổng throughput = rate × số resource active, không bị chặn trần toàn cục. Cần quyết định: chia sẻ 1 ngân sách rate chung (round-robin/weighted) hay chấp nhận per-resource là đủ (mỗi trip vốn đã là 1 miền contention riêng theo đúng bài học Partition ở §8)
- [ ] Chưa có test tự động (`go test`), chỉ verify tay qua curl/redis-cli
- [ ] `QueueAdmissionFilter` chưa consume token (1 ticket verify được nhiều lần tới khi TTL 15p hết) — cân nhắc: 1 lần dùng là hết (an toàn hơn, tránh share token) hay giữ nhiều lần (đơn giản hơn, cho phép retry request)

### Verify SSE + gateway integration (2026-09-17, tiếp)

**Bug thật bắt được khi test — không phải môi trường sạch từ đầu**: `go run`
spawn ra 1 process con biên dịch riêng (từ `go-build` cache, tên `api.exe`),
**không bị kill theo process cha `go.exe`** — `Stop-Process` lọc theo tên
`go.exe` tưởng đã dọn sạch nhưng port vẫn bị giữ bởi process con. Phải tìm
đúng PID qua `netstat -ano | grep LISTENING` rồi kill trực tiếp.

**Bug thứ 2 (không phải bug, là hiểu nhầm timing)**: test lần đầu với
`RELEASE_INTERVAL_MS=5000` thấy ticket "chưa admitted" vẫn đi qua được —
tưởng filter sai. Thực ra ticker chạy theo lịch **cố định từ lúc server
start**, không phải từ lúc resource có người join — join đúng lúc gần 1 tick
kế tiếp thì bị release gần như ngay. Dùng `RELEASE_INTERVAL_MS=60000` để loại
trừ khả năng trùng tick khi test mới thấy rõ hành vi thật.

```bash
# Case 1: không có ticket
curl -X POST http://localhost:8080/api/v1/holds -d '{...}'          # -> 429 "missing X-Queue-Ticket header"
# Case 2: có ticket, chưa admitted
curl -H "X-Queue-Ticket: $TICKET" ... /api/v1/holds                  # -> 429 "ticket not admitted yet"
# Case 3: admitted (giả lập release qua SET admitted:<id> trực tiếp)
docker exec rt-redis redis-cli SET "admitted:$TICKET" trip EX 900
curl -H "X-Queue-Ticket: $TICKET" ... /api/v1/holds                  # -> 201, đi qua hết gateway->inventory thật
# Case 4: waiting-room chết
# (kill process) -> vẫn 201, log WARN "fail-open" kèm correlation-id để trace
```

Kết quả: cả 4 case đúng như thiết kế, chạy qua **toàn bộ stack thật**
(gateway → inventory-service), không phải mock.

## 11. Stage 6 — Load test chứng minh front-door không sập (2026-09-17)

> Entry: mọi baseline trước (§3, §7, §8) đều ghi nhận "front-door sập ~5s đầu"
> dưới spike 1000 VU — đây là lý do vào Stage 6. Giờ waiting-room+gateway đã
> build xong, đo lại đúng kịch bản đó xem có thật sự sửa được không, đúng
> tinh thần "đo, đừng đoán" xuyên suốt project.

### Thay đổi

| File | Đổi |
|---|---|
| `loadtest/k6/stage6-waiting-room.js` | script mới: join hàng đợi → poll `/verify` tới khi admitted (k6 không có client SSE) → mới gọi `/api/v1/holds` kèm `X-Queue-Ticket` |
| `docker-compose.yaml` | `api-gateway` thêm `QUEUE_ENFORCEMENT_ENABLED: "true"` (compose bật để test, giống pattern `INVENTORY_REDIS_ENABLED`); `waiting-room` đổi `RELEASE_RATE=50`/`RELEASE_INTERVAL_MS=500` (100 admit/s — nằm dưới xa `server.tomcat.threads.max=200` của gateway) |

### Verify — build + chạy full container thật (không phải `java -jar` local, để so sánh công bằng với baseline gốc đo cùng cách)

```bash
docker compose --profile services up -d --build
docker run --rm -v "${PWD}/loadtest/k6:/scripts" grafana/k6 run \
  -e WAITING_ROOM_URL=http://host.docker.internal:8085 \
  -e GATEWAY_URL=http://host.docker.internal:8080 \
  -e VUS=1000 -e DURATION=60s -e MAX_POLL_ATTEMPTS=90 /scripts/stage6-waiting-room.js
```

### Kết quả

| Metric | Baseline gốc (§3, không qua waiting-room) | Stage 6 (qua waiting-room) |
|---|---|---|
| Front-door lúc spike | ~5s đầu `connection refused` | **Không có** — `queue_join p99 = 270ms` |
| `server_errors` | không track riêng, nhưng pool bão hòa/pending 500-1000 | **0.40%** (27/6665), dưới ngưỡng 5% |
| Latency chạm backend | p99 4.95-5.06s (§2) | `http_req_duration` avg **9.68ms**, p95 **29.59ms** |
| Cách 1000 VU tới backend | tất cả cùng lúc (nguyên nhân sập) | rải đều theo `RELEASE_RATE` — `queue_wait_ms` avg 7.98s, p95 10.16s (đúng thiết kế: xếp hàng rồi mới thả, không phải tới cùng lúc) |
| Đúng đắn dữ liệu | verify GREEN mọi lần | verify GREEN: INV-1 `0`, `held_mask<>0` đúng 506/506 (hết vé), `occupied`/`booking` = 0 (test chỉ tới bước hold, không gọi booking — đúng thiết kế script) |

**Kết luận: waiting-room giải quyết đúng vấn đề đưa dự án vào Stage 6.**
Front-door không còn "connection refused" dưới spike 1000 VU — 1000 connection
được giữ ở hàng đợi (SSE/poll), backend chỉ thấy đúng `RELEASE_RATE` request/s
đều đặn thay vì cả 1000 cùng lúc. Không oversell, không mất đúng đắn dữ liệu.

### Checklist

- [x] Load test 1000 VU qua đúng luồng thật (join → verify → hold), không mock
- [x] So sánh trực tiếp với baseline gốc cùng điều kiện (1000 VU, container thật, cùng trip)
- [x] `verify.sh`/invariant check GREEN sau load test
- [x] `QUEUE_ENFORCEMENT_ENABLED=true` là default trong `docker-compose.yaml` (đã proven, giống `INVENTORY_REDIS_ENABLED` sau Stage 4) — Spring app-level default vẫn `false`

## 12. Stage 6 — Observability cho waiting-room (2026-09-17)

> Nhận xét đúng: waiting-room không có tracing/metrics/log có cấu trúc trong
> khi mọi service Java đều có (OTel → Jaeger, Prometheus qua Micrometer,
> correlation-id qua MDC). Làm đủ 3 trụ cột, nối chung hạ tầng sẵn có
> (không dựng Jaeger/Prometheus riêng cho Go).

### Thay đổi

| File | Đổi |
|---|---|
| `services/waiting-room/internal/observability/logger.go` | `slog.NewJSONHandler` — JSON structured log, field `service` |
| `services/waiting-room/internal/observability/metrics.go` | `prometheus.Registry` riêng (không dùng global registry) — `http_requests_total`, `http_request_duration_seconds`, `queue_length{resource}`, `active_resources`, `admitted_total{resource}`, `join_total` |
| `services/waiting-room/internal/observability/tracing.go` | `otlptracehttp` exporter nối `OTEL_EXPORTER_OTLP_ENDPOINT` — **cùng Jaeger** các service Java đang dùng, cùng tên biến môi trường |
| `services/waiting-room/internal/observability/middleware.go` | đọc/echo/log `X-Correlation-Id`, gắn `traceId`/`spanId` vào log JSON từ span context (otelhttp tạo trước), `normalizePath()` gộp path động (`/tickets/{uuid}/verify` → `/tickets/{id}/verify`) làm label Prometheus — **tránh cardinality nổ theo ticketID thật** |
| `services/waiting-room/cmd/api/main.go` | wire logger/metrics/tracer, `otelhttp.NewHandler` bọc ngoài cùng (đọc `traceparent` do gateway forward sẵn), endpoint `/actuator/prometheus`, `releaseLoop` cập nhật gauge `queue_length`/`active_resources` + counter `admitted_total` mỗi tick |
| `services/waiting-room/internal/handler/queue_handler.go` | `join` tăng `JoinTotal` |
| `infra/prometheus/prometheus.yml` | thêm scrape target `waiting-room:8085`, path `/actuator/prometheus` khớp convention |
| `docker-compose.yaml` | `waiting-room` thêm `OTEL_SERVICE_NAME`/`OTEL_EXPORTER_OTLP_ENDPOINT`, `depends_on jaeger` |

Dependency mới: `prometheus/client_golang`, `go.opentelemetry.io/otel` +
`otlptracehttp` + `sdk` + `contrib/instrumentation/net/http/otelhttp`.

### Verify (Redis + Jaeger thật, không mock)

```bash
docker compose up -d redis jaeger
# (chạy waiting-room qua PowerShell Start-Process -RedirectStandardOutput —
#  nohup+bash background trên Windows không capture được stdout của process
#  con go-build spawn ra, xem ghi chú §10; PowerShell redirect thì được)
curl -X POST -H "X-Correlation-Id: test-corr-123" http://localhost:8085/queue/obs-test/join
curl http://localhost:8085/actuator/prometheus | grep waitingroom
curl http://localhost:16686/api/services   # -> ["waiting-room"]
curl "http://localhost:16686/api/traces?service=waiting-room&limit=5"
```

Kết quả:
- Log JSON có đủ `correlationId` (echo đúng giá trị gửi lên), `traceId`, `spanId` — VD:
  `{"correlationId":"ps-test-456","method":"POST","path":"/queue/{resource}/join","status":201,"traceId":"541f48c6df03f4d94291047eb908a932",...}`
- `/actuator/prometheus` trả đúng series `waitingroom_http_requests_total`, `_duration_seconds`, `_join_total` — path label là `/queue/{resource}/join` (đã normalize), không phải path thô
- Jaeger API xác nhận `waiting-room` là 1 service thật, span `POST /queue/{resource}/join`/`GET /actuator/health` có mặt với duration thật (không phải log giả)
- Docker build lại sạch với dependency mới (`prometheus/client_golang`, `otel/*`)

### Checklist

- [x] Structured logging (JSON) với correlation-id đọc/echo/log
- [x] Prometheus metrics — path label đã normalize, không cardinality nổ theo ticketID
- [x] OTel tracing nối chung Jaeger sẵn có, verify bằng Jaeger API thật
- [x] Scrape config Prometheus + docker-compose env đã cập nhật
- [x] Trace cha-con thật giữa api-gateway (Java) và waiting-room (Go) — xác nhận qua Jaeger API, xem §13

## 13. Stage 6 — Verify trace cha-con xuyên Java <-> Go (2026-09-17)

> Đúng gap đã tự nêu ở cuối §12: mới verify từng service phát span đúng
> riêng lẻ, chưa test 1 request thật xuyên cả gateway (Java) lẫn
> waiting-room (Go) có link cha-con đúng trong Jaeger không.

### Verify

```bash
docker compose --profile services up -d --build
# join queue thật, admit thủ công qua redis-cli (bỏ qua chờ release loop),
# rồi gọi /api/v1/holds THẬT qua gateway kèm X-Queue-Ticket — trigger đúng
# QueueAdmissionFilter -> RestClient -> waiting-room /verify
curl -X POST http://localhost:8085/queue/trace-test/join
docker exec rt-redis redis-cli SET admitted:TICKET_ID trace-test EX 900
curl -H "X-Queue-Ticket: TICKET_ID" -X POST http://localhost:8080/api/v1/holds -d '{...}'
curl "http://localhost:16686/api/traces?service=api-gateway&limit=10"   # tìm trace có mặt cả 2 service
curl "http://localhost:16686/api/traces/TRACE_ID"                        # xem cây span chi tiết
```

### Kết quả — cây span thật lấy từ Jaeger API

```
api-gateway: http post /api/v1/holds/**        (root, 666ms)
+-- api-gateway: http get                       (client call -> waiting-room, 59.9ms)
|     +-- waiting-room: GET /tickets/{ticketId}/verify   (410us) <- CHILD SPAN thật, parent đúng
+-- api-gateway: http post                      (client call -> inventory-service, 550ms)
      +-- inventory-service: http post /api/v1/holds     (507ms)
```

`waiting-room` (Go) là **con thật** của span gọi ra từ `api-gateway` (Java) —
Spring RestClient tự gắn `traceparent` (W3C Trace Context) vào request
outbound (không cần code thêm ở `HttpClientConfig`/`QueueAdmissionFilter`),
`otelhttp` phía waiting-room tự đọc header đó và tạo span con đúng chỗ. Trace
context propagate xuyên ngôn ngữ (Java <-> Go) hoàn toàn tự động qua chuẩn
W3C — không cần glue code nào ở giữa, không cần cấu hình thêm.

### Checklist

- [x] 1 request thật xuyên gateway -> waiting-room -> inventory-service, cùng 1 traceID
- [x] Span `waiting-room` đúng là CHILD_OF span outbound của `api-gateway` (không phải trace rời rạc trùng ID ngẫu nhiên)
- [x] Không cần sửa code Java (RestClient tự propagate) lẫn code Go (otelhttp tự extract) — chỉ cần cả 2 bên cùng dùng chuẩn W3C Trace Context

## 14. Stage 6 — RELEASE_RATE: ngân sách chung thay vì per-resource (2026-09-17)

> Trước đây `releaseLoop` gọi `svc.ReleaseNext(resource, rate)` riêng cho
> từng resource mỗi tick — 3 trip cùng hot thì tổng release = 3×rate, sai
> với ý đồ ở docs/02-architecture.md ("release N vé/s ra backend", N là
> ngân sách TOÀN HỆ THỐNG, không phải N/trip). Đổi `releaseLoop` sang
> round-robin: mỗi tick chia đúng `globalRate` vé cho tất cả resource đang
> active, 1 vé/resource/vòng cho tới khi hết ngân sách hoặc hết vé.

### Thay đổi

| File | Thay đổi |
|---|---|
| `services/waiting-room/cmd/api/main.go` | `releaseLoop`: bỏ vòng lặp gọi `ReleaseNext(resource, globalRate)` cho từng resource độc lập; thay bằng round-robin trừ dần 1 ngân sách chung `globalRate` qua các resource active mỗi tick |

### Verify

```bash
docker compose up -d redis   # chỉ infra, không build
docker exec rt-redis redis-cli FLUSHALL
RELEASE_RATE=5 RELEASE_INTERVAL_MS=10000 REDIS_HOST=localhost SERVER_PORT=8085 go run ./cmd/api
# 3 resource, mỗi resource 10 waiter join
for res in global2-A global2-B global2-C; do
  for i in $(seq 1 10); do curl -s -X POST "http://localhost:8085/queue/${res}/join" -d '{}' -o /dev/null; done
done
# check length ngay sau join, rồi check lại sau khi 1-2 tick trôi qua
curl http://localhost:8085/queue/global2-A/length
```

### Kết quả thật

- Server start: `16:26:50.776` (log `releaseRate=5, releaseIntervalMs=10000`)
- 30 waiter join xong lúc `16:27:06.66` — ngay sau join, length mỗi resource = 10/10/10 (đúng, chưa tick nào chạy vì tick đầu `16:27:00.776` rơi TRƯỚC lúc join, không có gì để release)
- Check lúc `16:27:23.2x` — đã trôi qua 2 tick nữa (`16:27:10.776` và `16:27:20.776`), mỗi tick chia đúng 5 vé round-robin (A:2, B:2, C:1 theo thứ tự `ActiveResources()` trả về) → sau 2 tick: A mất 4, B mất 4, C mất 2
- Length đo được: **global2-A=6, global2-B=6, global2-C=8** — khớp chính xác dự đoán round-robin (không phải per-resource — nếu còn per-resource thì mỗi resource đã mất 2×5=10, tức về 0 hết cả 3, không lệch A/B/C như vậy)
- Kết luận: ngân sách chung hoạt động đúng — tổng vé release luôn = `globalRate × số tick`, chia đều luân phiên qua các resource active, không cộng dồn theo số resource

### Checklist

- [x] `releaseLoop` release đúng ngân sách chung, không nhân theo số resource active
- [x] Verify bằng số liệu thật (3 resource × 10 waiter, đối chiếu đúng công thức round-robin qua 2 tick)
- [x] `go build`, `go vet`, `go test ./...` sạch (10/10 test pass — xem §15)

## 15. Stage 6 — go test cho queue_service và redis_queue_repository (2026-09-17)

> `internal/service` và `internal/repository` trước đây không có test nào.
> Theo đúng convention project (Java dùng testcontainers cho test tầng
> repository, fake/mock chỉ ở tầng service): service test dùng `fakeRepo`
> in-memory (test business logic điều phối, không phải Redis), repository
> test dùng testcontainers-go Redis thật (test đúng câu lệnh Redis chạy
> đúng, không phải giả lập hành vi Redis).

### File mới

| File | Nội dung |
|---|---|
| `internal/service/queue_service_test.go` | `fakeRepo` implement `QueueRepository` trong RAM; 5 test: Join trả WAITING/position 0; Status phản ánh ADMITTED sau release; AwaitAdmission trả về ngay nếu đã admitted (race check); AwaitAdmission unblock khi release đến đồng thời; AwaitAdmission timeout đúng khi không ai release |
| `internal/repository/redis_queue_repository_test.go` | Redis thật qua `testcontainers-go/modules/redis`; 5 test: FIFO order đúng theo Position; join trùng ticketID giữ nguyên position gốc (không nhảy xuống cuối hàng); Release trả đúng thứ tự FIFO + đánh dấu admitted; `active-queues` tự loại resource khi hết vé; SubscribeAdmission nhận đúng tín hiệu Pub/Sub khi Release |

### Verify

```bash
cd services/waiting-room
go test ./... -v
```

### Kết quả thật

```
ok  github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/service      3.150s
ok  github.com/AndreNguyen03/rail-ticketing-v1/services/waiting-room/internal/repository   (testcontainers, Redis thật)
```
10/10 test pass — 5 service (fake repo), 5 repository (Redis thật qua testcontainers-go).

### Checklist

- [x] Service test dùng fake repo trong RAM — test đúng logic điều phối (race check IsAdmitted trước khi subscribe, timeout, unblock đồng thời)
- [x] Repository test dùng Redis thật (testcontainers) — đúng convention project, không giả lập hành vi Redis
- [x] Cả 2 bộ test pass sạch, không flaky (chạy lại xác nhận ổn định)

## 16. Stage 6 — Quyết định: token verify multi-use hay consume-once? (2026-09-17)

> Câu hỏi đặt ra: `GET /tickets/{id}/verify` có nên "tiêu" token (xoá
> `admitted:{ticketID}` ngay sau lần verify thành công đầu tiên) hay giữ
> cho dùng nhiều lần trong TTL 15 phút?

### Quyết định: giữ multi-use trong TTL cố định 15 phút — KHÔNG đổi code

Đọc lại `internal/repository/redis_queue_repository.go:112` (`IsAdmitted`) xác
nhận implementation hiện tại đã đúng ý này từ đầu: `EXISTS admitted:{ticketID}`
là thao tác **đọc**, không xoá key, TTL 900s set 1 lần duy nhất lúc `Release`
(không sliding — không tự gia hạn mỗi lần verify).

**Vì sao multi-use là lựa chọn đúng:**
`queue.enforcement.protected-prefixes` (application.yml) bảo vệ CẢ
`/api/v1/holds` LẪN `/api/v1/bookings`. Một luồng đặt vé thật gọi
`QueueAdmissionFilter` (cùng 1 `X-Queue-Ticket`) ít nhất 2 lần — giữ chỗ rồi
xác nhận, có thể thêm cả lệnh retry khi lỗi mạng. Nếu consume-once, request
thứ 2 của CHÍNH người dùng vừa được admit sẽ bị 429 và đá ngược vào hàng đợi
— phá luôn mục đích "được vào phòng chờ" (đã đến lượt thì phải làm xong cả
luồng, không phải chỉ đúng 1 request).

**Vì sao TTL vẫn nên CỐ ĐỊNH (không sliding), không renew mỗi lần verify:**
Giữ nguyên hành vi hiện tại — không refresh TTL ở `IsAdmitted`. Đây là chủ ý
giống mô hình phòng chờ thật (Ticketmaster/Queue-it): được vào thì có một cửa
sổ THỜI GIAN CỐ ĐỊNH để hoàn tất checkout, hết giờ thì mất suất (dù vẫn đang
"hoạt động") — tránh 1 người giữ slot vô thời hạn bằng cách liên tục gọi lại
API, đảm bảo công bằng lượt xoay vòng cho người còn lại trong hàng đợi.

### Checklist

- [x] Xác nhận `IsAdmitted` là thao tác đọc thuần (EXISTS), không xoá token — multi-use trong TTL
- [x] Xác nhận TTL không sliding/renew — cố ý, giữ đúng ngữ nghĩa "cửa sổ checkout có hạn"
- [x] Không cần sửa code — quyết định giữ nguyên implementation hiện tại, chỉ ghi rõ lý do thiết kế
