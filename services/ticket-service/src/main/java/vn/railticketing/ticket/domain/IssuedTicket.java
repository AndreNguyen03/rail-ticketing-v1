package vn.railticketing.ticket.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

// ticket-service owns this aggregate independently of booking-service.
// Populated from BookingConfirmed event — no FK to bookingdb.
@Entity
@Table(name = "issued_ticket")
public class IssuedTicket {

    @Id
    @Column(name = "ticket_id")
    private UUID ticketId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "berth_id", nullable = false)
    private Long berthId;

    @Column(name = "carriage_no", nullable = false)
    private short carriageNo;

    @Column(name = "berth_no", nullable = false)
    private short berthNo;

    @Column(name = "passenger_name", nullable = false)
    private String passengerName;

    @Column(name = "passenger_id_number", nullable = false)
    private String passengerIdNumber;

    @Column(name = "passenger_type", nullable = false)
    private String passengerType;

    @Column(name = "price_vnd", nullable = false)
    private long priceVnd;

    @Column(nullable = false)
    private String status = "ISSUED";

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected IssuedTicket() {}

    public static IssuedTicket issue(UUID ticketId, UUID bookingId, Long tripId,
                                      Long berthId, short carriageNo, short berthNo,
                                      String passengerName, String passengerIdNumber,
                                      String passengerType, long priceVnd) {
        IssuedTicket t = new IssuedTicket();
        t.ticketId          = ticketId;
        t.bookingId         = bookingId;
        t.tripId            = tripId;
        t.berthId           = berthId;
        t.carriageNo        = carriageNo;
        t.berthNo           = berthNo;
        t.passengerName     = passengerName;
        t.passengerIdNumber = passengerIdNumber;
        t.passengerType     = passengerType;
        t.priceVnd          = priceVnd;
        t.issuedAt          = Instant.now();
        return t;
    }

    public UUID   getTicketId()            { return ticketId; }
    public UUID   getBookingId()           { return bookingId; }
    public Long   getTripId()              { return tripId; }
    public Long   getBerthId()             { return berthId; }
    public short  getCarriageNo()          { return carriageNo; }
    public short  getBerthNo()             { return berthNo; }
    public String getPassengerName()       { return passengerName; }
    public String getPassengerIdNumber()   { return passengerIdNumber; }
    public String getPassengerType()       { return passengerType; }
    public long   getPriceVnd()            { return priceVnd; }
    public String getStatus()              { return status; }
    public Instant getIssuedAt()           { return issuedAt; }
}
