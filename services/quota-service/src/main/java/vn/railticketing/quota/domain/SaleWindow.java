package vn.railticketing.quota.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sale_window")
public class SaleWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sale_window_id")
    private Long saleWindowId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "quota_limit", nullable = false)
    private int quotaLimit;

    protected SaleWindow() {}

    public Long getSaleWindowId() { return saleWindowId; }
    public String getName()       { return name; }
    public Instant getStartsAt()  { return startsAt; }
    public Instant getEndsAt()    { return endsAt; }
    public int getQuotaLimit()    { return quotaLimit; }
}
