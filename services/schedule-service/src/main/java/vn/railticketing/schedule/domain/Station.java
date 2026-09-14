package vn.railticketing.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "station")
public class Station {

    @Id
    @Column(name = "station_code")
    private String stationCode;

    @Column(name = "station_name", nullable = false)
    private String stationName;

    public String getStationCode() { return stationCode; }
    public String getStationName() { return stationName; }
}
