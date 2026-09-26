package vn.railticketing.fare.service;

import org.springframework.stereotype.Service;
import vn.railticketing.fare.client.ScheduleClient;
import vn.railticketing.fare.client.dto.BerthDto;
import vn.railticketing.fare.client.dto.CarriageDto;
import vn.railticketing.fare.client.dto.StationDto;
import vn.railticketing.fare.client.dto.TripDetailDto;
import vn.railticketing.fare.web.dto.FareByClassDto;
import vn.railticketing.fare.web.dto.FareResponse;
import vn.railticketing.fare.web.dto.RefundPolicyResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FareService {

    private final ScheduleClient scheduleClient;

    public FareService(ScheduleClient scheduleClient) {
        this.scheduleClient = scheduleClient;
    }

    public FareResponse getFares(Long tripId, int fromStationIndex, int toStationIndex) {
        TripDetailDto trip = scheduleClient.getTripDetail(tripId);

        // Departure time at origin station of this journey segment
        OffsetDateTime departureAt = trip.stations().stream()
                .filter(s -> s.stationIndex() == fromStationIndex)
                .map(StationDto::departsAt)
                .findFirst()
                .orElse(null);

        // Collect all berths across all carriages, group by class
        Map<String, List<Long>> pricesByClass = new LinkedHashMap<>();
        Map<String, Integer> countByClass = new LinkedHashMap<>();

        for (CarriageDto carriage : trip.carriages()) {
            String cls = carriage.berthClass();
            for (BerthDto berth : carriage.berths()) {
                pricesByClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(berth.priceVnd());
                countByClass.merge(cls, 1, Integer::sum);
            }
        }

        List<FareByClassDto> faresByClass = pricesByClass.entrySet().stream()
                .map(e -> {
                    List<Long> prices = e.getValue();
                    return new FareByClassDto(
                            e.getKey(),
                            prices.stream().mapToLong(Long::longValue).min().orElse(0),
                            prices.stream().mapToLong(Long::longValue).max().orElse(0),
                            countByClass.getOrDefault(e.getKey(), 0)
                    );
                })
                .collect(Collectors.toList());

        return new FareResponse(tripId, fromStationIndex, toStationIndex, departureAt, faresByClass);
    }

    // Calculate refund amount for a ticket price given the departure time.
    public long calculateRefundAmount(long ticketPriceVnd, OffsetDateTime departureAt) {
        if (departureAt == null) return ticketPriceVnd; // unknown departure → full refund
        long hoursUntilDeparture = Duration
                .between(Instant.now(), departureAt.toInstant())
                .toHours();

        int percentage;
        if (hoursUntilDeparture >= 24)      percentage = 100;
        else if (hoursUntilDeparture >= 12) percentage = 50;
        else                                percentage = 0;

        return ticketPriceVnd * percentage / 100;
    }

    public RefundPolicyResponse getRefundPolicy() {
        return RefundPolicyResponse.standard();
    }
}
