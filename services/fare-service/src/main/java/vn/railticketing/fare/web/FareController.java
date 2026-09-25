package vn.railticketing.fare.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.railticketing.fare.service.FareService;
import vn.railticketing.fare.web.dto.FareResponse;
import vn.railticketing.fare.web.dto.RefundPolicyResponse;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/fares")
public class FareController {

    private final FareService fareService;

    public FareController(FareService fareService) {
        this.fareService = fareService;
    }

    // Query fares for a trip/journey. Clients call this before creating a hold
    // to show pricing to the user. Prices are per-berth, grouped by class.
    @GetMapping
    public ResponseEntity<FareResponse> getFares(
            @RequestParam Long tripId,
            @RequestParam int fromStationIndex,
            @RequestParam int toStationIndex) {
        return ResponseEntity.ok(fareService.getFares(tripId, fromStationIndex, toStationIndex));
    }

    // Refund policy rules — static, not trip-specific. Used by clients to show
    // the refund % before the user requests a cancellation.
    @GetMapping("/refund-policy")
    public ResponseEntity<RefundPolicyResponse> getRefundPolicy() {
        return ResponseEntity.ok(fareService.getRefundPolicy());
    }

    // Internal endpoint used by booking-service during refund processing to
    // calculate the exact refund amount for a given ticket price and departure.
    @GetMapping("/refund-amount")
    public ResponseEntity<Long> getRefundAmount(
            @RequestParam long ticketPriceVnd,
            @RequestParam OffsetDateTime departureAt) {
        return ResponseEntity.ok(fareService.calculateRefundAmount(ticketPriceVnd, departureAt));
    }
}
