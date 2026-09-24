package vn.railticketing.quota.web;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.railticketing.quota.domain.SaleWindow;
import vn.railticketing.quota.repository.SaleWindowRepository;
import vn.railticketing.quota.service.QuotaService;
import vn.railticketing.quota.web.dto.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quota")
public class QuotaController {

    private final QuotaService         quotaService;
    private final SaleWindowRepository windowRepo;

    public QuotaController(QuotaService quotaService, SaleWindowRepository windowRepo) {
        this.quotaService = quotaService;
        this.windowRepo   = windowRepo;
    }

    // Called by booking-service before creating a booking.
    @PostMapping("/reserve")
    public ResponseEntity<QuotaReserveResponse> reserve(@Valid @RequestBody QuotaReserveRequest request) {
        QuotaReserveResponse resp = quotaService.reserve(request);
        return resp.allowed()
                ? ResponseEntity.ok(resp)
                : ResponseEntity.status(409).body(resp);
    }

    // Called by booking-service when a booking expires or is cancelled.
    @DeleteMapping("/reservations/{bookingId}")
    public ResponseEntity<Void> release(@PathVariable UUID bookingId) {
        quotaService.release(bookingId);
        return ResponseEntity.noContent().build();
    }

    // Diagnostic: what is the active sale window right now?
    @GetMapping("/windows/active")
    public ResponseEntity<ActiveWindowResponse> activeWindow() {
        return windowRepo.findActiveAt(Instant.now())
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    private ActiveWindowResponse toResponse(SaleWindow w) {
        return new ActiveWindowResponse(w.getSaleWindowId(), w.getName(),
                w.getStartsAt(), w.getEndsAt(), w.getQuotaLimit());
    }
}
