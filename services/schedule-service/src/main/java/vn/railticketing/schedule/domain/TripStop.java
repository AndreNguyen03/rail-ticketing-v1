package vn.railticketing.schedule.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "trip_stop")
public class TripStop {

    @EmbeddedId
    private TripStopId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("tripId")
    @JoinColumn(name = "trip_id")
    private Trip trip;

    // Plain field used in JPQL WHERE clauses (fromStop.stationCode = :code)
    @Column(name = "station_code", nullable = false)
    private String stationCode;

    // Join for station name — insertable/updatable false because station_code column
    // is already managed by the stationCode field above
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_code", referencedColumnName = "station_code",
                insertable = false, updatable = false)
    private Station station;

    @Column(name = "arrives_at")
    private OffsetDateTime arrivesAt;

    @Column(name = "departs_at")
    private OffsetDateTime departsAt;

    public TripStopId getId() { return id; }
    public Trip getTrip() { return trip; }
    public String getStationCode() { return stationCode; }
    public Station getStation() { return station; }
    public OffsetDateTime getArrivesAt() { return arrivesAt; }
    public OffsetDateTime getDepartsAt() { return departsAt; }
}
