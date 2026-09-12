# 04 — Contention Strategies

> Bốn cách giải cùng một bài toán. Mỗi cách **hỏng theo một kiểu khác nhau**.
> Bạn chỉ thật sự hiểu khi tự dựng cả bốn và tự thấy chúng gãy.
>
> Đây là bài tập học giá trị nhất của dự án.

---

## 1. Vì sao thêm server làm mọi thứ tệ hơn

Trực giác từ web thông thường: chậm ⇒ thêm pod. Ở bài toán tranh chấp, trực giác đó **sai ngược**.

**Luật khả mở rộng phổ quát (Universal Scalability Law):**

$$
C(N) = \frac{N}{1 + \alpha(N-1) + \beta N(N-1)}
$$

| Hệ số | Nghĩa | Ở bài toán này |
|---|---|---|
| $\alpha$ | **Tuần tự hoá** — phần công việc không song song được | Cao: mọi request chạm cùng vài dòng |
| $\beta$ | **Nhiễu chéo** — chi phí đồng bộ giữa các luồng | Cao: khoá, retry, cache invalidation |

Với $\beta > 0$, $C(N)$ **đạt đỉnh rồi đi xuống**. Thêm luồng sau điểm đỉnh làm throughput **giảm**.

```
throughput
   │      ╭──╮
   │     ╱    ╲___          ← USL: đỉnh rồi tụt
   │    ╱          ╲___
   │   ╱                ╲___
   │  ╱
   │ ╱     ┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈  ← tuyến tính (điều bạn TƯỞNG sẽ xảy ra)
   │╱
   └──────────────────────────▶ số luồng đồng thời
        ↑
     điểm đỉnh — thường thấp đến mức bất ngờ
```

**Hệ quả thực tế:** 100 pod `inventory-service` cùng tranh một dòng chậm hơn 10 pod. Bạn sẽ
tự đo được điều này ở §7 — và đó là khoảnh khắc bài học đọng lại.

Chỉ có ba cách thật sự tăng throughput:
1. **Giảm $\alpha$** — làm phần tuần tự ngắn hơn (Lua 80 µs thay vì transaction 3 ms)
2. **Giảm $\beta$** — bỏ hẳn đồng bộ (đơn luồng ⇒ không có khoá)
3. **Phân vùng** — biến một miền tranh chấp lớn thành N miền độc lập ⭐

---

## 2. Cách 1 — `SELECT FOR UPDATE`

```sql
BEGIN;
SELECT berth_id, occupied_mask, held_mask
  FROM berth_inventory
 WHERE trip_id = ? AND class_code = ?
   AND (occupied_mask | held_mask) & ? = 0
 ORDER BY preference_score DESC
 LIMIT 1
   FOR UPDATE SKIP LOCKED;        -- ⭐ SKIP LOCKED là mấu chốt

UPDATE berth_inventory SET held_mask = held_mask | ? WHERE berth_id = ?;
INSERT INTO berth_hold (...) VALUES (...);
COMMIT;
```

`SKIP LOCKED` cứu nó khỏi thảm hoạ — không có nó, mọi transaction xếp hàng sau cùng một dòng
và throughput sụp về ~1/độ_trễ_khoá.

| | |
|---|---|
| ✅ | Đúng tuyệt đối, database giữ bất biến |
| ✅ | Bền vững ngay lập tức, không cần đối soát |
| ✅ | Ít mã, dễ hiểu, dễ debug |
| ❌ | **~200–500 giữ chỗ/giây mỗi chuyến** |
| ❌ | Giữ khoá suốt thời gian round-trip mạng |
| ❌ | Connection pool cạn khi tải cao ⇒ **service treo, không chỉ chậm** |
| ❌ | Không có `SKIP LOCKED` ⇒ convoy, deadlock |

**Đây là baseline đúng đắn của bạn.** Dựng nó trước tiên. Mọi cách sau phải chứng minh
nó cho **cùng kết quả** với cách này.

---

## 3. Cách 2 — Optimistic lock + retry

```sql
UPDATE berth_inventory
   SET held_mask = held_mask | :jmask, version = version + 1
 WHERE berth_id = :berthId
   AND version  = :readVersion
   AND (occupied_mask | held_mask) & :jmask = 0;
-- 0 dòng bị ảnh hưởng ⇒ có người chen trước ⇒ thử lại
```

| | |
|---|---|
| ✅ | Không giữ khoá ⇒ đọc scale tốt |
| ✅ | Không deadlock |
| ❌ | **Bão retry** — đây là cách nó chết |

**Cơ chế sụp đổ:**

```
Tranh chấp thấp  (10 luồng, 400 chỗ)
  → P(thành công lần 1) ≈ 97%  → ~800 op/giây  ✅

Tranh chấp cao   (5.000 luồng, 20 chỗ còn lại)
  → P(thành công lần 1) ≈ 0,4%
  → mỗi request thử ~15 lần
  → 5.000 × 15 = 75.000 lần ghi thất bại
  → database bận rộn làm công việc VÔ ÍCH
  → throughput hữu ích SỤP về ~50/giây
```

Công việc lãng phí tăng theo **bình phương** số luồng tranh chấp. Đây là con đường thất bại
xảo quyệt nhất: hệ thống trông *bận rộn* (CPU 100%, IOPS cao) trong khi **hầu như không làm được gì**.

> **Nếu dùng optimistic: bắt buộc có trần retry và backoff có jitter.** Retry vô hạn không
> phải khả năng chịu lỗi — nó là tấn công từ chối dịch vụ tự gây ra.

---

## 4. Cách 3 — Redis + Lua nguyên tử ⭐ (chọn)

Chi tiết script ở [03 §5](03-segment-inventory-engine.md).

**Vì sao nhanh hơn hai bậc độ lớn:**

| Yếu tố | Chi tiết |
|---|---|
| Phần tuần tự ~**80 µs** | so với ~3 ms của transaction PostgreSQL. $\alpha$ nhỏ hơn 37 lần |
| **$\beta \approx 0$** | Redis đơn luồng ⇒ không khoá, không đồng bộ, không nhiễu chéo |
| Toàn bộ trạng thái vừa **4 KB** | Một lần đọc, một lần ghi 8 byte |
| Không round-trip giữa đọc và ghi | Quyết định xảy ra **bên trong** Redis |

| | |
|---|---|
| ✅ | **~15.000–25.000 op/giây mỗi shard** |
| ✅ | Nguyên tử tự nhiên, không cần khoá phân tán |
| ✅ | Idempotency rẻ (kiểm `EXISTS` đầu script) |
| ❌ | **Không bền vững** — AOF `everysec` mất tối đa 1 giây |
| ❌ | Cần PostgreSQL làm nguồn sự thật + đối soát |
| ❌ | Logic nghiệp vụ nằm trong Lua: khó test, khó debug, không có kiểu |
| ❌ | Script dài làm nghẽn Redis (đơn luồng cắt cả hai chiều) |

> **Giữ script Lua dưới ~100 dòng và không có vòng lặp không chặn trên.** Redis đơn luồng:
> một script chạy 50 ms làm **mọi** client khác chờ 50 ms.

---

## 5. Cách 4 — Single-writer qua Kafka (event sourcing)

Phương án "thuần tuý" — và mạnh hơn vẻ ngoài của nó.

```
Mọi lệnh giữ chỗ của chuyến X
        ↓ partition key = tripId
   Kafka partition (thứ tự bảo đảm)
        ↓
   MỘT consumer sở hữu chuyến X
        ↓ trạng thái trong RAM — KHÔNG tranh chấp vì chỉ một luồng chạm
   Ghi kết quả ra topic phản hồi
```

| | |
|---|---|
| ✅ | **Không tranh chấp theo định nghĩa** — một luồng ghi |
| ✅ | **Log Kafka CHÍNH LÀ tính bền vững** — replay để dựng lại trạng thái |
| ✅ | Kiểm toán hoàn hảo: mọi quyết định nằm trong log, bất biến |
| ✅ | Logic là Java thuần, test được, gõ kiểu được — **không phải Lua** |
| ❌ | **Bất đồng bộ** — khách đang chờ chỗ, phải bắc cầu request/response qua topic |
| ❌ | p99 cao hơn (~50–200 ms) do gom lô |
| ❌ | Rebalance = dừng phục vụ chuyến đó vài giây |
| ❌ | Trạng thái trong RAM phải có snapshot, nếu không khởi động lại mất vài phút replay |

**Đây là lựa chọn đúng nếu bạn ưu tiên tính đúng đắn và kiểm toán hơn độ trễ.** Với vé tàu,
khách đang nhìn màn hình chờ — độ trễ quan trọng, nên ta chọn Redis. Với đặt chỗ theo lô
hoặc hệ thống nội bộ, cách này thắng.

---

## 6. Bảng đối chiếu

| | FOR UPDATE | Optimistic | **Redis Lua** | Kafka single-writer |
|---|---|---|---|---|
| Throughput/chuyến | 200–500/s | 800/s → **sụp** | **15–25k/s** | 8–15k/s |
| p99 độ trễ | 15–200 ms | 10 ms → **∞** | **2–5 ms** | 50–200 ms |
| Bền vững | ✅ ngay | ✅ ngay | ❌ mất ≤1s | ✅ log |
| Nguyên tử | ✅ | ✅ | ✅ | ✅ |
| Cần đối soát | ❌ | ❌ | ✅ | ❌ |
| Test được | ✅ | ✅ | ❌ Lua | ✅ |
| Kiểu dữ liệu an toàn | ✅ | ✅ | ❌ | ✅ |
| Hỏng kiểu gì | Pool cạn, treo | **Bão retry** | Mất hold | Rebalance, trễ |
| Độ phức tạp vận hành | Thấp | Thấp | **Cao** | Cao |

> Con số là **ước lượng để bạn đối chiếu khi tự đo**, không phải kết quả đã đo. Phần giá trị
> nhất của dự án là bạn tự chạy §7 và có số của chính mình trên phần cứng của chính mình.

---

## 7. Cách đo — bài tập cốt lõi

```javascript
// loadtest/hold-contention.js  — k6
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    // Đợt mở bán: 0 → 10.000 VU trong 10 giây, giữ 60 giây
    tet_opening: {
      executor: 'ramping-arrival-rate',
      startRate: 0, timeUnit: '1s',
      preAllocatedVUs: 10000,
      stages: [
        { target: 5000,  duration: '10s' },
        { target: 20000, duration: '30s' },   // vượt xa khả năng — đó là điểm cần đo
        { target: 0,     duration: '20s' },
      ],
    },
  },
};

const TRIP = 'SE1-2026-02-14';        // MỘT chuyến — tranh chấp tối đa

export default function () {
  const res = http.post(`${__ENV.API}/api/v1/holds`, JSON.stringify({
    tripId: TRIP,
    fromStopIdx: 0, toStopIdx: 17,     // HN → SG, chiếm toàn bộ 17 chặng
    classCode: 'SOFT_SLEEPER_4',
    strategy: __ENV.STRATEGY,          // SPECIFIC | BEST_FIT | FIRST_FIT
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `${__VU}-${__ITER}`,
      'X-Identity': `CCCD-${__VU}`,    // mỗi VU một danh tính ⇒ quota không chặn
    },
  });

  check(res, {
    'không lỗi 5xx': (r) => r.status < 500,
    'kết quả rõ ràng': (r) => r.status === 201 || r.status === 409,
  });
}
```

### Ma trận thí nghiệm

| Thí nghiệm | Đổi biến | Điều bạn sẽ thấy |
|---|---|---|
| **A** | 4 cách cài đặt, cùng tải | Chênh lệch throughput hai bậc độ lớn |
| **B** | 1 → 200 pod `inventory-service` | **Đường cong USL** — đỉnh rồi tụt. Bài học đắt nhất |
| **C** | `BEST_FIT` vs `FIRST_FIT` + offset ngẫu nhiên | [03 §6](03-segment-inventory-engine.md) — thuật toán "thông minh" chậm hơn |
| **D** | 1 chuyến vs 200 chuyến, cùng tổng tải | **Phân vùng có tác dụng**: cùng RPS, tranh chấp bằng 0 |
| **E** | Bật/tắt phòng chờ ảo | Backpressure sản phẩm vs backpressure kỹ thuật |
| **F** | Giết Redis giữa load test | Mất bao nhiêu hold, đối soát mất bao lâu để sửa |

**Sau mỗi lần chạy, bắt buộc:**

```bash
make verify   # 4 truy vấn bất biến ở [03 §12] — đỏ một cái là fail
```

Một cài đặt nhanh gấp 100 lần nhưng bán thừa 3 vé là **sai**, không phải "nhanh hơn".

---

## 8. Vấn đề bền vững — và tại sao ta chấp nhận nó

Redis không bền vững như PostgreSQL. Cấu hình AOF:

| `appendfsync` | Mất tối đa | Throughput |
|---|---|---|
| `always` | ~0 | **Giảm 10–20 lần** — mất hết lợi thế |
| **`everysec`** ⭐ | **1 giây** | Giữ nguyên |
| `no` | Đến 30 giây | Nhanh nhất |

Chọn `everysec` và **thiết kế để mất 1 giây không quan trọng**:

| Trạng thái | Mất được? | Vì sao | Lưu ở đâu |
|---|---|---|---|
| Hold chưa trả tiền | ✅ | Khách bấm lại, mất 5 giây | Redis (TTL) |
| Đơn đang chờ thanh toán | ⚠️ | Có ghi PostgreSQL bất đồng bộ | Redis + PG async |
| **Vé đã thanh toán** | ❌ **Không bao giờ** | Tiền đã trừ | **PostgreSQL đồng bộ** |

### Luồng xác nhận — thứ tự là bắt buộc

```
1. Thanh toán thành công (webhook từ VNPay)
        ↓
2. ⭐ GHI POSTGRESQL ĐỒNG BỘ
   INSERT ticket ... với ràng buộc EXCLUDE chống chồng chặng
   ├─ THÀNH CÔNG ⇒ đi tiếp
   └─ VI PHẠM RÀNG BUỘC ⇒ Redis đã mất trạng thái, người khác lấy mất chỗ
        ⇒ HOÀN TIỀN NGAY + xin lỗi + gợi ý chỗ khác
        ⇒ cảnh báo vận hành — đây là sự cố, không phải chuyện thường
        ↓
3. Cập nhật Redis: held → occupied
        ↓
4. Xác nhận cho khách
```

**Bước 2 trước bước 4.** Không bao giờ báo "đặt vé thành công" trước khi PostgreSQL đã ghi bền.

Bước 2 thất bại là **hiếm nhưng có thật**. Hệ thống nào không có nhánh xử lý đó là hệ thống
sẽ có ngày hai khách cùng cầm vé một giường.

---

## 9. Hết hạn giữ chỗ — chiếc bẫy rò rỉ

Hold có TTL. Nhưng **bit `held` trong hash tồn kho KHÔNG tự hết hạn.**

```
hold:{trip}:{holdId}  ──TTL 15 phút──▶ tự xoá  ✅
inv:{trip}:{class}    field berth 05-12  held=0b11111  ──▶ ??? 

Không ai xoá bit này ⇒ giường vĩnh viễn không bán được.
Không có lỗi nào được ném ra. Rò rỉ âm thầm.
```

Một chuyến rò rỉ 30 chỗ là mất doanh thu 30 vé, và **không hệ thống giám sát thông thường nào
phát hiện** — mọi service đều "khoẻ".

### Ba lớp phòng thủ

**Lớp 1 — Hết hạn lười ngay trong script quét** ⭐ *tự lành*

Lưu thêm `heldUntil` vào giá trị mỗi chỗ. Script quét coi hold quá hạn là **trống** và tiện tay dọn luôn:

```lua
local now = tonumber(ARGV[8])
if b.heldUntil > 0 and b.heldUntil < now then
  b.held     = 0          -- coi như trống
  b.heldUntil = 0
  dirty[#dirty+1] = b     -- ghi lại khi kết thúc script
end
```

Ưu điểm lớn: **không phụ thuộc vào bất kỳ tiến trình nền nào.** Rò rỉ tự khỏi ở lần quét kế tiếp.

**Lớp 2 — Keyspace notification**

Redis phát sự kiện khi key hết hạn ⇒ consumer xoá bit. Nhanh, nhưng **fire-and-forget**:
consumer đang restart thì sự kiện mất luôn. Không được dùng làm cơ chế duy nhất.

**Lớp 3 — Job quét + đối soát**

Mỗi 60 giây, đối soát so Redis với PostgreSQL. Bit `held` không có hold tương ứng ⇒ xoá + ghi kiểm toán.

> Ba lớp nghe như thừa. Không thừa: lớp 1 xử lý 99%, lớp 2 giảm độ trễ, lớp 3 là lưới cuối
> bắt những gì cả hai lớp trên lọt. Rò rỉ tồn kho là loại lỗi **âm thầm** — chỉ phát hiện
> khi doanh thu hụt mà không ai biết vì sao.

---

## 10. Đối soát — trọng tài giữa hai kho dữ liệu

```
Mỗi 60 giây, cho từng chuyến đang bán:

  1. Đọc mọi bitmask từ Redis
  2. Dựng lại bitmask kỳ vọng từ PostgreSQL:
       occupied_kỳ_vọng = OR(journey_mask) các vé ISSUED
       held_kỳ_vọng     = OR(journey_mask) các hold còn hạn
  3. So sánh từng chỗ
  4. Lệch ⇒ POSTGRESQL THẮNG, sửa Redis
  5. Ghi bản ghi kiểm toán bất biến + cảnh báo
```

| Loại lệch | Nguyên nhân | Xử lý | Mức |
|---|---|---|---|
| Redis có `held`, PG không có hold | Rò rỉ (§9) | Xoá bit | 🟡 Info |
| PG có vé, Redis thiếu `occupied` | **Redis mất dữ liệu** | Đặt bit **ngay** | 🔴 Critical |
| Redis có `occupied`, PG không có vé | Redis ma / ghi hụt | Điều tra, mặc định xoá | 🔴 Critical |
| Cả hai khớp | Bình thường | — | — |

**Tỉ lệ lệch là chỉ số sức khoẻ hệ thống quan trọng nhất.** Ngưỡng cảnh báo:

```
lệch loại 🔴 > 0 trong 5 phút          ⇒ Critical, gọi người
lệch loại 🟡 > 0,1% số chỗ              ⇒ Warning
đối soát không chạy được 3 chu kỳ liên tiếp ⇒ Critical
```

> Đối soát **không phải tính năng phụ**. Nó là cái giá bạn trả cho việc chọn Redis —
> và là phần bạn phải viết **trước** khi bật Redis lên production, không phải sau.

---

## 11. Kiến trúc chốt lại

```
                    ┌──── waiting-room (Go) ────┐
  500k người ───────▶  hấp thụ sóng, xả 2k/giây │
                    └────────────┬──────────────┘
                                 ▼
                    ┌─── booking-service ───┐
                    │   saga orchestrator   │
                    └───────────┬───────────┘
                                ▼
                    ┌── inventory-service ──┐
                    │  phân vùng theo trip  │
                    └───────────┬───────────┘
                     ┌──────────┴──────────┐
                     ▼                     ▼
            ┌─────────────────┐   ┌──────────────────┐
            │ REDIS (nóng)    │   │ POSTGRESQL (bền) │
            │ Lua nguyên tử   │   │ EXCLUDE gist     │
            │ 15–25k op/s     │   │ nguồn sự thật    │
            │ AOF everysec    │   │ 200–500 op/s     │
            └────────┬────────┘   └────────┬─────────┘
                     └──────────┬──────────┘
                                ▼
                    ┌─── reconciliation ────┐
                    │  mỗi 60s · PG thắng   │
                    │  kiểm toán mọi lần sửa│
                    └───────────────────────┘
```

| Quyết định | Lý do |
|---|---|
| Redis cho đường nóng | Tranh chấp cần $\alpha$ nhỏ và $\beta = 0$ |
| PostgreSQL cho sự thật | Tiền và vé phải bền, phải có ràng buộc không lách được |
| `EXCLUDE USING gist` ở PG | Lưới an toàn cuối: kể cả Redis sai, DB vẫn từ chối ghi chồng chặng |
| Đối soát bắt buộc | Cái giá của kiến trúc hai kho dữ liệu |
| Phân vùng theo `tripId` | Cách duy nhất thật sự scale bài toán tranh chấp |
| Phòng chờ ảo | Rải nhu cầu theo thời gian — hiệu quả hơn mọi tối ưu kỹ thuật |

---

## 12. Thứ tự dựng — đi từ đúng tới nhanh

| Bước | Việc | Học được |
|---|---|---|
| 1 | **`SELECT FOR UPDATE`**, một chuyến, không cache | Baseline đúng đắn. Mọi thứ sau phải khớp kết quả này |
| 2 | Viết 4 truy vấn bất biến + `make verify` | Lưới an toàn trước khi tối ưu |
| 3 | Load test, đo throughput | Có con số gốc |
| 4 | Thêm pod → **thấy USL** | Bài học đắt nhất, và nó phản trực giác |
| 5 | Thử optimistic → **thấy bão retry** | Vì sao retry không phải giải pháp |
| 6 | Chuyển sang Redis Lua | Nhanh hơn ~50 lần |
| 7 | Giết Redis giữa test → **mất hold** | Vì sao cần đối soát |
| 8 | Viết đối soát | Đánh đổi bền vững |
| 9 | Phân vùng 200 chuyến | **Phân vùng thắng nhân bản** |
| 10 | Thêm phòng chờ | Backpressure ở tầng sản phẩm |

**Đừng nhảy thẳng tới bước 6.** Giá trị học nằm ở bước 4, 5, 7 — những lần bạn *thấy* nó gãy.
Đọc về bão retry không bằng nhìn biểu đồ throughput sụp xuống trong khi CPU 100%.

---

**Quay lại:** [README](../README.md) · [03 — Segment Inventory Engine](03-segment-inventory-engine.md)
