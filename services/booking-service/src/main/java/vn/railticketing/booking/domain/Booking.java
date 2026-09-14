package vn.railticketing.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "booking")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(nullable = false)
    private String status;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "contact_phone", nullable = false)
    private String contactPhone;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "total_price_vnd", nullable = false)
    private Long totalPriceVnd;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Ticket> tickets = new ArrayList<>();

    public UUID getBookingId()       { return bookingId; }
    public String getStatus()        { return status; }
    public Long getTripId()          { return tripId; }
    public UUID getHoldId()          { return holdId; }
    public String getContactName()   { return contactName; }
    public String getContactPhone()  { return contactPhone; }
    public String getContactEmail()  { return contactEmail; }
    public Long getTotalPriceVnd()   { return totalPriceVnd; }
    public Instant getExpiresAt()    { return expiresAt; }
    public UUID getIdempotencyKey()  { return idempotencyKey; }
    public Instant getCreatedAt()    { return createdAt; }
    public List<Ticket> getTickets() { return tickets; }

    public void setStatus(String status)       { this.status = status; }
    public void setHoldId(UUID holdId)         { this.holdId = holdId; }
    public void setExpiresAt(Instant expiresAt){ this.expiresAt = expiresAt; }

    public static Booking create(Long tripId, UUID holdId, String contactName,
                                  String contactPhone, String contactEmail,
                                  Long totalPriceVnd, Instant expiresAt,
                                  UUID idempotencyKey) {
        Booking b = new Booking();
        b.status         = "PENDING_PAYMENT";
        b.tripId         = tripId;
        b.holdId         = holdId;
        b.contactName    = contactName;
        b.contactPhone   = contactPhone;
        b.contactEmail   = contactEmail;
        b.totalPriceVnd  = totalPriceVnd;
        b.expiresAt      = expiresAt;
        b.idempotencyKey = idempotencyKey;
        return b;
    }
}
