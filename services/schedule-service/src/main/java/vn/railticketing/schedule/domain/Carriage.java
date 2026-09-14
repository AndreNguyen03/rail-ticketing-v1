package vn.railticketing.schedule.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "carriage")
public class Carriage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "carriage_id")
    private Long carriageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "carriage_no", nullable = false)
    private Short carriageNo;

    @Column(name = "berth_class", nullable = false)
    private String berthClass;

    @OneToMany(mappedBy = "carriage", fetch = FetchType.LAZY)
    @OrderBy("berthNo ASC")
    private List<Berth> berths = new ArrayList<>();

    public Long getCarriageId() { return carriageId; }
    public Trip getTrip() { return trip; }
    public Short getCarriageNo() { return carriageNo; }
    public String getBerthClass() { return berthClass; }
    public List<Berth> getBerths() { return berths; }
}
