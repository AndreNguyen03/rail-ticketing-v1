package vn.railticketing.inventory.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.railticketing.inventory.service.AvailabilityService;
import vn.railticketing.inventory.web.dto.AvailabilityResponse;

@RestController
@RequestMapping("/api/v1/trips")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/{tripId}/availability")
    public ResponseEntity<AvailabilityResponse> getAvailability(
            @PathVariable Long tripId,
            @RequestParam int fromStationIndex,
            @RequestParam int toStationIndex
    ) {
        return ResponseEntity.ok(
                availabilityService.getAvailability(tripId, fromStationIndex, toStationIndex));
    }
}
