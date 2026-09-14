package vn.railticketing.schedule.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "berth")
public class Berth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "berth_id")
    private Long berthId;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "carriage_id", nullable = false)
    private Carriage carriage;

    @Column(name = "carriage_no", nullable = false)
    private Short carriageNo;

    @Column(name = "berth_no", nullable = false)
    private Short berthNo;

    @Column(name = "berth_class", nullable = false)
    private String berthClass;

    @Column
    private Short level;

    @Column(name = "price_vnd", nullable = false)
    private Long priceVnd;

    public Long getBerthId() { return berthId; }
    public Long getTripId() { return tripId; }
    public Carriage getCarriage() { return carriage; }
    public Short getCarriageNo() { return carriageNo; }
    public Short getBerthNo() { return berthNo; }
    public String getBerthClass() { return berthClass; }
    public Short getLevel() { return level; }
    public Long getPriceVnd() { return priceVnd; }
}
