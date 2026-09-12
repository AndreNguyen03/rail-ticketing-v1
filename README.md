# Rail Ticketing — Hệ thống bán vé tàu Tết

> 500.000 người bấm "Đặt vé" trong cùng một giây. 500 chỗ trên chuyến SE1 ngày 26 Tết.
> Bán đúng 500 vé. Không thừa một vé. Không ai bị trừ tiền nhầm. Không con bot nào ôm hết.
>
> Hệ thống microservices được thiết kế để học **tranh chấp phân tán** — bài toán mà
> thêm server không giải quyết được.

Stack: **Java 21 / Spring Boot 3.3** · **Redis** (hot path tồn kho) · **PostgreSQL** (nguồn sự thật) ·
**Kafka** · **React** (web) · **k3d** (local, $0)

---

## 1. Luận điểm kiến trúc

Đa số hệ thống bán vé được thiết kế cho **quy mô** (scale). Bài toán này không phải quy mô — nó là **tranh chấp** (contention). Hai thứ khác nhau về bản chất và đòi hỏi kiến trúc ngược nhau:

| | Quy mô | Tranh chấp |
|---|---|---|
| Hình dạng tải | 500k request/giây trải trên 500k mục khác nhau | 500k request/giây cho **cùng 500 chỗ** |
| Giải bằng | Sharding, thêm replica, cache | **Không thêm server nào giúp được** |
| Nút thắt | Băng thông, CPU | **Một dòng dữ liệu duy nhất** |
| Sai thì sao | Chậm | **Bán thừa vé — sai về mặt nghiệp vụ** |

Thêm 100 pod cho service đặt vé **làm vấn đề tệ hơn**: 100 tiến trình cùng tranh một hàng trong database, khoá xếp chồng, throughput giảm. Đây là nghịch lý mà chỉ bài toán tranh chấp mới có, và là lý do domain này dạy được thứ mà CRUD không dạy được.

### Bốn đặc tính định hình toàn bộ thiết kế

```
1. TỒN KHO LÀ KHOẢNG, KHÔNG PHẢI BOOLEAN
   Ghế 12A chuyến SE1:
   HN ──── Vinh ──── Huế ──── ĐN ──── NT ──── SG
   │                  │       │               │
   └─ khách A ────────┘       └─ khách B ─────┘
        (không xung đột — cùng một ghế, hai khách)

2. TẢI KHÔNG ĐỀU ĐẾN MỨC PHI LÝ
   360 ngày/năm:  ~50 request/giây
   Giờ mở bán Tết: ~500.000 request trong 60 giây
   Tỉ lệ 10.000:1

3. ĐỐI THỦ CÓ THẬT
   Bot của "cò vé" xoay IP, xoay tài khoản, gọi API trực tiếp.
   Chống bằng hạ tầng (rate limit IP) là thua. Phải chống bằng DANH TÍNH.

4. ĐÚNG/SAI LÀ NHỊ PHÂN VÀ CHỨNG MINH ĐƯỢC
   SELECT count(*) FROM ticket WHERE train='SE1' AND date='2026-02-14';
   Đúng 500. Hoặc bạn sai.
```

### Hệ quả: chia service theo **hình dạng tải**

```
QUEUE PLANE      waiting-room · gateway              ← hấp thụ đợt sóng, xả đều
                          │
CATALOG PLANE    schedule · fare · station           ← đọc 10.000:1, cache mạnh, dữ liệu lạnh
                          │
CONTENTION PLANE inventory · booking · quota         ← ⭐ TRANH CHẤP CAO, nhất quán mạnh
                          │                             phân vùng theo CHUYẾN TÀU
FULFILLMENT      payment · ticket · notification     ← async, chịu được chậm
                          │
RECOVERY         refund · reconciliation             ← chạy nền, sửa sai lệch
```

`inventory-service` là **trái tim và điểm nghẽn**. Mọi quyết định kiến trúc khác tồn tại để bảo vệ nó.

---

## 2. Bài toán trung tâm: tồn kho theo chặng

Một chuyến tàu có các ga $S_0, S_1, \dots, S_n$ và $n$ **chặng** (leg).
Khách đi từ $S_a$ đến $S_b$ chiếm các chặng $L_a, L_{a+1}, \dots, L_{b-1}$.

Biểu diễn bằng **bitmask**:

```
SE1:  HN(0) ── Vinh(1) ── Huế(2) ── ĐN(3) ── NT(4) ── SG(5)
Chặng:     L0        L1        L2       L3       L4        → 5 bit

Khách A: HN → Huế   = L0,L1        = 0b00011
Khách B: ĐN → SG    = L3,L4        = 0b11000
Khách C: Vinh → NT  = L1,L2,L3     = 0b01110

A & B = 0  ⇒ KHÔNG xung đột, dùng chung ghế được
A & C ≠ 0  ⇒ xung đột
```

**Kiểm tra xung đột = một phép AND.** Đặt chỗ = một phép OR. Cả hai đều là lệnh CPU đơn.

### Phát hiện quyết định toàn bộ kiến trúc

Tuyến dài nhất Việt Nam (Hà Nội – Sài Gòn) có ~30 ga ⇒ **29 chặng ⇒ vừa một `int32`**.

Một chuyến tàu ~500 chỗ. Toàn bộ tồn kho của một chuyến:

$$
500 \text{ chỗ} \times 4 \text{ byte} = \mathbf{2\ KB}
$$

**Toàn bộ tồn kho một chuyến tàu nằm gọn trong 2 KB.** Nó vừa một lần ghi Redis, vừa một script Lua nguyên tử, vừa một dòng cache CPU. Đây là chi tiết khiến bài toán tranh chấp cực đoan này trở nên giải được — và là thứ bạn sẽ không nhận ra nếu mô hình hoá tồn kho bằng "một dòng SQL cho mỗi ghế".

Chi tiết đầy đủ: [03 — Segment Inventory Engine](docs/03-segment-inventory-engine.md).

---

## 3. Bốn cách giải tranh chấp — và bạn sẽ thử cả bốn

Đây là bài tập học giá trị nhất của dự án. Mỗi cách **hỏng theo một kiểu khác nhau**, và bạn chỉ thật sự hiểu khi tự thấy nó hỏng.

| Cách | Throughput (1 chuyến) | Hỏng thế nào |
|---|---|---|
| `SELECT FOR UPDATE` trên PostgreSQL | ~200/giây | Khoá xếp hàng, connection pool cạn, cả service treo |
| Optimistic lock + retry | ~800/giây | **Bão retry** — càng tranh chấp càng nhiều retry, throughput sụp |
| **Redis + Lua nguyên tử** | **~25.000/giây** | Nhanh, nhưng **mất bền vững** — Redis chết là mất chỗ đã giữ |
| Single-writer theo chuyến (Kafka partition) | ~8.000/giây | Đúng và có thứ tự, nhưng độ trễ p99 cao |

Thiết kế cuối dùng **Redis Lua + PostgreSQL làm nguồn sự thật + đối soát nền**. Chi tiết và cách đo: [04 — Contention Strategies](docs/04-contention-strategies.md).

---

## 4. Bản đồ service

| Service | Plane | Ngôn ngữ | Datastore | Vì sao tách riêng |
|---|---|---|---|---|
| `api-gateway` | Queue | Java | — | Xác thực, rate limit theo danh tính |
| **`waiting-room`** | Queue | **Go** | Redis | Giữ 500k kết nối chờ — Java tốn RAM gấp 6 lần |
| `schedule-service` | Catalog | Java | PostgreSQL + Redis | Đọc 10.000:1, cache 24h |
| `fare-service` | Catalog | Java | PostgreSQL | Quy tắc giá vé, tính sẵn |
| **`inventory-service`** | Contention | **Java** | **Redis + PostgreSQL** | ⭐ Trái tim. Phân vùng theo chuyến |
| `booking-service` | Contention | Java | PostgreSQL | Saga orchestrator |
| `quota-service` | Contention | Java | Redis + PostgreSQL | Chống đầu cơ theo CCCD |
| `payment-service` | Fulfillment | Java | PostgreSQL | Tích hợp VNPay/MoMo, bất đồng bộ |
| `ticket-service` | Fulfillment | Java | PostgreSQL + S3 | Sinh mã QR, PDF |
| `notification-service` | Fulfillment | Java | PostgreSQL | SMS/Zalo/email |
| `refund-service` | Recovery | Java | PostgreSQL | Saga ngược |
| **`reconciliation`** | Recovery | Java | PostgreSQL | ⭐ Đối soát Redis ↔ PostgreSQL |

12 service. Ít hơn fitness (18) nhưng **mật độ bài học cao hơn** — vì mọi service đều xoay quanh một bài toán khó duy nhất thay vì trải ra nhiều miền.

---

## 5. Dữ liệu — sinh bằng code, không cần curate

Đây là lý do domain này nhẹ hơn fitness hẳn một bậc:

| Dữ liệu | Cách có | Công sức |
|---|---|---|
| ~20 ga chính tuyến Bắc–Nam | Danh sách công khai, gõ tay | **15 phút** |
| Sơ đồ toa (ghế mềm 64, khoang 6 × 42, khoang 4 × 28) | **Vòng lặp sinh ra** | **1 giờ code** |
| ~200 chuyến × 20 ngày Tết | Generator | **1 giờ code** |
| Bảng giá theo hạng chỗ, tầng giường | Công thức | **30 phút** |
| Hành khách, đơn hàng, thanh toán | **Do chính việc dùng hệ thống sinh ra** | **0** |
| Tải 500k người dùng | **Load generator (k6)** — và nó là phần thú vị | 4 giờ code |
| | **Tổng** | **~7 giờ** |

So với **65–350 giờ** curate nội dung của dự án fitness. Bạn viết code từ ngày đầu tiên.

---

## 6. Mục lục tài liệu

| # | Tài liệu | Nội dung |
|---|---|---|
| 01 | [Domain Model](docs/01-domain-model.md) | Event storming, bounded context, ubiquitous language |
| 02 | [Architecture](docs/02-architecture.md) | 5 plane, C4, lý do chọn công nghệ |
| **03** | [**Segment Inventory Engine**](docs/03-segment-inventory-engine.md) | ⭐ Bitmask, thuật toán chọn chỗ, 2KB insight |
| **04** | [**Contention Strategies**](docs/04-contention-strategies.md) | ⭐ 4 cách, benchmark, cách nào hỏng khi nào |

> Đang xây tiếp: danh mục service · schema DB · API contract · saga & event ·
> phòng chờ ảo · chống đầu cơ · load test · sprint backlog.

---

## 7. Bài học chuyển từ dự án fitness

Khoảng **70% phần "cách làm"** dùng lại nguyên vẹn:

| Từ bộ tài liệu fitness | Dùng lại |
|---|---|
| Thứ tự dựng codebase · template copy · 10 bước dựng service | ✅ 100% |
| Quy ước API (đơn vị, thời gian, RFC 9457, idempotency) | ✅ 100% |
| Quy ước schema (outbox, processed_event, enum, tiền) | ✅ 100% |
| Event & saga · migration & rollback · mẫu ADR | ✅ 100% |
| Chạy local $0 (k3d, ngân sách RAM) | ✅ 100% |
| Mô hình miền fitness, solver thực đơn | ❌ — giữ lại cho bản ship thật sau này |

Cái mới hoàn toàn: **tranh chấp, phòng chờ ảo, đối soát hai kho dữ liệu, chống đối thủ có chủ đích**.

---

## 8. Chạy thử

```bash
make infra-up      # Redis, PostgreSQL, Kafka, Keycloak
make seed          # sinh 20 ga, 200 chuyến, 20 ngày Tết — bằng code, không cần dữ liệu ngoài
make services-up
make loadtest      # k6: 10.000 người tranh 500 chỗ
make verify        # đếm vé bán ra — phải đúng 500
```

`make verify` là thứ khiến dự án này dạy nhanh hơn fitness: **feedback khách quan trong 30 giây**.
