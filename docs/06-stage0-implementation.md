# 06 — Hướng dẫn triển khai Stage 0

> Tài liệu này dùng để tham khảo trong khi viết code. Để hiểu *tại sao* các quyết định
> được đưa ra, xem [05 — Build Progression](05-build-progression.md) và
> [03 — Segment Inventory Engine](03-segment-inventory-engine.md).

---

## Thứ tự xây dựng

Viết theo thứ tự này. Mỗi bước mở khóa bước tiếp theo.

---

### Bước 1 — `schedule-service`

**Không phụ thuộc vào service nào. Xây dựng và kiểm tra hoàn toàn độc lập.**

#### Mục đích nghiệp vụ

Hành khách cần tìm chuyến tàu trước khi đặt vé. Service này trả lời hai câu hỏi:

- "Có chuyến tàu nào từ Hà Nội đến Sài Gòn ngày 14 tháng 2 không?" → tìm kiếm
- "Chuyến tàu SE1 có gì — các ga dừng, toa, chỗ ngồi, giá vé?" → chi tiết

Service này **chỉ đọc**. Nó không bao giờ thay đổi trạng thái tồn kho. Vì vậy nó
an toàn để xây dựng độc lập và cho bạn dữ liệu test trước khi làm các service khó hơn.

#### Vấn đề mà query tìm kiếm phải giải quyết

Người dùng tìm kiếm `HN → SG`. Database biết về chỗ nằm, không biết về tìm kiếm.
Thách thức là join hai hàng `trip_stop` (một cho HN, một cho SG) trên cùng một
chuyến tàu và kiểm tra rằng HN xuất hiện trước SG:

```
trip_stop where station_code = 'HN'  →  station_index = 0  (fromIndex)
trip_stop where station_code = 'SG'  →  station_index = 19 (toIndex)
Chỉ lấy nếu fromIndex < toIndex      →  đúng chiều đi
```

Response phải trả về `fromStationIndex` và `toStationIndex` — client truyền hai
số này thẳng vào lần gọi tiếp theo (`/availability`). Client không cần tự tính index.

`minPriceVnd` là giá chỗ rẻ nhất trên toàn bộ chuyến tàu, qua tất cả các hạng.
Đây là gợi ý hiển thị trên trang kết quả tìm kiếm, không phải giá đặt vé thực sự.

#### Vấn đề mà query chi tiết phải giải quyết

`GET /trips/{tripId}` trả về mọi thứ client cần để hiển thị sơ đồ toa: tất cả các
ga theo thứ tự, tất cả các toa, và tất cả chỗ nằm trong mỗi toa với giá và tầng.
Không cần gọi thêm bất kỳ API nào.

#### Cấu trúc package

```
vn.railticketing.schedule
├── ScheduleServiceApplication.java
├── domain/
│   ├── Trip.java
│   ├── TripStopId.java       ← class khóa chính composite
│   ├── TripStop.java
│   ├── Carriage.java
│   └── Berth.java
├── repository/
│   ├── TripRepository.java
│   └── BerthRepository.java
├── service/
│   └── TripService.java
└── web/
    ├── TripController.java
    └── dto/
        ├── TripSummaryDto.java
        ├── TripDetailDto.java
        ├── StationDto.java
        ├── CarriageDto.java
        └── BerthDto.java
```

#### Cách triển khai từng class

**`Trip`** — `@Entity @Table(name = "trip")`
- Fields: `tripId` (`@Id @GeneratedValue IDENTITY`), `trainCode`, `serviceDate` (`LocalDate`), `status`
- Quan hệ: `@OneToMany(mappedBy="trip") @OrderBy("stationIndex") List<TripStop> stops`
  và `@OneToMany(mappedBy="trip") @OrderBy("carriageNo") List<Carriage> carriages`
- Giữ quan hệ `fetch = LAZY` — endpoint tìm kiếm không cần stops hay carriages

**`TripStopId`** — `@Embeddable` với hai fields: `tripId` (`Long`) và `stationIndex` (`Short`)
- Phải implement `equals` và `hashCode`

**`TripStop`** — `@Entity @Table(name = "trip_stop")`
- `@EmbeddedId TripStopId id`
- `@ManyToOne(fetch=LAZY) @MapsId("tripId") Trip trip`
- Fields: `stationCode`, `arrivesAt` (`OffsetDateTime`), `departsAt` (`OffsetDateTime`)

**`Carriage`** — `@Entity`
- Fields: `carriageId`, `tripId`, `carriageNo`, `berthClass`
- `@OneToMany(mappedBy="carriage") @OrderBy("berthNo") List<Berth> berths`

**`Berth`** — `@Entity`
- Fields: `berthId`, `tripId`, `carriageId`, `carriageNo`, `berthNo`, `berthClass`, `level` (nullable `Short`), `priceVnd`

**`TripRepository`** — extends `JpaRepository<Trip, Long>`
- Query tìm kiếm cần join `trip_stop` hai lần — một lần cho ga đi, một lần cho ga đến
  — và lọc `fromIndex < toIndex`. Viết dưới dạng `@Query` JPQL:
  ```
  SELECT t, fromStop, toStop FROM Trip t
  JOIN t.stops fromStop
  JOIN t.stops toStop
  WHERE t.serviceDate = :date
    AND fromStop.stationCode = :fromCode
    AND toStop.stationCode   = :toCode
    AND fromStop.id.stationIndex < toStop.id.stationIndex
    AND t.status IN ('SCHEDULED','SELLING')
  ```
  Kiểu trả về có thể là `List<Object[]>` và unpack trong service, hoặc dùng projection.

**`BerthRepository`** — extends `JpaRepository<Berth, Long>`
- `findMinPriceVndByTripId(Long tripId)` — một query per trip để tìm giá chỗ rẻ nhất

**`TripService`**
- `searchTrips(fromCode, toCode, date)`:
  1. Chạy query double-join
  2. Với mỗi kết quả, tính `durationMinutes` từ `fromStop.departsAt` đến `toStop.arrivesAt`
  3. Lấy `minPriceVnd` theo trip id
  4. Map sang `TripSummaryDto`
- `getTrip(tripId)`:
  1. Load trip với stops và carriages (eager hoặc explicit join fetch)
  2. Map sang `TripDetailDto`
  3. Throw `ResponseStatusException(NOT_FOUND)` nếu không tìm thấy

**`TripController`** — `@RestController @RequestMapping("/api/v1/trips")`
- `@GetMapping` — gọi `tripService.searchTrips`, trả về `{ "trips": [...] }`
- `@GetMapping("/{tripId}")` — gọi `tripService.getTrip`, trả về `TripDetailDto`
- Validate format `departureDate` với `@DateTimeFormat(iso = DATE)` trên param

**DTOs** — plain record hoặc class với tất cả fields theo OpenAPI schema. Không có JPA
annotation. Service layer map entities → DTOs; controller không bao giờ đụng đến entity.

#### Thứ tự triển khai

| # | Viết gì | Kiểm tra |
|---|---|---|
| 1 | Flyway `V1__init.sql` ✅ đã xong | Service khởi động, không có lỗi Flyway trong log |
| 2 | Các entity `Trip`, `TripStop`, `TripStopId`, `Carriage`, `Berth` | — |
| 3 | `TripRepository`, `BerthRepository` | — |
| 4 | DTOs: `TripSummaryDto`, `TripDetailDto`, `StationDto`, `CarriageDto`, `BerthDto` | — |
| 5 | `TripService.searchTrips` + `TripController GET /trips` | `curl "localhost:8081/api/v1/trips?fromStationCode=HN&toStationCode=SG&departureDate=2026-02-14"` trả về SE1 và SE2 |
| 6 | `TripService.getTrip` + `TripController GET /trips/{tripId}` | `curl localhost:8081/api/v1/trips/1` trả về 20 ga, 13 toa, 506 chỗ |

**Cửa ngưỡng:** cả hai endpoint trả về JSON đúng. Kiểm tra `fromStationIndex` và
`toStationIndex` trong response tìm kiếm khớp với index thực tế trong response chi tiết.

---

### Bước 2 — `inventory-service`

**Phụ thuộc vào: `schedule-service` đang chạy và có dữ liệu.**

#### Mục đích nghiệp vụ

Đây là service khó nhất và quan trọng nhất. Nó thực thi quy tắc không thể vi phạm:
**không có hai hành khách nào được ngồi cùng một chỗ trên cùng một chặng đường**.

Một chỗ nằm không đơn giản là "đã bán" hay "còn trống" — nó có thể bị chiếm một phần.
Hành khách A đặt HN→Huế (chặng 0–5), hành khách B đặt Đà Nẵng→SG (chặng 10–18).
Họ có thể dùng chung cùng một chỗ nằm vì hành trình không chồng lên nhau. Database
phải theo dõi theo từng chặng, không phải theo chuyến tàu.

Giải pháp là bitmask: mỗi bit đại diện cho một chặng. Một chỗ với `occupied_mask
= 0b0000000000011111` (bit 0–4 được set) đã bán cho chặng 0–4 và còn trống cho chặng 5–18.

Còn có mask thứ hai — `held_mask` — cho các chỗ đã được giữ nhưng chưa thanh toán.
Khi ai đó nhấn "Đặt vé", chỗ được giữ 15 phút trong khi họ thanh toán. Trong thời
gian đó chỗ không thể bán cho người khác, nhưng cũng không phải đã chiếm vĩnh viễn.
Nếu thanh toán thất bại, hold hết hạn và các bit được giải phóng.

**Bất biến: `(occupied_mask & held_mask) == 0` luôn luôn đúng.** Một bit không thể
vừa bị chiếm vĩnh viễn vừa đang được giữ tạm thời — điều đó có nghĩa là cùng một chặng
vừa đã bán vừa đang chờ thanh toán.

#### Tại sao phải copy metadata chỗ nằm vào `inventorydb`

`inventory-service` phải trả về số toa, số chỗ, hạng, và giá khi response
`/availability`. Dữ liệu đó nằm trong `scheduledb`. Gọi `schedule-service` mỗi
lần có request availability là thêm một network hop trên đường đi nóng nhất của hệ thống.

Thay vào đó, copy các field tĩnh (carriage_no, berth_no, berth_class, level, price_vnd)
vào `berth_inventory` khi seed. Những field này không bao giờ thay đổi — vị trí vật lý
và giá của một chỗ không đổi sau khi chuyến tàu được công bố.

#### Vấn đề mà query availability phải giải quyết

Cho `fromStationIndex` và `toStationIndex`, tính journey bitmask:
```
journeyMask = ((1 << (toIndex - fromIndex)) - 1) << fromIndex

HN(0) → SG(19):   bit 0–18 = 0b1111111111111111111  (19 bit)
HN(0) → Huế(9):   bit 0–8  = 0b0000000001111111111  (9 bit)
Huế(9) → SG(19):  bit 9–18 = 0b1111111110000000000  (10 bit)
```

Một chỗ còn trống cho hành trình này nếu:
```
(occupied_mask | held_mask) & journeyMask == 0
```

Phép AND đơn đó là toàn bộ logic kiểm tra xung đột.

#### Vấn đề mà hold phải giải quyết — và tại sao phải làm đúng

Khi người dùng nhấn "Giữ chỗ", hệ thống cần:

1. Tìm chỗ trống nguyên tử — hai người dùng không được lấy cùng một chỗ
2. Đánh dấu là đang giữ — flip bit trong `held_mask`
3. Ghi lại hold với thời gian hết hạn — 15 phút

**`SELECT FOR UPDATE SKIP LOCKED`** là câu trả lời của stage 0 cho tính nguyên tử.
Chỉ một transaction có thể giữ lock trên một hàng `berth_inventory` tại một thời điểm.
`SKIP LOCKED` có nghĩa là các request cạnh tranh chuyển sang chỗ trống tiếp theo thay
vì xếp hàng chờ — điều đó sẽ khiến service bị treo khi tải cao.

**Idempotency** bắt buộc vì client (`booking-service`) cuối cùng sẽ thêm retry logic
(stage 3). Nếu cùng một `Idempotency-Key` đến hai lần, trả về hold gốc — không tạo
hai hold cho cùng một request.

#### Vấn đề mà job hết hạn hold phải giải quyết

Hold chỉ là tạm thời. Nếu người dùng bỏ qua quá trình thanh toán, chỗ phải trả về
tồn kho. Job `@Scheduled` chạy mỗi 30 giây, tìm các hold đã quá `expires_at`, xóa
các bit `held_mask`, và xóa các hàng.

Nếu không có job này, các hold bị bỏ lại sẽ khóa chỗ mãi mãi và hệ thống trông
như đã hết chỗ trong khi thực tế thì không.

#### Cấu trúc package

```
vn.railticketing.inventory
├── InventoryServiceApplication.java
├── domain/
│   ├── BerthInventory.java
│   ├── Hold.java
│   └── HoldBerth.java
├── repository/
│   ├── BerthInventoryRepository.java
│   └── HoldRepository.java
├── service/
│   ├── AvailabilityService.java
│   ├── HoldService.java
│   └── HoldExpiryJob.java
├── client/
│   └── ScheduleClient.java      ← RestClient gọi schedule-service
├── infra/
│   └── DataSeeder.java          ← seed berth_inventory khi khởi động
└── web/
    ├── AvailabilityController.java
    ├── HoldController.java
    └── dto/
        ├── AvailabilityResponse.java
        ├── BerthDto.java
        ├── CreateHoldRequest.java
        └── HoldResponse.java
```

#### Cách triển khai từng class

**`BerthInventory`** — `@Entity @Table(name = "berth_inventory")`
- `@Id Long berthId` — không có `@GeneratedValue`, được seed từ berth_id của schedule
- Fields: `tripId`, `carriageNo`, `berthNo`, `berthClass`, `level`, `priceVnd`
- `int occupiedMask`, `int heldMask`
- `@Version int version` — Hibernate optimistic lock; cũng là lưới an toàn bổ sung cho `SELECT FOR UPDATE`

**`Hold`** — `@Entity @Table(name = "hold")`
- `@Id UUID holdId` — `@GeneratedValue` với UUID strategy
- Fields: `tripId`, `fromStationIndex`, `toStationIndex`, `expiresAt` (`Instant`)
- `@Column(unique=true) UUID idempotencyKey`
- `@OneToMany(mappedBy="hold", cascade=ALL) List<HoldBerth> berths`

**`HoldBerth`** — `@Entity @Table(name = "hold_berth")`
- Khóa chính composite hoặc surrogate: `holdId` + `berthId`
- `@ManyToOne Hold hold`
- `Long berthId`

**`BerthInventoryRepository`**
- Query availability — tìm chỗ trống dùng bitmask. Viết dưới dạng `@Query`:
  ```
  SELECT b FROM BerthInventory b
  WHERE b.tripId = :tripId
    AND (b.occupiedMask | b.heldMask) & :journeyMask = 0
  ```
- Query lock để tạo hold — cùng filter nhưng với pessimistic write lock và SKIP LOCKED.
  Dùng `@Lock(LockModeType.PESSIMISTIC_WRITE)` và thêm query hint
  `jakarta.persistence.lock.timeout = -2` (giá trị `-2` nghĩa là SKIP LOCKED trong Hibernate).
  Thêm tham số `Pageable` để giới hạn `quantity` hàng:
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("SELECT b FROM BerthInventory b WHERE b.tripId = :tripId AND (b.occupiedMask | b.heldMask) & :journeyMask = 0")
  List<BerthInventory> lockAvailableForJourney(long tripId, int journeyMask, Pageable pageable);
  ```

**`HoldRepository`**
- `findByIdempotencyKey(UUID key)` — để kiểm tra idempotency
- `findAllByExpiresAtBefore(Instant now)` — cho job hết hạn

**`ScheduleClient`**
- `@Component` bọc `RestClient` trỏ tới `${clients.schedule.base-url}`
- Hai method: `getTrips()` trả về danh sách trips, `getTrip(long tripId)` trả về chi tiết trip
- Chỉ dùng bởi `DataSeeder` khi khởi động

**`DataSeeder`** — `@Component implements CommandLineRunner`
- `run()` gọi `scheduleClient.getTrips()` để lấy tất cả trip IDs
- Với mỗi trip, gọi `scheduleClient.getTrip(id)` để lấy tất cả chỗ nằm
- Với mỗi chỗ, gọi `berthInventoryRepository.existsById(berth.berthId())` — bỏ qua nếu đã seed
- Insert `BerthInventory` với `occupiedMask = 0`, `heldMask = 0`, copy các field tĩnh
- Bọc insert trong `@Transactional`

**`AvailabilityService`**
- `getAvailability(tripId, fromIndex, toIndex)`:
  1. Tính `journeyMask = ((1 << (toIndex - fromIndex)) - 1) << fromIndex`
  2. Query `berthInventoryRepository` với bitmask filter
  3. Map sang list `BerthDto`
  4. Đếm theo hạng (class)
  5. Trả về `AvailabilityResponse`

**`HoldService`**
- `createHold(request, idempotencyKey)`:
  1. Kiểm tra `holdRepository.findByIdempotencyKey(key)` — nếu tìm thấy, trả về (idempotent)
  2. Tính `journeyMask`
  3. Gọi `lockAvailableForJourney(tripId, journeyMask, Pageable.ofSize(quantity))` bên trong `@Transactional`
  4. Nếu số kết quả < quantity → throw 409
  5. Với mỗi chỗ bị lock: `berth.setHeldMask(berth.getHeldMask() | journeyMask)`
  6. Lưu berths, insert hàng `Hold` + `HoldBerth`
  7. Trả về `HoldResponse`
- `releaseHold(holdId)`:
  1. Load hold — nếu không tìm thấy, return (idempotent 204)
  2. Load mỗi berth theo berthId, tính `journeyMask`, áp dụng `heldMask &= ~journeyMask`
  3. Lưu berths, xóa hàng `HoldBerth`, xóa `Hold`
  4. Tất cả trong `@Transactional`

**`HoldExpiryJob`**
- Method `@Scheduled(fixedDelay = 30_000)`
- Tìm tất cả holds có `expiresAt < Instant.now()`
- Với mỗi hold: gọi cùng logic release như `releaseHold`
- Xử lý một hold per transaction — không bọc tất cả expired holds trong một transaction lớn

**`AvailabilityController`** — `@RestController`
- `GET /api/v1/trips/{tripId}/availability` — delegate sang `availabilityService`

**`HoldController`** — `@RestController`
- `POST /api/v1/holds` — đọc `Idempotency-Key` từ header với `@RequestHeader`, delegate sang `holdService`
- `DELETE /api/v1/holds/{holdId}` — delegate sang `holdService.releaseHold`, luôn trả về 204

#### Thứ tự triển khai

| # | Viết gì | Kiểm tra |
|---|---|---|
| 1 | Flyway `V1__init.sql` — `berth_inventory`, `hold`, `hold_berth` | Service khởi động |
| 2 | `ScheduleClient` + `DataSeeder` | `berth_inventory` có 1012 hàng sau khi khởi động |
| 3 | Entity `BerthInventory`, `Hold`, `HoldBerth` | — |
| 4 | `BerthInventoryRepository`, `HoldRepository` | — |
| 5 | `AvailabilityService` + `AvailabilityController` | `curl localhost:8082/api/v1/trips/1/availability?fromStationIndex=0&toStationIndex=19` trả về 506 chỗ trống |
| 6 | `HoldService.createHold` + `HoldController POST /holds` | Bit `held_mask` flip trong DB; số lượng availability giảm |
| 7 | `HoldService.releaseHold` + `HoldController DELETE /holds/{id}` | Bit xóa; availability phục hồi |
| 8 | `HoldExpiryJob` | Tạm thời set TTL là 1 phút, đợi 30 giây, kiểm tra bit tự động được giải phóng |

**Cửa ngưỡng 1:** giữ một chỗ, kiểm tra availability giảm. Giải phóng, kiểm tra phục hồi.

**Cửa ngưỡng 2 (quan trọng nhất):** giữ chỗ A cho HN→SG. Trong khi đang giữ, thử
giữ cùng chỗ đó cho HN→SG lần nữa. Phải nhận **409**. Đây là bằng chứng bitmask
conflict check hoạt động đúng.

**Cửa ngưỡng 3:** giữ một chỗ, không làm gì, đợi job quét. Kiểm tra availability
phục hồi mà không cần gọi delete thủ công.

---

### Bước 3 — `booking-service`

**Phụ thuộc vào: bước 1 và 2 đã hoàn thành và cả hai service đang chạy.**

#### Mục đích nghiệp vụ

Booking-service là saga orchestrator. Nó điều phối toàn bộ luồng mua vé qua
inventory và database của chính nó:

```
Người dùng gửi booking
  → inventory-service tạo hold    (chỗ được giữ, đồng hồ 15 phút bắt đầu)
  → booking-service ghi lại đơn  (tên hành khách, tổng giá)

Người dùng gửi thanh toán
  → thanh toán giả: 90% thành công
  → thành công: đơn xác nhận, vé được phát
  → thất bại: đơn bị hủy, hold được giải phóng, chỗ trả về
```

Booking-service không sở hữu trạng thái tồn kho — nó ủy quyền hoàn toàn cho
inventory-service. Database của nó ghi lại ai mua gì và đơn đang ở trạng thái nào.

#### Tại sao không có `@Transactional` qua HTTP call

Lời gọi tới `inventory-service` và lần ghi DB local không thể chia sẻ một database
transaction — chúng ở hai database khác nhau. Điều này có nghĩa là lỗi giữa hai bước
tạo ra sự không nhất quán:

- Hold được tạo trong `inventorydb` → sau đó insert booking thất bại trong `bookingdb` → hold bị bỏ lại

Câu trả lời của stage 0 là **naive compensation**: nếu insert booking thất bại, gọi
`DELETE /holds/{holdId}`. Nếu lời gọi đó cũng thất bại, TTL 15 phút của hold sẽ tự
dọn dẹp. Bạn mất tối đa 15 phút trên một vài chỗ. Không có saga, không có outbox —
TTL đang làm việc thay.

Đây là thiếu sót có chủ ý. Stage 5 thay thế bằng saga thực sự thu hẹp cửa sổ mất
mát từ 15 phút xuống còn vài giây.

#### Vấn đề mà confirm phải giải quyết

Thanh toán ở stage 0 là `Math.random() < 0.9`. Điểm mấu chốt không phải là thanh toán
mà là xử lý đúng cả hai kết quả:

- **Thành công:** đơn là vĩnh viễn. Hold phải được giải phóng (chỗ được ghi nhận là
  đã chiếm vĩnh viễn qua `occupied_mask`). Vé được phát hành.
- **Thất bại:** đơn chết. Hold phải được giải phóng để hành khách khác có thể lấy chỗ đó.

Trong cả hai trường hợp response là **200**, không phải 4xx. Thanh toán thất bại là
một kết quả hợp lệ, được mong đợi — không phải là ngoại lệ.

**Ghi chú về `occupied_mask` ở stage 0:** cách đơn giản nhất là khi booking được xác
nhận, gọi `DELETE /holds/{holdId}` để giải phóng hold, và riêng biệt update
`occupied_mask |= journeyMask` cho mỗi chỗ. Giải phóng hold xóa `held_mask`; update
occupied set `occupied_mask`. Cả hai phải xảy ra nguyên tử trong `inventory-service`.

#### Cấu trúc package

```
vn.railticketing.booking
├── BookingServiceApplication.java
├── domain/
│   ├── Booking.java
│   └── Ticket.java
├── repository/
│   ├── BookingRepository.java
│   └── TicketRepository.java
├── service/
│   └── BookingService.java
├── client/
│   ├── InventoryClient.java
│   └── dto/
│       ├── HoldResponse.java      ← mirror của HoldResponse trong inventory-service
│       └── CreateHoldRequest.java ← mirror của CreateHoldRequest trong inventory-service
└── web/
    ├── BookingController.java
    └── dto/
        ├── CreateBookingRequest.java
        ├── ConfirmBookingRequest.java
        └── BookingResponse.java
```

#### Cách triển khai từng class

**`Booking`** — `@Entity @Table(name = "booking")`
- `@Id UUID bookingId` — `@GeneratedValue` UUID
- `status` — String, các giá trị: `PENDING_PAYMENT`, `CONFIRMED`, `PAYMENT_FAILED`, `EXPIRED`, `CANCELLED`
- `tripId`, `holdId` (nullable `UUID`), `totalPriceVnd`
- Contact fields inline: `contactName`, `contactPhone`, `contactEmail`
- `expiresAt` (`Instant`, nullable — xóa khi confirm)
- `@Column(unique=true) UUID idempotencyKey`
- `createdAt` (`Instant`, default `now()`)
- `@OneToMany(mappedBy="booking", cascade=ALL) List<Ticket> tickets`

**`Ticket`** — `@Entity @Table(name = "ticket")`
- `@Id UUID ticketId` — `@GeneratedValue` UUID
- `@ManyToOne Booking booking`
- `berthId`, `carriageNo`, `berthNo`
- `passengerName`, `passengerIdNumber`, `passengerType`
- `priceVnd`
- `status` — default `ISSUED`

**`BookingRepository`**
- `findByIdempotencyKey(UUID key)` — để kiểm tra idempotency khi tạo
- Standard `findById` cho get và confirm

**`InventoryClient`** — `@Component` bọc `RestClient`
- Base URL từ `${clients.inventory.base-url}`
- `createHold(CreateHoldRequest, idempotencyKey)` → trả về `HoldResponse`
- `releaseHold(UUID holdId)` → void, nuốt 404 (idempotent)
- `confirmHold(UUID holdId, int journeyMask)` → báo cho inventory chuyển bit `held_mask` sang `occupied_mask`
  (thêm endpoint này vào `inventory-service` — `POST /api/v1/holds/{holdId}/confirm`)

> **Cách đơn giản hơn ở stage 0:** bỏ qua `confirmHold` hoàn toàn. Khi booking được
> xác nhận, chỉ gọi `releaseHold` để xóa `held_mask`. Chưa set `occupied_mask`.
> Query gate ở cuối sẽ cho bạn biết double-selling có xảy ra trong thực tế không.
> Thêm việc promote `occupied_mask` ở stage 1 khi viết invariant verifier.

**`BookingService`**
- `createBooking(request, idempotencyKey)`:
  1. Kiểm tra idempotency — trả về existing nếu key đã thấy
  2. Gọi `inventoryClient.createHold(...)` — dùng UUID mới làm idempotency key của hold
  3. Nếu `HttpClientErrorException` (4xx từ inventory) → propagate thành 409 cho caller
  4. Mở `@Transactional`, insert hàng `Booking` + `Ticket`
  5. Nếu transaction throw → gọi `inventoryClient.releaseHold(holdId)` trong `catch` block (naive compensation)
  6. Trả về booking đã lưu
- `confirmBooking(bookingId, idempotencyKey)`:
  1. Kiểm tra idempotency
  2. Load booking — 404 nếu không tìm thấy
  3. Kiểm tra `status == PENDING_PAYMENT` — 409 nếu không thể confirm
  4. Mock payment: `Math.random() < mockSuccessRate` (đọc rate từ `@Value("${booking.payment.mock-success-rate}")`)
  5. Thành công: update status → `CONFIRMED`, xóa `holdId`, xóa `expiresAt`, gọi `inventoryClient.releaseHold`
  6. Thất bại: update status → `PAYMENT_FAILED`, gọi `inventoryClient.releaseHold`
  7. Lưu, trả về booking — **luôn 200**
- `getBooking(bookingId)`:
  - Load booking với tickets, 404 nếu không tìm thấy

**`BookingController`** — `@RestController @RequestMapping("/api/v1/bookings")`
- `POST /` — đọc header `Idempotency-Key`, delegate sang service, trả về 201
- `POST /{bookingId}/confirm` — đọc header `Idempotency-Key`, trả về 200
- `GET /{bookingId}` — trả về 200

#### Thứ tự triển khai

| # | Viết gì | Kiểm tra |
|---|---|---|
| 1 | Flyway `V1__init.sql` — `booking`, `ticket` | Service khởi động |
| 2 | Entity `Booking`, `Ticket` | — |
| 3 | `BookingRepository`, `TicketRepository` | — |
| 4 | DTOs: `CreateBookingRequest`, `ConfirmBookingRequest`, `BookingResponse` | — |
| 5 | `InventoryClient` với `createHold` và `releaseHold` | — |
| 6 | `BookingService.createBooking` + `BookingController POST /bookings` | Hàng booking trong DB; hold hiển thị trong `inventorydb` |
| 7 | `BookingService.confirmBooking` + `BookingController POST /bookings/{id}/confirm` | Chạy 10 lần, kỳ vọng ~9 CONFIRMED và ~1 PAYMENT_FAILED |
| 8 | `BookingService.getBooking` + `BookingController GET /bookings/{id}` | Trả về booking + tickets |

**Cửa ngưỡng:** chạy toàn bộ luồng 10 lần. Kiểm tra không có booking nào đã confirm
chia sẻ chỗ với booking khác đã confirm trên các chặng chồng lên nhau. Query `inventorydb`:

```sql
SELECT berth_id, occupied_mask FROM berth_inventory WHERE trip_id = 1;
-- Không có hai vé đã confirm nào được phép có bit chồng lên nhau trong occupied_mask trên cùng berth_id
```

---

### Bước 4 — `api-gateway`

**Phụ thuộc vào: bước 1–3. Không có business logic ở đây.**

#### Mục đích nghiệp vụ

Một điểm vào duy nhất cho tất cả clients. Client chỉ nói chuyện với một host trên
một port (8080) và không bao giờ cần biết service nào xử lý từng đường dẫn.

Gateway cũng tạo và truyền `X-Correlation-Id`. Khi một request chạm vào cả ba service,
bạn có ba file log riêng biệt. Correlation ID là cách duy nhất để khớp ba dòng log
thuộc về cùng một request. Nếu không có nó, debug một booking thất bại nghĩa là tìm
kiếm ba log mà không có liên kết nào giữa chúng.

#### Cấu trúc package

```
vn.railticketing.gateway
├── ApiGatewayApplication.java
└── filter/
    └── CorrelationIdFilter.java
```

Route nằm hoàn toàn trong `application.yml` — không cần Java route config.

#### Cách triển khai từng class

**Routes trong `application.yml`** — Spring Cloud Gateway MVC (không phải reactive).
Định nghĩa bốn routes. Thứ tự quan trọng: cụ thể nhất trước.

```yaml
spring:
  cloud:
    gateway:
      mvc:
        routes:
          - id: availability
            uri: ${INVENTORY_URL:http://localhost:8082}
            predicates:
              - Path=/api/v1/trips/*/availability
          - id: holds
            uri: ${INVENTORY_URL:http://localhost:8082}
            predicates:
              - Path=/api/v1/holds/**
          - id: bookings
            uri: ${BOOKING_URL:http://localhost:8083}
            predicates:
              - Path=/api/v1/bookings/**
          - id: schedule
            uri: ${SCHEDULE_URL:http://localhost:8081}
            predicates:
              - Path=/api/v1/trips/**
```

Route `availability` **phải** xuất hiện trước `schedule` — cả hai khớp với
`/api/v1/trips/**` nhưng `availability` cụ thể hơn.

**`CorrelationIdFilter`** — `@Component implements HandlerInterceptor`
- `preHandle`: đọc `X-Correlation-Id` từ request; nếu không có, tạo `UUID.randomUUID().toString()`; lưu vào `MDC.put("correlationId", id)` và forward trong header request được proxy
- `afterCompletion`: thêm cùng giá trị vào response header; gọi `MDC.clear()`

MDC key `correlationId` khớp với logging pattern đã có trong `application.yml`:
```
%X{correlationId:-}
```
nên ID sẽ tự động xuất hiện trong mỗi dòng log.

#### Thứ tự triển khai

| # | Viết gì | Kiểm tra |
|---|---|---|
| 1 | Route table trong `application.yml` | Cùng các curl như trên nhưng trên port 8080 |
| 2 | `CorrelationIdFilter` | `curl -v localhost:8080/api/v1/trips/1` — response có header `X-Correlation-Id` |

**Cửa ngưỡng:** lặp lại toàn bộ chuỗi curl smoke test (bên dưới) hoàn toàn trên port 8080.
Mỗi response phải có `X-Correlation-Id` trong response headers.

---

### Smoke test đầu cuối (tất cả bốn service đang chạy)

```bash
# 1. Tìm kiếm
curl "localhost:8080/api/v1/trips?fromStationCode=HN&toStationCode=SG&departureDate=2026-02-14"
# ghi lại tripId và fromStationIndex / toStationIndex từ response

# 2. Giữ chỗ
curl -X POST localhost:8080/api/v1/holds \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "X-Identity: test-user-1" \
  -H "Content-Type: application/json" \
  -d '{"tripId":1,"fromStationIndex":0,"toStationIndex":19,"quantity":1}'
# ghi lại holdId

# 3. Đặt vé
curl -X POST localhost:8080/api/v1/bookings \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "X-Identity: test-user-1" \
  -H "Content-Type: application/json" \
  -d '{"holdId":"<holdId>","contact":{"fullName":"Nguyen Van A","phone":"0901234567"},"passengers":[{"fullName":"Nguyen Van A","idNumber":"001234567890","passengerType":"ADULT"}]}'
# ghi lại bookingId

# 4. Xác nhận thanh toán
curl -X POST localhost:8080/api/v1/bookings/<bookingId>/confirm \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "X-Identity: test-user-1" \
  -H "Content-Type: application/json" \
  -d '{"paymentMethod":"MOCK"}'
```

---

## Xử lý lỗi và API response

### Bật RFC 9457 trong mỗi service

Thêm vào `application.yml` của mỗi service (đã có sẵn trong scaffold):

```yaml
server:
  error:
    include-message: never   # không bao giờ để lộ raw exception message
spring:
  mvc:
    problemdetails:
      enabled: true          # Spring tự chuyển đổi exception chuẩn sang RFC 9457
```

Với `problemdetails.enabled: true`, Spring tự động chuyển đổi
`MethodArgumentNotValidException` (400), `NoHandlerFoundException` (404),
`HttpMessageNotReadableException` (400), và các exception khác sang `application/problem+json`.
Bạn chỉ cần xử lý các domain exception của riêng mình.

---

### Quy ước success response

| Endpoint | Status | Body |
|---|---|---|
| `GET /trips` | 200 | `{ "trips": [ TripSummaryDto, ... ] }` — có wrapper theo OpenAPI |
| `GET /trips/{id}` | 200 | `TripDetailDto` — trực tiếp |
| `GET /trips/{id}/availability` | 200 | `AvailabilityResponse` — trực tiếp |
| `POST /holds` | **201** | `HoldResponse` — trực tiếp |
| `DELETE /holds/{id}` | **204** | không có body |
| `POST /bookings` | **201** | `BookingResponse` — trực tiếp |
| `POST /bookings/{id}/confirm` | **200** | `BookingResponse` — luôn 200, kiểm tra field `status` |
| `GET /bookings/{id}` | 200 | `BookingResponse` — trực tiếp |

Dùng `ResponseEntity<T>` khi bạn cần kiểm soát status code (201, 204).
Với 200 bạn có thể trả về DTO trực tiếp từ controller method.

---

### Cấu trúc package cho exceptions

Mỗi service có một package `exception/` và một `GlobalExceptionHandler`:

```
vn.railticketing.<service>
├── exception/
│   ├── <domain exceptions — mỗi class một file>
└── web/
    └── GlobalExceptionHandler.java
```

---

### Domain exceptions — một class per trường hợp lỗi

Định nghĩa mỗi class là `RuntimeException` subclass đơn giản. Giữ chúng nhỏ gọn —
chỉ đủ fields để tạo thông báo lỗi có nghĩa. Handler làm việc mapping HTTP.

**Exceptions của `schedule-service`:**

| Class | HTTP | Problem type |
|---|---|---|
| `TripNotFoundException` | 404 | `problems/trip-not-found` |
| `StationNotFoundException` | 404 | `problems/station-not-found` |

**Exceptions của `inventory-service`:**

| Class | HTTP | Problem type |
|---|---|---|
| `TripNotFoundException` | 404 | `problems/trip-not-found` |
| `InsufficientInventoryException` | 409 | `problems/insufficient-inventory` |
| `HoldNotFoundException` | 404 | `problems/hold-not-found` |
| `DuplicateHoldException` | 409 | `problems/duplicate-hold` — cùng idempotency key với body khác nhau |

**Exceptions của `booking-service`:**

| Class | HTTP | Problem type |
|---|---|---|
| `HoldExpiredException` | 409 | `problems/hold-expired` — hold không tìm thấy khi tạo booking |
| `BookingNotFoundException` | 404 | `problems/booking-not-found` |
| `BookingNotConfirmableException` | 409 | `problems/booking-not-confirmable` |
| `InventoryServiceException` | 503 | `problems/inventory-unavailable` — inventory-service trả về 5xx hoặc không có mặt |

**Cách viết mỗi exception class:**

```
public class InsufficientInventoryException extends RuntimeException {
    private final int requested;
    private final int available;

    public InsufficientInventoryException(int requested, int available) {
        super("Yêu cầu " + requested + " chỗ, chỉ còn " + available + " trống");
        this.requested = requested;
        this.available = available;
    }
    // getters cho requested, available
}
```

Với các trường hợp đơn giản (TripNotFoundException) một field `id` và message là đủ.

---

### Global exception handler

Một class `@RestControllerAdvice` per service, trong `web/GlobalExceptionHandler.java`.
Extend `ResponseEntityExceptionHandler` để các mapping exception có sẵn của Spring
(validation, method-not-allowed, v.v.) được kế thừa.

**Pattern cho mỗi handler method:**

```
@ExceptionHandler(InsufficientInventoryException.class)
ProblemDetail handle(InsufficientInventoryException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    pd.setType(URI.create("https://railticketing.vn/problems/insufficient-inventory"));
    pd.setTitle("Insufficient inventory");
    pd.setInstance(URI.create(req.getRequestURI()));
    pd.setProperty("traceId", MDC.get("correlationId"));
    return pd;
}
```

Trả về `ProblemDetail` trực tiếp — Spring serialize nó thành `application/problem+json`
tự động khi `problemdetails.enabled: true`.

**Handler đầy đủ cho `inventory-service`:**

```
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(TripNotFoundException.class)
    ProblemDetail handleTripNotFound(TripNotFoundException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://railticketing.vn/problems/trip-not-found"));
        pd.setTitle("Trip not found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("traceId", MDC.get("correlationId"));
        return pd;
    }

    @ExceptionHandler(InsufficientInventoryException.class)
    ProblemDetail handleInsufficientInventory(InsufficientInventoryException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://railticketing.vn/problems/insufficient-inventory"));
        pd.setTitle("Insufficient inventory");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("traceId", MDC.get("correlationId"));
        return pd;
    }

    @ExceptionHandler(HoldNotFoundException.class)
    ProblemDetail handleHoldNotFound(HoldNotFoundException ex, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://railticketing.vn/problems/hold-not-found"));
        pd.setTitle("Hold not found");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("traceId", MDC.get("correlationId"));
        return pd;
    }
}
```

Áp dụng cùng pattern cho `schedule-service` và `booking-service` với các exception
class của chúng.

---

### Validation request

Annotate mỗi field trong request DTO với Bean Validation constraints và thêm
`@Valid` vào controller parameter. Spring + `problemdetails.enabled` tự chuyển đổi
`MethodArgumentNotValidException` thành 400 RFC 9457 response.

```
// DTO
public record CreateHoldRequest(
    @NotNull Long tripId,
    @Min(0) int fromStationIndex,
    @Min(1) int toStationIndex,
    @Min(1) @Max(4) int quantity,
    String preferredClass   // nullable
) {}

// Controller
@PostMapping
ResponseEntity<HoldResponse> createHold(
    @Valid @RequestBody CreateHoldRequest req,
    @RequestHeader("Idempotency-Key") UUID idempotencyKey
) { ... }
```

Validation cross-field (ví dụ `fromStationIndex < toStationIndex`) không thể biểu
diễn bằng Bean Validation — kiểm tra trong service layer và throw
`IllegalArgumentException` mô tả rõ ràng, handler sẽ map thành 400.

---

### Hình dạng error response

Mọi lỗi từ mọi service phải trông như thế này:

```json
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{
  "type": "https://railticketing.vn/problems/insufficient-inventory",
  "title": "Insufficient inventory",
  "status": 409,
  "detail": "Yêu cầu 2 chỗ, chỉ còn 0 trống cho chặng 0..18",
  "instance": "/api/v1/holds",
  "traceId": "0af7651916cd43dd8448eb211c80319c"
}
```

`traceId` là `X-Correlation-Id` từ request — đọc từ `MDC` trong handler.
Gateway set nó; mỗi service downstream truyền qua MDC.

---

## Ports và databases

| Service | Port | Database | DB user |
|---|---|---|---|
| `api-gateway` | 8080 | — | — |
| `schedule-service` | 8081 | `scheduledb` | `schedule_user` |
| `inventory-service` | 8082 | `inventorydb` | `inventory_user` |
| `booking-service` | 8083 | `bookingdb` | `booking_user` |

**IDE run config** — thêm `DB_PORT=5432` vào environment variables của mỗi service.
`application.yml` mặc định là `5433`; Docker Compose expose Postgres trên `5432`.

---

## Quy tắc chung — phải làm đúng ngay từ đầu

Những điều này rẻ để thêm bây giờ và rất đắt để retrofit sau.

### 1. Idempotency key

Mọi `POST` ghi state đều yêu cầu `Idempotency-Key: <uuid>` trong header.
Lưu key trong DB với UNIQUE constraint. Khi trùng: trả về response gốc, không thực thi lại.

### 2. RFC 9457 errors

Tất cả error response phải dùng `Content-Type: application/problem+json`. Xem phần
"Xử lý lỗi và API response" ở trên.

### 3. Không query cross-DB

`inventory-service` và `booking-service` không bao giờ được query `scheduledb`.
Mỗi service sở hữu đúng một database user; DB từ chối các user khác (xem
`infra/postgres/init/01-databases.sql`).

### 4. Không `@Transactional` qua network call

Chỉ bọc lần ghi DB local trong transaction. HTTP call tới service khác xảy ra
bên ngoài — trước hoặc sau — ranh giới transaction.

### 5. Quy ước đặt tên field

Từ OpenAPI contract:
- Đơn vị trong tên field: `priceVnd`, `durationMinutes`, `ttlSeconds`
- Calendar date: `*Date` (`2026-02-14`)
- Instant: `*At` (RFC 3339 UTC, ví dụ `2026-02-14T06:00:00Z`)

---

## Hoàn thành khi (Checklist Stage 0)

- [ ] `curl` qua tất cả 8 endpoint hoạt động đầu cuối
- [ ] Double booking cùng chỗ trên các chặng chồng lên nhau bị từ chối (409)
- [ ] Hold hết hạn trả lại chỗ về tồn kho (kiểm tra bằng cách query availability lại)
- [ ] Tắt `inventory-service` khiến `booking-service` trả về lỗi rõ ràng, không treo
- [ ] Query của `booking_user` vào `inventorydb` trả về `FATAL: permission denied`
- [ ] `docker compose up` khởi động bốn service và ba database sạch sẽ

---

**Quay lại:** [README](../README.md) · [05 — Build Progression](05-build-progression.md)
