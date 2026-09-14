package vn.railticketing.schedule.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trip")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "train_code", nullable = false)
    private String trainCode;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(nullable = false)
    private String status;

    @OneToMany(mappedBy = "trip", fetch = FetchType.LAZY)
    @OrderBy("id.stationIndex ASC")
    private List<TripStop> stops = new ArrayList<>();

    @OneToMany(mappedBy = "trip", fetch = FetchType.LAZY)
    @OrderBy("carriageNo ASC")
    private List<Carriage> carriages = new ArrayList<>();

    public Long getTripId() { return tripId; }
    public String getTrainCode() { return trainCode; }
    public LocalDate getServiceDate() { return serviceDate; }
    public String getStatus() { return status; }
    public List<TripStop> getStops() { return stops; }
    public List<Carriage> getCarriages() { return carriages; }
}
