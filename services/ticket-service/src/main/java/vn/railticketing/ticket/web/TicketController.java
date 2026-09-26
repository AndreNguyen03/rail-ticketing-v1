package vn.railticketing.ticket.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.railticketing.ticket.domain.IssuedTicket;
import vn.railticketing.ticket.repository.IssuedTicketRepository;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final IssuedTicketRepository repository;

    public TicketController(IssuedTicketRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<IssuedTicket> getTicket(@PathVariable UUID ticketId) {
        return repository.findById(ticketId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<IssuedTicket>> listTickets(
            @RequestParam(required = false) UUID bookingId,
            @RequestParam(required = false) String passengerIdNumber) {

        if (bookingId != null) {
            return ResponseEntity.ok(repository.findByBookingId(bookingId));
        }
        if (passengerIdNumber != null) {
            return ResponseEntity.ok(repository.findByPassengerIdNumber(passengerIdNumber));
        }
        return ResponseEntity.badRequest().build();
    }
}
