package vn.railticketing.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class HoldBerthId implements Serializable {

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "berth_id")
    private Long berthId;

    public HoldBerthId() {}

    public HoldBerthId(UUID holdId, Long berthId) {
        this.holdId  = holdId;
        this.berthId = berthId;
    }

    public UUID getHoldId()  { return holdId; }
    public Long getBerthId() { return berthId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HoldBerthId that)) return false;
        return Objects.equals(holdId, that.holdId) && Objects.equals(berthId, that.berthId);
    }

    @Override
    public int hashCode() { return Objects.hash(holdId, berthId); }
}
