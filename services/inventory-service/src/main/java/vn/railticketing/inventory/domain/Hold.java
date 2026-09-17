package vn.railticketing.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "hold")
public class Hold {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "from_station_index", nullable = false)
    private short fromStationIndex;

    @Column(name = "to_station_index", nullable = false)
    private short toStationIndex;

    // Precomputed mask: release/commit skip recompute.
    @Column(name = "journey_mask", nullable = false)
    private int journeyMask;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @OneToMany(mappedBy = "hold", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HoldBerth> holdBerths = new ArrayList<>();

    public UUID getHoldId()              { return holdId; }
    public Long getTripId()              { return tripId; }
    public short getFromStationIndex()   { return fromStationIndex; }
    public short getToStationIndex()     { return toStationIndex; }
    public int getJourneyMask()          { return journeyMask; }
    public Instant getExpiresAt()        { return expiresAt; }
    public UUID getIdempotencyKey()      { return idempotencyKey; }
    public List<HoldBerth> getHoldBerths() { return holdBerths; }

    public static Hold create(Long tripId, short fromIndex, short toIndex,
                              int journeyMask, Instant expiresAt, UUID idempotencyKey) {
        Hold h = new Hold();
        h.tripId             = tripId;
        h.fromStationIndex   = fromIndex;
        h.toStationIndex     = toIndex;
        h.journeyMask        = journeyMask;
        h.expiresAt          = expiresAt;
        h.idempotencyKey     = idempotencyKey;
        return h;
    }
}
