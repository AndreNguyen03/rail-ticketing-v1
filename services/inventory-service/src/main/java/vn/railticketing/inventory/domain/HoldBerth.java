package vn.railticketing.inventory.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "hold_berth")
public class HoldBerth {

    @EmbeddedId
    private HoldBerthId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("holdId")
    @JoinColumn(name = "hold_id")
    private Hold hold;

    public HoldBerth() {}

    public HoldBerth(Hold hold, Long berthId) {
        this.hold = hold;
        this.id   = new HoldBerthId(hold.getHoldId(), berthId);
    }

    public HoldBerthId getId()  { return id; }
    public Hold getHold()       { return hold; }
    public Long getBerthId()    { return id.getBerthId(); }
}
