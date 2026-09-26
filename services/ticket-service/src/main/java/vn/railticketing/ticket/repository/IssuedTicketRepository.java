package vn.railticketing.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.railticketing.ticket.domain.IssuedTicket;

import java.util.List;
import java.util.UUID;

public interface IssuedTicketRepository extends JpaRepository<IssuedTicket, UUID> {
    List<IssuedTicket> findByBookingId(UUID bookingId);
    List<IssuedTicket> findByPassengerIdNumber(String passengerIdNumber);
}
