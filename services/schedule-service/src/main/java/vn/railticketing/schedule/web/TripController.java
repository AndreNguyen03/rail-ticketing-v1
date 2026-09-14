package vn.railticketing.schedule.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.railticketing.schedule.service.TripService;
import vn.railticketing.schedule.web.dto.TripDetailDto;
import vn.railticketing.schedule.web.dto.TripSummaryDto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @GetMapping
    public ResponseEntity<Map<String, List<TripSummaryDto>>> searchTrips(
            @RequestParam String fromStationCode,
            @RequestParam String toStationCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate departureDate
    ) {
        List<TripSummaryDto> trips = tripService.searchTrips(fromStationCode, toStationCode, departureDate);
        return ResponseEntity.ok(Map.of("trips", trips));
    }

    @GetMapping("/{tripId}")
    public ResponseEntity<TripDetailDto> getTrip(@PathVariable Long tripId) {
        return ResponseEntity.ok(tripService.getTrip(tripId));
    }
}
