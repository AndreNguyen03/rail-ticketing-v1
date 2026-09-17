package vn.railticketing.booking.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox")
public class Outbox {
    @Id
    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Outbox() {}

    public static Outbox create(String aggregateType, UUID aggregateId, String eventType, String payload) {
        Outbox o = new Outbox();
        o.outboxId = UUID.randomUUID();
        o.aggregateType = aggregateType;
        o.aggregateId = aggregateId;
        o.eventType = eventType;
        o.payload = payload;
        o.published = false;
        o.createdAt = Instant.now();
        return o;
    }

    public UUID getOutboxId() { return outboxId; }
    public String getPayload() { return payload; }
    public String getEventType() { return eventType; }
    public UUID getAggregateId() { return aggregateId; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
}
