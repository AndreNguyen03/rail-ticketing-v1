package vn.railticketing.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ticket_id")
    private UUID ticketId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "berth_id", nullable = false)
    private Long berthId;

    @Column(name = "carriage_no", nullable = false)
    private Short carriageNo;

    @Column(name = "berth_no", nullable = false)
    private Short berthNo;

    @Column(name = "passenger_name", nullable = false)
    private String passengerName;

    @Column(name = "passenger_id_number", nullable = false)
    private String passengerIdNumber;

    @Column(name = "passenger_type", nullable = false)
    private String passengerType;

    @Column(name = "price_vnd", nullable = false)
    private Long priceVnd;

    @Column(nullable = false)
    private String status = "ISSUED";

    // Stage 9: lifecycle additions
    @Column(name = "journey_mask")
    private Integer journeyMask;

    @Column(name = "refund_amount_vnd")
    private Long refundAmountVnd;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "previous_berth_id")
    private Long previousBerthId;

    public UUID getTicketId()              { return ticketId; }
    public Booking getBooking()            { return booking; }
    public Long getBerthId()               { return berthId; }
    public Short getCarriageNo()           { return carriageNo; }
    public Short getBerthNo()              { return berthNo; }
    public String getPassengerName()       { return passengerName; }
    public String getPassengerIdNumber()   { return passengerIdNumber; }
    public String getPassengerType()       { return passengerType; }
    public Long getPriceVnd()              { return priceVnd; }
    public String getStatus()              { return status; }
    public Integer getJourneyMask()        { return journeyMask; }
    public Long getRefundAmountVnd()       { return refundAmountVnd; }
    public Instant getRefundedAt()         { return refundedAt; }
    public Long getPreviousBerthId()       { return previousBerthId; }

    public void setStatus(String status)              { this.status = status; }
    public void setRefundAmountVnd(Long amount)       { this.refundAmountVnd = amount; }
    public void setRefundedAt(Instant at)             { this.refundedAt = at; }
    public void setBerthId(Long berthId)              { this.berthId = berthId; }
    public void setCarriageNo(Short carriageNo)       { this.carriageNo = carriageNo; }
    public void setBerthNo(Short berthNo)             { this.berthNo = berthNo; }
    public void setPreviousBerthId(Long previousBerthId) { this.previousBerthId = previousBerthId; }
    public void setJourneyMask(Integer mask)          { this.journeyMask = mask; }

    public static Ticket create(Booking booking, Long berthId, Short carriageNo,
                                 Short berthNo, String passengerName,
                                 String passengerIdNumber, String passengerType,
                                 Long priceVnd) {
        Ticket t = new Ticket();
        t.booking           = booking;
        t.berthId           = berthId;
        t.carriageNo        = carriageNo;
        t.berthNo           = berthNo;
        t.passengerName     = passengerName;
        t.passengerIdNumber = passengerIdNumber;
        t.passengerType     = passengerType;
        t.priceVnd          = priceVnd;
        return t;
    }
}
