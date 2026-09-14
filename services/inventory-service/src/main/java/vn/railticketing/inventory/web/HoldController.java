package vn.railticketing.inventory.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.railticketing.inventory.service.HoldService;
import vn.railticketing.inventory.web.dto.CreateHoldRequest;
import vn.railticketing.inventory.web.dto.HoldResponse;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @GetMapping("/{holdId}")
    public ResponseEntity<HoldResponse> getHold(@PathVariable UUID holdId) {
        return ResponseEntity.ok(holdService.getHold(holdId));
    }

    @PostMapping
    public ResponseEntity<HoldResponse> createHold(
            @Valid @RequestBody CreateHoldRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey
    ) {
        HoldResponse response = holdService.createHold(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{holdId}")
    public ResponseEntity<Void> releaseHold(@PathVariable UUID holdId) {
        holdService.releaseHold(holdId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{holdId}/commit")
    public ResponseEntity<Void> commitHold(@PathVariable UUID holdId) {
        holdService.commitHold(holdId);
        return ResponseEntity.noContent().build();
    }
}
