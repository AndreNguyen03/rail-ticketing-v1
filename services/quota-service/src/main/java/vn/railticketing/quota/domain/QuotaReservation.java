package vn.railticketing.quota.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quota_reservation")
public class QuotaReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reservation_id")
    private UUID reservationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_window_id", nullable = false)
    private SaleWindow saleWindow;

    @Column(name = "passenger_id_number", nullable = false, length = 12)
    private String passengerIdNumber;

    @Column(name = "from_station_index", nullable = false)
    private short fromStationIndex;

    @Column(name = "to_station_index", nullable = false)
    private short toStationIndex;

    // Stores the idempotencyKey from booking-service (same UUID used as the quota key).
    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "ticket_count", nullable = false)
    private int ticketCount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QuotaReservation() {}

    public static QuotaReservation create(SaleWindow window,
                                          String passengerIdNumber,
                                          short fromStationIndex,
                                          short toStationIndex,
                                          UUID bookingId,
                                          int ticketCount) {
        QuotaReservation r = new QuotaReservation();
        r.saleWindow        = window;
        r.passengerIdNumber = passengerIdNumber;
        r.fromStationIndex  = fromStationIndex;
        r.toStationIndex    = toStationIndex;
        r.bookingId         = bookingId;
        r.ticketCount       = ticketCount;
        r.status            = "ACTIVE";
        r.createdAt         = Instant.now();
        return r;
    }

    public void release() { this.status = "RELEASED"; }

    public UUID   getReservationId()     { return reservationId; }
    public SaleWindow getSaleWindow()    { return saleWindow; }
    public String getPassengerIdNumber() { return passengerIdNumber; }
    public short  getFromStationIndex()  { return fromStationIndex; }
    public short  getToStationIndex()    { return toStationIndex; }
    public UUID   getBookingId()         { return bookingId; }
    public int    getTicketCount()       { return ticketCount; }
    public String getStatus()            { return status; }
    public Instant getCreatedAt()        { return createdAt; }
}
