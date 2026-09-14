package vn.railticketing.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class TripStopId implements Serializable {

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "station_index")
    private Short stationIndex;

    public TripStopId() {}

    public TripStopId(Long tripId, Short stationIndex) {
        this.tripId = tripId;
        this.stationIndex = stationIndex;
    }

    public Long getTripId() { return tripId; }
    public Short getStationIndex() { return stationIndex; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TripStopId that)) return false;
        return Objects.equals(tripId, that.tripId) && Objects.equals(stationIndex, that.stationIndex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tripId, stationIndex);
    }
}
