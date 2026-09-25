package vn.railticketing.inventory.web;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.railticketing.inventory.service.HoldService;
import vn.railticketing.inventory.web.dto.RefundReleaseRequest;

// Internal endpoint — called by booking-service during refund processing to
// release occupiedMask bits on berths that belonged to a refunded ticket.
// Distinct from DELETE /holds/{id} which only releases heldMask (pre-payment).
@RestController
@RequestMapping("/api/v1/inventory")
public class RefundController {

    private final HoldService holdService;

    public RefundController(HoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping("/release-occupied")
    public ResponseEntity<Void> releaseOccupied(@Valid @RequestBody RefundReleaseRequest request) {
        holdService.releaseOccupied(request.berthIds(), request.journeyMask());
        return ResponseEntity.noContent().build();
    }

    // Exchange: atomically release old berth occupied bits, then commit a
    // pre-created hold into occupied bits. Two separate DB writes — if commit
    // fails, old bits are already cleared. Booking-service re-tries commit.
    @PostMapping("/exchange")
    public ResponseEntity<Void> exchange(
            @RequestParam("oldBerthId")    Long   oldBerthId,
            @RequestParam("journeyMask")   int    journeyMask,
            @RequestParam("newHoldId")     java.util.UUID newHoldId) {
        holdService.exchange(oldBerthId, journeyMask, newHoldId);
        return ResponseEntity.noContent().build();
    }
}
