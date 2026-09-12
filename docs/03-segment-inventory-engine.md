# 03 — Segment Inventory Engine

> Trái tim hệ thống. Mọi service khác tồn tại để phục vụ hoặc bảo vệ nó.
>
> Bài toán: **một chỗ, nhiều khách, không được chồng chặng** — dưới 10.000 request đồng thời.

---

## 1. Vì sao "còn/hết" là mô hình sai

Vé máy bay: một ghế, một chuyến bay, một khách. Boolean.

Vé tàu Bắc–Nam: **một giường có thể bán cho 5 khách khác nhau trong cùng một chuyến.**

```
SE1 · 14/02/2026 · Giường 12, toa 5, tầng 1

HN ─── Vinh ─── Huế ─── ĐN ─── NT ─── SG
 0      1       2       3      4      5
 └─ Khách A ────┘
                 └──────┘ trống
                          └─ Khách B ──┘

Cùng một giường. Hai vé. Hoàn toàn hợp lệ.
Khách A xuống ở Huế, nhân viên thay ga giường, khách B lên ở Đà Nẵng.
```

Mô hình `available: true/false` không diễn đạt được điều này. Và nếu bạn mô hình sai ở đây,
**mọi thứ phía trên đều sai** — hiển thị sai, đặt chỗ sai, doanh thu mất 40% vì bán một
giường cho một khách thay vì ba.

---

## 2. Ba cách mô hình hoá — và vì sao chọn cách thứ ba

### Cách 1 — Một dòng cho mỗi (chỗ × chặng)

```sql
CREATE TABLE berth_leg (
    trip_id  TEXT, berth_id INT, leg_idx SMALLINT,
    status   TEXT,             -- FREE | HELD | SOLD
    PRIMARY KEY (trip_id, berth_id, leg_idx)
);
```

| Vấn đề | Con số |
|---|---|
| Số dòng | 500 chỗ × 29 chặng = **14.500 dòng/chuyến** · 200 chuyến × 20 ngày = **58 triệu dòng** |
| Một lần giữ chỗ | `UPDATE` 5–29 dòng trong một transaction |
| Deadlock | Hai request khoá các chặng **theo thứ tự khác nhau** ⇒ deadlock. Phải ép thứ tự khoá |
| Đếm chỗ trống | `GROUP BY berth_id HAVING count(*) = 29` — quét toàn bộ |

**Loại.** Đúng về logic, nhưng mỗi thao tác chạm hàng chục dòng — đúng thứ bạn không muốn khi có 10.000 request tranh nhau.

### Cách 2 — Khoảng + ràng buộc loại trừ của PostgreSQL

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

PostgreSQL **tự đảm bảo** không có hai đặt chỗ nào chồng chặng trên cùng một giường. Ràng buộc ở tầng database, không thể lách.

| Ưu | Nhược |
|---|---|
| ✅ Đúng tuyệt đối, không cần code giữ bất biến | ❌ ~200 giữ chỗ/giây khi tranh chấp cao |
| ✅ Chỉ 1 dòng mỗi đặt chỗ | ❌ Chỉ số GiST là điểm nghẽn ghi |
| ✅ Rất dễ hiểu | ❌ Đếm chỗ trống vẫn tốn |

**Giữ lại — nhưng cho đường nguội.** Đây là ràng buộc bền vững ở PostgreSQL ([02 §4](02-architecture.md)). Nó là lưới an toàn cuối cùng: kể cả Redis sai, database vẫn từ chối ghi chồng chặng.

### Cách 3 — Bitmask ⭐

Mỗi chỗ: **hai số nguyên 32 bit**.

```
occupiedMask  bit i = 1  ⇔  chặng L_i đã BÁN
heldMask      bit i = 1  ⇔  chặng L_i đang GIỮ TẠM
```

| Thao tác | Phép tính |
|---|---|
| Hành trình từ ga `a` đến ga `b` | `mask = (1 << b) - (1 << a)` |
| Chỗ có trống cho hành trình không? | `((occupied \| held) & mask) == 0` |
| Giữ chỗ | `held \|= mask` |
| Xác nhận (đã trả tiền) | `held &= ~mask ; occupied \|= mask` |
| Nhả (hết hạn / huỷ) | `held &= ~mask` |
| Trả vé | `occupied &= ~mask` |

Kiểm chứng công thức mask:

```
a=0, b=2  (HN → Huế)   (1<<2)-(1<<0) = 4-1  = 3  = 0b00011  → chặng L0, L1  ✓
a=3, b=5  (ĐN → SG)    (1<<5)-(1<<3) = 32-8 = 24 = 0b11000  → chặng L3, L4  ✓
0b00011 & 0b11000 = 0                                        → không xung đột ✓
```

**Mọi thao tác là một lệnh CPU.** Không join, không quét, không khoá hàng, không deadlock.

---

## 3. Phát hiện quyết định toàn bộ kiến trúc

Tuyến dài nhất Việt Nam — Hà Nội ↔ Sài Gòn — có khoảng 30 ga dừng ⇒ **29 chặng ⇒ vừa `int32`**.

Một chuyến ~500 chỗ:

$$
500 \text{ chỗ} \times (4 + 4) \text{ byte} = \mathbf{4\ KB}
$$

**Toàn bộ tồn kho một chuyến tàu = 4 KB.**

Hệ quả xâu chuỗi nhau:

```
4 KB
 ├─▶ vừa MỘT giá trị Redis
 │    └─▶ một script Lua đọc/sửa/ghi trọn vẹn
 │         └─▶ Redis đơn luồng ⇒ NGUYÊN TỬ, KHÔNG CẦN KHOÁ
 │              └─▶ không deadlock, không retry, không khoá hết hạn sai lúc
 │                   └─▶ ~25.000 thao tác/giây/chuyến
 └─▶ vừa cache L2 của CPU ⇒ quét 500 chỗ trong ~microgiây
```

Đây là lý do bài toán tranh chấp cực đoan này **giải được**. Và bạn chỉ thấy nó khi
mô hình hoá tồn kho bằng bitmask thay vì bằng dòng SQL.

> **Giới hạn cần biết:** `int32` chịu được 32 chặng. Tuyến VN tối đa ~29 — vừa đủ, không dư nhiều.
> Tuyến dài hơn (nếu có) dùng `int64` (63 chặng) hoặc hai `int32`. Ghi rõ giới hạn này vào
> ràng buộc dữ liệu, đừng để phát hiện lúc chạy.

---

## 4. Bố trí dữ liệu Redis

```
Key:   inv:{SE1-2026-02-14}:SOFT_SLEEPER_4        ← hashtag {tripId} ép cùng slot
Type:  Hash
Field: berthId                                     ← "05-12" = toa 5, giường 12
Value: "occ,held,level,carriage,berthNo"           ← "3,0,1,5,12"

Key:   hold:{SE1-2026-02-14}:{holdId}              ← TTL 15 phút
Value: "berthId,journeyMask,bookingId"

Key:   meta:{SE1-2026-02-14}
Value: JSON { legCount, stops[], classCodes[], saleStatus }
```

**Hashtag `{tripId}` là bắt buộc, không phải tuỳ chọn.** Redis Cluster chia slot theo hash của
key; chỉ phần trong `{}` được tính. Không có nó, các hạng chỗ của cùng chuyến rơi vào slot khác
nhau và **script Lua không chạy được** (Lua chỉ thao tác key cùng slot).

**Tách key theo hạng chỗ** (`SOFT_SLEEPER_4`, `SOFT_SEAT`, …) để script chỉ quét ứng viên
thật sự phù hợp, thay vì cả 500 chỗ.

---

## 5. Script Lua giữ chỗ

```lua
-- KEYS[1] = inv:{tripId}:{classCode}
-- KEYS[2] = hold:{tripId}:{holdId}
-- ARGV[1] = journeyMask       (số nguyên)
-- ARGV[2] = strategy          BEST_FIT | FIRST_FIT | SPECIFIC
-- ARGV[3] = requestedBerthId  (chỉ dùng khi SPECIFIC)
-- ARGV[4] = holdId
-- ARGV[5] = ttlSeconds
-- ARGV[6] = scanOffset        (ngẫu nhiên — xem §6)
-- ARGV[7] = bookingId

local jmask    = tonumber(ARGV[1])
local strategy = ARGV[2]

-- Chống lặp: holdId đã tồn tại ⇒ trả lại kết quả cũ
if redis.call('EXISTS', KEYS[2]) == 1 then
  local prev = redis.call('GET', KEYS[2])
  return {1, prev, 'IDEMPOTENT_REPLAY'}
end

local inv = redis.call('HGETALL', KEYS[1])       -- ~4 KB, một lần đọc

-- Gom ứng viên
local berths = {}
for i = 1, #inv, 2 do
  local id = inv[i]
  local occ, held, level, carriage, no = inv[i+1]:match("(%d+),(%d+),(%d+),(%d+),(%d+)")
  berths[#berths+1] = {
    id = id, occ = tonumber(occ), held = tonumber(held),
    level = tonumber(level), carriage = tonumber(carriage), no = tonumber(no)
  }
end

-- Chọn chỗ
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
  local offset = tonumber(ARGV[6]) % n          -- ⭐ điểm bắt đầu ngẫu nhiên
  local best, bestScore = nil, -1

  for k = 0, n - 1 do
    local b = berths[((offset + k) % n) + 1]

    if bit.band(bit.bor(b.occ, b.held), jmask) == 0 then
      if strategy == 'FIRST_FIT' then
        chosen = b                               -- ⭐ cao điểm: lấy ngay, không chấm điểm
        break
      end
      local score = (4 - b.level) * 10           -- tầng 1 > 2 > 3
      if b.no > 4 and b.no < 30 then score = score + 3 end   -- tránh đầu/cuối toa
      if score > bestScore then best, bestScore = b, score end
    end
  end
  chosen = chosen or best
end

if chosen == nil then
  return {0, '', 'NO_BERTH_AVAILABLE'}
end

-- Đặt bit — kiểm tra lại lần cuối (phòng thủ)
if bit.band(bit.bor(chosen.occ, chosen.held), jmask) ~= 0 then
  return {0, '', 'RACE_DETECTED'}                -- không thể xảy ra: Lua đơn luồng
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

**Bốn chi tiết đáng chú ý:**

| Chi tiết | Vì sao |
|---|---|
| Kiểm `EXISTS KEYS[2]` đầu script | Idempotency. User bấm 3 lần ⇒ một chỗ, không phải ba |
| `RACE_DETECTED` không bao giờ xảy ra | Redis Lua đơn luồng. Nhưng giữ lại để **test khẳng định giả định** — nếu nó xuất hiện, giả định nền tảng đã sai |
| `scanOffset` ngẫu nhiên | §6 — quan trọng hơn nó trông có vẻ |
| `FIRST_FIT` phá vòng lặp ngay | §6 |

---

## 6. Vì sao "chọn chỗ tốt nhất" làm hỏng hệ thống lúc cao điểm

Đây là bài học phản trực giác nhất của dự án.

```
BEST_FIT — 10.000 request đồng thời
  Mọi script đều quét từ đầu, đều chấm điểm giống nhau,
  đều kết luận "giường 05-12 tầng 1 là tốt nhất".
  → Request #1 lấy được.
  → 9.999 request còn lại tính toán xong rồi phát hiện mất chỗ.
  → Lãng phí 9.999 lần quét toàn bộ.
```

Nghịch lý: thuật toán chọn chỗ **thông minh hơn** làm hệ thống **chậm hơn**, vì nó khiến mọi
người tranh cùng một tài nguyên.

```
FIRST_FIT + scanOffset ngẫu nhiên
  Mỗi request bắt đầu quét ở một vị trí khác nhau.
  Request #1 bắt đầu ở chỗ 37 → lấy chỗ 37.
  Request #2 bắt đầu ở chỗ 412 → lấy chỗ 412.
  → Va chạm gần như bằng 0. Mỗi script dừng sau ~1 lần thử.
```

Hai thay đổi nhỏ — bỏ chấm điểm, bắt đầu ngẫu nhiên — đổi độ phức tạp trung bình từ
$O(n)$ quét lãng phí sang $O(1)$ trúng ngay.

**Chiến lược theo tải:**

| Tỉ lệ lấp đầy | Chế độ | Lý do |
|---|---|---|
| < 60% | `SPECIFIC` (khách tự chọn trên sơ đồ) | Trải nghiệm tốt, tranh chấp thấp |
| 60–85% | `BEST_FIT` | Vẫn còn dư chỗ để tối ưu |
| **> 85% hoặc từ chối > 30%** | **`FIRST_FIT` + offset ngẫu nhiên** | **Sống sót quan trọng hơn tối ưu** |

Chuyển chế độ tự động dựa trên metric, và **báo cho khách biết**:
*"Đang cao điểm — hệ thống sẽ chọn chỗ tốt nhất còn lại cho bạn."*

---

## 7. Đếm chỗ trống — phần tinh tế

Câu hỏi tưởng đơn giản: *"Còn bao nhiêu chỗ HN→SG?"*

**Bộ đếm theo chặng cho kết quả SAI.** Ví dụ với sức chứa 100:

```
Giường #1: chỉ bị chiếm chặng L0
Giường #2: chỉ bị chiếm chặng L4
Các giường khác: trống hoàn toàn

Bộ đếm theo chặng:  L0 còn 99, L4 còn 99, các chặng khác còn 100
min(theo chặng) = 99   ← SAI

Thực tế cho hành trình HN→SG (cần TẤT CẢ chặng):
  Giường #1 không dùng được (vướng L0)
  Giường #2 không dùng được (vướng L4)
  = 98 chỗ                 ← ĐÚNG
```

Bộ đếm theo chặng chỉ cho **cận trên**. Muốn đúng phải đếm thật:

$$
\text{available}(J) = \bigl|\{\, b : (\text{occ}_b \mid \text{held}_b) \,\&\, J = 0 \,\}\bigr|
$$

Với 500 chỗ, đây là một vòng lặp Lua ~200 µs. Rẻ. Nhưng gọi 10.000 lần/giây thì không rẻ nữa.

**Giải pháp ba tầng:**

| Tầng | Dùng khi | Độ chính xác | Chi phí |
|---|---|---|---|
| Cache Redis, TTL 3 giây | Hiển thị danh sách chuyến | Cũ tối đa 3s | ~0 |
| Quét Lua thật | Khách mở sơ đồ chỗ của một chuyến | Chính xác lúc đó | 200 µs |
| **Script giữ chỗ** | **Lúc bấm "Giữ chỗ"** | **Sự thật duy nhất** | 80 µs |

> **Nguyên tắc sản phẩm:** con số hiển thị là **gợi ý**, không phải cam kết. Giao diện phải
> nói rõ điều đó. Mọi hệ thống bán vé đều có khoảnh khắc "chỗ vừa bị người khác lấy" —
> khác biệt nằm ở chỗ bạn *thiết kế* cho nó hay *giả vờ* nó không tồn tại.

---

## 8. Đặt nhóm — cả nhà nằm cùng khoang

Yêu cầu R7. Gia đình 4 người muốn nguyên một khoang 4 giường.

Ràng buộc: cần $k$ chỗ **trong cùng một khoang**, tất cả đều trống cho cùng `journeyMask`.

```lua
-- Quét theo khoang thay vì theo chỗ
-- Khoang = nhóm 4 hoặc 6 giường liên tiếp trong cùng toa
for _, compartment in ipairs(compartments) do
  local free = {}
  for _, b in ipairs(compartment.berths) do
    if bit.band(bit.bor(b.occ, b.held), jmask) == 0 then
      free[#free+1] = b
    end
  end
  if #free >= groupSize then
    -- lấy groupSize chỗ đầu tiên, đặt bit cho tất cả trong CÙNG script
    return reserve_all(free, groupSize, jmask)
  end
end
```

**Thang rơi lùi khi không đủ:**

```
1. Nguyên khoang, cùng tầng          ← lý tưởng
2. Cùng khoang, khác tầng
3. Cùng toa, khoang liền kề
4. Cùng toa
5. Bất kỳ — báo rõ "không xếp được cạnh nhau, bạn có muốn tiếp tục?"
```

> **Không dùng solver tối ưu ở đây.** Greedy first-fit theo thang trên là đủ và chạy trong
> cùng script Lua. Đưa OR-Tools vào để "xếp chỗ tối ưu" là over-engineering: nó phá tính
> nguyên tử, thêm một lời gọi mạng vào đường nóng nhất, và khách không phân biệt được
> "tối ưu" với "đủ tốt". Đây là chỗ dễ over-engineer nhất trong cả hệ thống.

---

## 9. Đổi vé — thao tác nguy hiểm nhất

Đổi từ chuyến A sang chuyến B: phải **nhả mask cũ và chiếm mask mới**, và nếu nửa chừng thất bại thì khách mất cả hai.

**Cùng một chuyến** (đổi giường) — một script Lua, nguyên tử tự nhiên:

```
old.occupied &= ~oldMask
new.held     |= newMask        ← trong cùng script, không thể nửa vời
```

**Khác chuyến** — hai shard Redis khác nhau, **không thể nguyên tử**. Bắt buộc dùng saga:

```
1. Giữ chỗ MỚI trên chuyến B          (chưa đụng vé cũ)
   ✗ thất bại ⇒ dừng, vé cũ nguyên vẹn, khách không mất gì
2. Ghi PostgreSQL: vé cũ EXCHANGING, vé mới PENDING   (một transaction)
3. Xác nhận chỗ mới (held → occupied)
4. Nhả chỗ cũ
   ✗ thất bại ⇒ đối soát nhặt lên sau; khách ĐÃ có vé mới
```

**Thứ tự bước 1 trước bước 4 là bắt buộc.** Nhả chỗ cũ trước rồi giữ chỗ mới thất bại
⇒ khách mất cả hai, và chỗ cũ có thể đã bị người khác lấy mất trong tích tắc đó.

Quy tắc chung: **luôn chiếm cái mới trước khi nhả cái cũ.** Rủi ro tệ nhất là chiếm hai chỗ
trong vài giây — chấp nhận được. Rủi ro của thứ tự ngược lại là khách không còn chỗ nào.

---

## 10. Bất biến sinh tử: chỉ số ga bị đóng băng

```
journeyMask được tính từ CHỈ SỐ ga trong danh sách dừng.
Chèn hoặc bỏ một ga ⇒ MỌI mask đã bán trở nên vô nghĩa.
```

Ví dụ: chuyến đang bán, điều độ thêm ga Tam Kỳ vào giữa Đà Nẵng(3) và Nha Trang(4).
Mọi ga sau đó dịch chỉ số +1. Vé HN→SG có mask `0b11111` giờ trỏ sai chặng.
**Toàn bộ tồn kho hỏng âm thầm** — không có lỗi nào được ném ra.

**Quy tắc bắt buộc:**

| Trạng thái chuyến | Được sửa danh sách ga? |
|---|---|
| `SCHEDULED` (chưa mở bán) | ✅ Tự do |
| `SELLING` · `CLOSED` · `DEPARTED` | ❌ **Cấm tuyệt đối** |

Đổi lịch sau khi mở bán ⇒ **tạo chuyến mới** + di cư vé + thông báo khách. Đau nhưng an toàn.

Thực thi ở tầng dữ liệu:

```sql
ALTER TABLE trip_stop ADD CONSTRAINT chk_stops_frozen CHECK (
    (SELECT status FROM trip t WHERE t.trip_id = trip_stop.trip_id) = 'SCHEDULED'
);
-- Thực tế dùng trigger BEFORE INSERT/UPDATE/DELETE vì CHECK không query được bảng khác
```

---

## 11. Sinh dữ liệu — 7 giờ, không cần curate

```
20 ga chính tuyến Bắc–Nam        →  gõ tay, 15 phút
   HN · Phủ Lý · Nam Định · Ninh Bình · Thanh Hoá · Vinh · Đồng Hới
   Đông Hà · Huế · Đà Nẵng · Tam Kỳ · Quảng Ngãi · Diêu Trì · Tuy Hoà
   Nha Trang · Tháp Chàm · Biên Hoà · Sài Gòn

Thành phần đoàn tàu             →  vòng lặp
   Toa 1–2:  ghế mềm điều hoà     64 ghế
   Toa 3–6:  giường nằm khoang 6  42 giường (7 khoang × 6, tầng 1/2/3)
   Toa 7–10: giường nằm khoang 4  28 giường (7 khoang × 4, tầng 1/2)
   Toa 11:   toa ăn · Toa 12: hành lý
   → 128 + 168 + 112 = 408 chỗ/chuyến

Chuyến                          →  generator
   5 mác tàu (SE1/3/5/7, TN1) × 2 chiều × 20 ngày Tết = 200 chuyến

Giá vé                          →  công thức
   base × hệ_số_quãng_đường × hệ_số_hạng_chỗ × hệ_số_tầng × hệ_số_cao_điểm
```

**Bất đối xứng có thật đáng mô phỏng:** trước Tết, chiều **Nam → Bắc** cháy vé (người làm ăn ở
Sài Gòn về quê); sau Tết thì ngược lại. Load test nên phản ánh điều này thay vì rải đều —
nếu không bạn sẽ tối ưu cho một hình dạng tải không tồn tại.

---

## 12. Cách chứng minh bạn làm đúng

Điểm mạnh lớn nhất của domain này: **tính đúng đắn kiểm chứng được bằng máy.**

```bash
make loadtest   # k6: 10.000 VU tranh 408 chỗ trên SE1-2026-02-14
make verify
```

```sql
-- ⛔ BẤT BIẾN 1: không chồng chặng trên cùng một chỗ
SELECT t1.berth_id, t1.ticket_id, t2.ticket_id
FROM ticket t1 JOIN ticket t2
  ON  t1.trip_id  = t2.trip_id
  AND t1.berth_id = t2.berth_id
  AND t1.ticket_id < t2.ticket_id
  AND (t1.journey_mask & t2.journey_mask) <> 0
WHERE t1.status = 'ISSUED' AND t2.status = 'ISSUED';
-- PHẢI trả về 0 dòng

-- ⛔ BẤT BIẾN 2: không chặng nào vượt sức chứa
SELECT leg_idx, count(*) AS sold, capacity
FROM ticket_leg JOIN trip_capacity USING (trip_id, leg_idx)
GROUP BY leg_idx, capacity
HAVING count(*) > capacity;
-- PHẢI trả về 0 dòng

-- ⛔ BẤT BIẾN 3: Redis khớp PostgreSQL
-- (reconciliation chạy và báo 0 sai lệch)

-- ⛔ BẤT BIẾN 4: tiền khớp vé
SELECT b.booking_id FROM booking b
WHERE b.status = 'CONFIRMED'
  AND b.total_vnd <> (SELECT COALESCE(sum(fare_vnd),0) FROM ticket
                      WHERE booking_id = b.booking_id AND status <> 'REFUNDED');
-- PHẢI trả về 0 dòng
```

Bốn truy vấn này chạy sau **mỗi** lần load test, trong CI. Đỏ một cái là build fail.

> **Tính đúng đắn ở đây là nhị phân, không phải ý kiến.** "Có bán thừa vé không" trả lời được
> trong 30 giây, tự động, mỗi lần commit — không cần người dùng thật, không cần chờ phản hồi,
> không cần tranh luận. Đó là lý do bạn dám thay đổi kiến trúc tồn kho bốn lần ở
> [04 §12](04-contention-strategies.md) mà không sợ: mỗi lần đổi, bốn truy vấn này nói ngay
> bạn còn đúng hay đã sai.

---

**Tiếp theo:** [04 — Contention Strategies](04-contention-strategies.md)
