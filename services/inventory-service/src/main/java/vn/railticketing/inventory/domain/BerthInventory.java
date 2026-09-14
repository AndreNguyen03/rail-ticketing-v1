package vn.railticketing.inventory.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "berth_inventory")
public class BerthInventory {

    @Id
    @Column(name = "berth_id")
    private Long berthId;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

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

    @Column(name = "occupied_mask", nullable = false)
    private int occupiedMask;

    @Column(name = "held_mask", nullable = false)
    private int heldMask;

    @Version
    @Column(nullable = false)
    private int version;

    public Long getBerthId()    { return berthId; }
    public Long getTripId()     { return tripId; }
    public Short getCarriageNo(){ return carriageNo; }
    public Short getBerthNo()   { return berthNo; }
    public String getBerthClass(){ return berthClass; }
    public Short getLevel()     { return level; }
    public Long getPriceVnd()   { return priceVnd; }
    public int getOccupiedMask(){ return occupiedMask; }
    public int getHeldMask()    { return heldMask; }
    public int getVersion()     { return version; }

    public void setOccupiedMask(int occupiedMask) { this.occupiedMask = occupiedMask; }
    public void setHeldMask(int heldMask)         { this.heldMask = heldMask; }

    // Used by DataSeeder to create a new inventory row from schedule berth data
    public static BerthInventory seed(Long berthId, Long tripId, Short carriageNo,
                                      Short berthNo, String berthClass, Short level,
                                      Long priceVnd) {
        BerthInventory b = new BerthInventory();
        b.berthId     = berthId;
        b.tripId      = tripId;
        b.carriageNo  = carriageNo;
        b.berthNo     = berthNo;
        b.berthClass  = berthClass;
        b.level       = level;
        b.priceVnd    = priceVnd;
        b.occupiedMask = 0;
        b.heldMask    = 0;
        return b;
    }
}
