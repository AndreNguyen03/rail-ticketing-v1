# 05 — Build Progression

> Kiến trúc ở [02](02-architecture.md) là **đích đến**, không phải điểm xuất phát.
>
> Nhưng "điểm xuất phát" ở đây **vẫn là microservices** — nhiều service, deploy riêng,
> database riêng, gọi nhau qua mạng. Thứ bị hoãn là **kỹ thuật bên trong và giữa** chúng,
> không phải bản thân topology.

---

## 1. Nguyên tắc chi phối

> ### Topology đầy đủ ngay từ đầu. Kỹ thuật thêm dần theo số đo được.

Hai vế, đừng lẫn:

| Có ngay từ commit đầu | Hoãn tới khi đo được nhu cầu |
|---|---|
| Nhiều service, deploy độc lập | Redis, cache |
| Database riêng mỗi service | Kafka, event, saga |
| Gọi nhau qua HTTP thật | Circuit breaker, retry, bulkhead |
| API gateway | Phòng chờ ảo |
| Bitmask tồn kho | Đối soát |
| Idempotency key | Service mesh, K8s |

Lý do chia như vậy: **mục tiêu học là hệ phân tán.** Nếu bắt đầu bằng monolith rồi tách sau,
bạn dành 6 tuần đầu học Spring Boot — thứ bạn đã biết. Bắt đầu bằng nhiều service, bạn
va ngay vào lời gọi mạng thất bại, không có transaction xuyên service, và log nằm ở 3 chỗ.
**Đó mới là thứ đáng học.**

Nhưng đừng nhét Redis, Kafka, mesh vào cùng lúc — khi đó bạn không biết vấn đề nào do đâu.

### Thứ không đổi qua mọi giai đoạn: API công khai

```
Client  ──────────────────────────────────────────────▶  gateway
                                                            │
   GĐ 0:  3 service · REST đồng bộ · PostgreSQL             │
   GĐ 3:  3 service · + timeout, retry, idempotency         │  hợp đồng
   GĐ 4:  3 service · + Redis trong inventory               │  KHÔNG ĐỔI
   GĐ 5:  5 service · + Kafka, outbox, saga                 │
   GĐ 7:  12 service · + phòng chờ, K8s                     │
```

Client không biết và không cần biết bên trong đổi gì. Đó là lý do **thiết kế API trước**.

---

## 2. Giai đoạn 0 — Bộ khung microservices, CRUD thuần

**Mục tiêu: đặt được một vé, đi qua 3 service thật, không có kỹ thuật gì cả.**

### Ba service + gateway

```
                    ┌──────────────┐
   Client ─────────▶│ api-gateway  │   định tuyến, không gì khác
                    └──────┬───────┘
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐
  │  schedule   │  │  inventory   │  │   booking   │
  │   service   │  │   service    │◀─│   service   │
  └──────┬──────┘  └──────┬───────┘  └──────┬──────┘
         │                │                 │
     scheduledb      inventorydb        bookingdb      ← 3 database RIÊNG
```

| Service | Sở hữu | Endpoint |
|---|---|---|
| `schedule-service` | Ga, tàu, chuyến, toa, chỗ, giá | `GET /trips`, `GET /trips/{id}` |
| `inventory-service` | Tồn kho theo chặng, hold | `GET /availability`, `POST /holds`, `DELETE /holds/{id}` |
| `booking-service` | Đơn, vé, thanh toán (mock) | `POST /bookings`, `POST /bookings/{id}/confirm`, `GET /bookings/{id}` |
| `api-gateway` | Định tuyến | — |

Tại sao đúng ba cái này: chúng là **ba plane khác nhau** ở [02 §3](02-architecture.md) — Catalog
(đọc nhiều, dữ liệu lạnh), Contention (tranh chấp), và điều phối. Ranh giới này được quyết
định bởi hình dạng tải, và hình dạng tải **không đổi** — nên đây là ba ranh giới ít rủi ro nhất.

### Bề mặt API

| Method | Path | Service | Việc |
|---|---|---|---|
| `GET` | `/api/v1/trips?from=HN&to=SG&date=2026-02-14` | schedule | Tìm chuyến |
| `GET` | `/api/v1/trips/{tripId}` | schedule | Chi tiết + sơ đồ toa |
| `GET` | `/api/v1/trips/{tripId}/availability?from=0&to=17` | inventory | Chỗ trống **cho hành trình này** |
| `POST` | `/api/v1/holds` | inventory | Giữ chỗ, TTL 15 phút |
| `DELETE` | `/api/v1/holds/{holdId}` | inventory | Nhả chỗ |
| `POST` | `/api/v1/bookings` | booking | Tạo đơn từ hold |
| `POST` | `/api/v1/bookings/{id}/confirm` | booking | Xác nhận, thanh toán mock |
| `GET` | `/api/v1/bookings/{id}` | booking | Xem đơn |

Tám endpoint, ba service. Hết.

### Cố tình KHÔNG có

```
✗ Redis                    ✗ Circuit breaker        ✗ Phòng chờ ảo
✗ Kafka / event            ✗ Retry / backoff        ✗ Quota chống đầu cơ
✗ Saga framework           ✗ Service discovery      ✗ Keycloak
✗ Cache bất kỳ loại nào    ✗ Kubernetes             ✗ Distributed tracing
```

Gọi service khác bằng `RestClient` với URL từ biến môi trường. `docker-compose` lo DNS.
Thanh toán là một `if (random() < 0.9) success else fail` trong `booking-service`.
Xác thực là header `X-Identity` không kiểm gì.

### Điểm tinh tế: không có transaction xuyên service — xử lý thô thế nào

Đây là vấn đề bạn va phải **ngay ngày đầu** khi chọn microservices trước, và là lý do
chính đáng để chọn nó.

```
booking-service nhận POST /bookings
  1. gọi inventory  POST /holds          → OK, có holdId
  2. ghi bookingdb  INSERT booking       → LỖI (vi phạm ràng buộc, DB rớt, pod chết…)
  3. gọi inventory  DELETE /holds/{id}   → bồi hoàn thô
```

Lỗ hổng rõ ràng: **pod chết giữa bước 2 và 3** ⇒ hold mồ côi, chỗ bị khoá mà không ai dùng.

**Và ở giai đoạn 0 điều đó chấp nhận được — vì hold có TTL.**

> **TTL của hold chính là thứ cho phép bồi hoàn ngây thơ hoạt động ở giai đoạn 0.**
> Hold mồ côi tự chết sau 15 phút. Bạn mất tối đa 15 phút × vài chỗ. Không cần saga,
> không cần outbox, không cần Kafka.
>
> Đây là ví dụ đẹp của việc **thiết kế nghiệp vụ che cho hạ tầng chưa có**. Ghi nhận nó,
> vì ở giai đoạn 5 bạn sẽ thay bằng saga thật và hiểu chính xác saga mua cho bạn cái gì:
> giảm cửa sổ mất mát từ 15 phút xuống vài giây, và biến "mất im lặng" thành "có bản ghi".

### Quyết định kỹ thuật trong `inventory-service`

| Hạng mục | Chọn | Vì sao |
|---|---|---|
| Tồn kho | `SELECT FOR UPDATE SKIP LOCKED` | Đúng tuyệt đối, PostgreSQL giữ bất biến hộ |
| Chống chồng chặng | `EXCLUDE USING gist` | Không thể lách, kể cả code có bug |
| Hết hạn hold | `@Scheduled` quét mỗi 30 giây | Đủ. Hẹn giờ phân tán là bài toán sau |
| Chọn chỗ | Quét đơn giản, lấy chỗ đầu tiên trống | Thuật toán ưu tiên là chuyện của [03 §6](03-segment-inventory-engine.md) |

### Bốn thứ phải làm đúng ngay — rẻ lúc này, rất đắt lúc sau

| Việc | Vì sao không hoãn được |
|---|---|
| **Bitmask cho chặng** | Đổi mô hình tồn kho sau = viết lại `inventory-service` ([03 §2](03-segment-inventory-engine.md)) |
| **Idempotency key** trên mọi POST ghi | Nhét vào sau = sửa mọi client và mọi handler. Và bạn **sẽ** cần nó ở GĐ 3 khi thêm retry |
| **Quy ước API** — đơn vị trong tên trường, ngày lịch vs thời điểm, lỗi RFC 9457 | Đổi contract sau khi có client rất đau |
| **Database riêng thật sự** — mỗi service một DB, một user, không query chéo | Đây là ranh giới microservices **thật**. Chung DB = monolith đội lốt |

> Mục cuối là thứ hay bị gian lận nhất. "Tạm thời chung một Postgres cho tiện" rồi một
> ngày bạn `JOIN` từ `booking` sang `inventory` và mọi tính độc lập biến mất.
> Dùng **user riêng, quyền riêng** — để việc gian lận đó bị database từ chối.

### Xong khi nào

- [ ] Đặt được vé HN→SG qua `curl`, đi qua đủ 3 service
- [ ] Đặt trùng ghế trùng chặng bị từ chối
- [ ] Hold hết hạn thì chỗ quay lại kho
- [ ] Tắt `inventory-service` ⇒ `booking` trả lỗi rõ ràng, **không treo vô hạn**
- [ ] Ba database riêng, thử `JOIN` chéo bị từ chối quyền
- [ ] `docker compose up` dựng 4 container + 3 DB

**Thời lượng: ~3 tuần.** Lâu hơn monolith một tuần — đó là học phí cho việc vào đúng topology.

---

## 3. Đánh đổi của việc chọn microservices trước

Nói thẳng cả hai mặt:

| | Microservices trước ⭐ | Monolith rồi tách |
|---|---|---|
| Tới vé đầu tiên | ~3 tuần | ~2 tuần |
| Va vào bài toán phân tán | **Ngày đầu** | Tuần thứ 8 |
| Ranh giới service | Cố định sớm — **sai thì sửa đắt** | Sửa rẻ (đổi package) |
| Debug | Khó từ đầu, 3 log stream | Dễ lúc đầu |
| Không có transaction chung | **Đối mặt ngay** — và TTL che cho | `@Transactional` lo hết |
| Học được gì trong 8 tuần đầu | Hệ phân tán | Spring Boot (thứ bạn đã biết) |
| Rủi ro lớn nhất | Chia sai ranh giới | Không bao giờ tách nổi |

**Giảm thiểu rủi ro "chia sai ranh giới":** ba service ở GĐ 0 được chọn theo **hình dạng tải**
([02 §3](02-architecture.md)), không theo thực thể. Hình dạng tải là thứ ổn định nhất trong
hệ này — catalog sẽ luôn đọc-nhiều-ghi-ít, tồn kho sẽ luôn là điểm tranh chấp. Ba ranh giới
đó gần như chắc chắn đúng.

Những ranh giới **có thể** sai và bạn sẽ sửa sau: `fare` nên tách khỏi `schedule` không?
`quota` nên nằm trong `booking` không? Cứ để chúng trong service lớn hơn ở GĐ 0, tách khi đo được.

---

## 4. Giai đoạn 1 — Lưới an toàn, trước khi tối ưu bất cứ thứ gì

**Mục tiêu: có tín hiệu đỏ/xanh tự động.**

| Thành phần | Nội dung |
|---|---|
| **4 truy vấn bất biến** | [03 §12](03-segment-inventory-engine.md) — chạy trên `inventorydb` + `bookingdb` |
| **`make verify`** | Exit code khác 0 nếu truy vấn nào trả về dòng |
| **Load test k6** | 1.000 VU tranh 408 chỗ trên một chuyến |
| **CI** | Load test + verify chạy mỗi PR |

Bạn sắp thay đổi `inventory-service` **bốn lần**. Không có lưới này, mỗi lần đổi là một
lần cầu nguyện.

Lưu ý riêng cho kiến trúc nhiều service: truy vấn bất biến giờ phải chạy **xuyên hai database**
(`booking` giữ vé, `inventory` giữ mask). Không `JOIN` được ⇒ script verify đọc cả hai rồi
so trong bộ nhớ. **Đó chính là bài học đầu tiên về nhất quán phân tán**, và bạn gặp nó ở tuần thứ 4.

- [ ] `make verify` bắt được lỗi khi bạn **cố ý** phá — bỏ `FOR UPDATE` đi và xem nó đỏ
- [ ] CI chặn merge khi verify đỏ

**Thời lượng: ~5 ngày.**

---

## 5. Giai đoạn 2 — Đo, đừng đoán

**Mục tiêu: con số gốc, và biết nút thắt nằm ở đâu.**

| # | Làm gì | Sẽ thấy gì |
|---|---|---|
| **A** | Tăng VU: 100 → 500 → 2.000 → 10.000 | Throughput chạm trần (dự kiến 200–500/giây/chuyến) |
| **B** | **Tăng instance `inventory-service`: 1 → 2 → 4 → 8 → 16** | ⭐ **Đường cong USL** — đỉnh rồi **tụt** |
| **C** | 1 chuyến vs 200 chuyến, cùng tổng RPS | Tranh chấp biến mất khi trải ra |
| **D** | Đo **thời gian mạng** giữa các service | Bao nhiêu % độ trễ là hop mạng, không phải công việc thật |

Thí nghiệm D chỉ có khi bạn chọn microservices trước — và nó dạy một thứ quan trọng:
**mỗi hop mạng là 1–3 ms bạn không bao giờ lấy lại được.** Đó là cái giá của tính độc lập,
và biết con số cụ thể giúp bạn không tách service bừa bãi về sau.

```
baseline.md
├── Throughput đỉnh:        ??? hold/giây/chuyến
├── Số instance tối ưu:     ???  (thấp đến bất ngờ)
├── p50 / p95 / p99:        ???
├── Thời gian đi đâu:       ???% chờ khoá · ???% query · ???% hop mạng
└── Nút thắt:               ???
```

**File này là giấy phép cho các giai đoạn sau.**

**Thời lượng: ~1 tuần** — phần lớn là chờ test chạy.

---

## 6. Giai đoạn 3 — Làm cứng các lời gọi đã có

**Điều kiện vào: bạn đã thấy chúng hỏng thế nào ở GĐ 2.**

Ở GĐ 0 bạn gọi service khác bằng `RestClient` trần. Giờ vá các chế độ hỏng **đã quan sát được**:

| Thêm | Vá vấn đề gì |
|---|---|
| **Timeout giảm dần vào trong** | gateway 8s > booking 5s > inventory 800ms > DB 200ms |
| **Retry + jitter, chỉ cho thao tác idempotent** | Lỗi mạng thoáng qua. **Cần idempotency key từ GĐ 0** |
| **Circuit breaker** | `inventory` chậm không được kéo sập `booking` |
| **Bulkhead** | Pool riêng cho lời gọi ra ngoài |
| **Correlation ID xuyên 3 service** | Giờ mới thật sự cần — log nằm ở 3 chỗ |

Đây là giai đoạn biến "3 service gọi nhau" thành "hệ phân tán chịu lỗi". Nó ngắn nhưng
đổi hẳn chất lượng.

> Chú ý thứ tự: **retry đến sau idempotency key**. Retry một thao tác không idempotent
> là cách tạo ra vé trùng. Đó là lý do idempotency key nằm trong danh sách "làm đúng ngay" ở GĐ 0.

- [ ] Tắt `inventory-service` ⇒ `booking` mở circuit sau vài lần lỗi, trả lỗi nhanh
- [ ] Thêm 500ms độ trễ nhân tạo ⇒ timeout bắt đúng, không treo
- [ ] Một `correlation-id` truy được cả 3 service trong log

**Thời lượng: ~1 tuần.**

---

## 7. Giai đoạn 4 — Tranh chấp: Redis + đối soát

**Điều kiện vào: `baseline.md` chứng minh PostgreSQL là nút thắt.**

| Thêm | Vì sao |
|---|---|
| Redis + script Lua trong `inventory-service` | [04 §4](04-contention-strategies.md) |
| PostgreSQL vẫn là **nguồn sự thật** | Ràng buộc `EXCLUDE` giữ nguyên, không bỏ |
| **Đối soát** | ⚠️ **Không phải tuỳ chọn** |

Bài học then chốt: **mọi giải pháp tạo ra một lớp vấn đề mới.**

```
Thêm Redis  ─┬─▶ nhanh hơn ~50 lần                      ✅
             ├─▶ hai kho dữ liệu ⇒ chúng SẼ lệch nhau  ❌ mới
             ├─▶ Redis chết ⇒ mất hold                 ❌ mới
             └─▶ bit held rò rỉ âm thầm                ❌ mới
```

Đối soát và hết-hạn-lười ([04 §9](04-contention-strategies.md)) viết **cùng giai đoạn này**.

Điểm hay: thay đổi này nằm **trọn trong `inventory-service`**. `booking-service` không biết
gì, API không đổi. Đó là phần thưởng cho việc đã tách service đúng từ GĐ 0.

```bash
docker kill redis && sleep 5 && docker start redis
make verify
```

- [ ] Throughput tăng ≥20 lần so với baseline
- [ ] `make verify` vẫn xanh
- [ ] Giết Redis giữa test ⇒ **không mất vé đã thanh toán**
- [ ] Đối soát tự sửa lệch trong dưới 2 phút

**Thời lượng: ~2 tuần.**

---

## 8. Giai đoạn 5 — Bất đồng bộ và saga thật

**Điều kiện vào: một lời gọi đồng bộ đang bắt người dùng chờ vô ích.**

Thanh toán là ứng viên rõ nhất — chờ webhook 3–30 giây. Đây cũng là lúc thay **bồi hoàn ngây thơ**
ở GĐ 0 bằng saga thật.

| Thêm | Vì sao lúc này mới thêm |
|---|---|
| Kafka | Giờ mới có luồng thật sự bất đồng bộ |
| **Outbox pattern** | Ghi DB + phát event phải nguyên tử |
| **Saga + bồi hoàn** | Thay try/catch + REST call của GĐ 0 |
| `payment-service` tách ra | Chờ bên thứ ba, vòng đời riêng |
| `ticket-service`, `notification-service` | Consumer của event, tách tự nhiên |

**Saga mua cho bạn cái gì so với GĐ 0** — giờ bạn trả lời được chính xác:

| | GĐ 0 (bồi hoàn thô + TTL) | GĐ 5 (saga) |
|---|---|---|
| Pod chết giữa chừng | Hold mồ côi 15 phút | Saga khôi phục trong vài giây |
| Có bản ghi không? | Không — mất im lặng | Có, trạng thái saga bền vững |
| Bồi hoàn nhiều bước | Không làm được | Làm được, có thứ tự |
| Độ phức tạp | Rất thấp | Cao |

```
Thanh toán thất bại              ⇒ nhả chỗ
Thanh toán timeout               ⇒ đối soát với cổng, hoàn nếu đã trừ
Xuất vé lỗi sau khi trả tiền     ⇒ retry, KHÔNG nhả chỗ, cảnh báo
Hold hết hạn giữa lúc thanh toán ⇒ ⚠️ khó nhất — [04 §8](04-contention-strategies.md)
```

Đây là giai đoạn dạy nhiều nhất về microservices. Đừng vội qua.

**Thời lượng: ~3 tuần.**

---

## 9. Giai đoạn 6+ — Theo nhu cầu đo được

| GĐ | Thêm | Điều kiện vào |
|---|---|---|
| **6** | Phòng chờ ảo (`waiting-room`, Go) | Load test cho thấy cửa trước sụp ở N req/giây |
| **7** | `quota-service` chống đầu cơ | Mô phỏng được hành vi cò vé |
| **8** | Keycloak, xác thực thật | Chuẩn bị người dùng thật |
| **9** | Tách `fare`, `refund`, đổi vé | Hoàn thiện vòng đời |
| **10** | Kubernetes | Compose bắt đầu vướng với ≥6 service |
| **11** | Observability đầy đủ, chaos | Đủ service để sự cố khó truy nguyên |

> **Dừng ở đâu cũng được.** Hết GĐ 5 là đã có hệ phân tán thật với saga, event, tranh chấp
> đã giải, resilience, và lưới kiểm chứng — nhiều hơn phần lớn dự án học microservices từng đi tới.

---

## 10. Tổng kết

| GĐ | Chủ đề | Thêm gì | Service | Thời lượng |
|---|---|---|---|---|
| **0** | Bộ khung CRUD | Spring Boot × 3 + gateway + 3 PostgreSQL | 3+1 | 3 tuần |
| **1** | Lưới an toàn | 4 bất biến + k6 + CI | 3+1 | 5 ngày |
| **2** | Đo | `baseline.md`, USL, chi phí hop mạng | 3+1 | 1 tuần |
| **3** | Chịu lỗi | timeout, retry, circuit breaker, correlation ID | 3+1 | 1 tuần |
| **4** | Tranh chấp | Redis Lua + đối soát | 3+1 | 2 tuần |
| **5** | Bất đồng bộ | Kafka + outbox + saga | 5+1 | 3 tuần |
| 6–11 | Theo nhu cầu | phòng chờ, quota, K8s, chaos | tới 12 | tuỳ |

**Tới hết giai đoạn 5: ~11 tuần**, và bạn đã chạm mọi bài học cốt lõi.

---

## 11. Bảy cách làm hỏng lộ trình này

| Sai lầm | Hậu quả |
|---|---|
| Dựng đủ 12 service ở GĐ 0 | 3 tháng chưa đặt được vé nào. Ba là đủ |
| **Chung một database "cho tiện"** | Monolith đội lốt microservices. Bạn học được **con số không** |
| Thêm Redis trước khi đo | Không biết nó giúp gì, và giờ có 2 kho dữ liệu để đồng bộ |
| Bỏ qua giai đoạn 1 | Mọi thay đổi sau là đoán mò |
| Bỏ qua thí nghiệm B (USL) | Bỏ lỡ bài học đắt nhất, và sẽ tin "thêm pod là nhanh hơn" cả đời |
| Thêm retry trước idempotency key | Tự tạo ra vé trùng |
| Thêm Kafka khi chưa có luồng async thật | Độ phức tạp mà không đổi lấy gì |

> Cái thứ hai nguy hiểm nhất. Nó **trông** giống microservices, chạy được, demo được —
> và không dạy bạn bất cứ điều gì về hệ phân tán, vì mọi bài toán khó đều bị
> `@Transactional` che mất.

---

**Quay lại:** [README](../README.md) · [04 — Contention Strategies](04-contention-strategies.md)
