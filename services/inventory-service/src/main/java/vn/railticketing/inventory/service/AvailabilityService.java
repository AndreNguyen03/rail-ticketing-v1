package vn.railticketing.inventory.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.inventory.domain.BerthInventory;
import vn.railticketing.inventory.repository.BerthInventoryRepository;
import vn.railticketing.inventory.web.dto.AvailabilityResponse;
import vn.railticketing.inventory.web.dto.BerthDto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AvailabilityService {

    private final BerthInventoryRepository berthInventoryRepository;

    public AvailabilityService(BerthInventoryRepository berthInventoryRepository) {
        this.berthInventoryRepository = berthInventoryRepository;
    }

    @Transactional(
            readOnly    = true,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED
    )
    public AvailabilityResponse getAvailability(Long tripId, int fromIndex, int toIndex) {
        if (fromIndex >= toIndex) {
            throw new IllegalArgumentException(
                    "fromStationIndex (" + fromIndex + ") must be less than toStationIndex (" + toIndex + ")");
        }

        int journeyMask = computeJourneyMask(fromIndex, toIndex);

        List<BerthInventory> free = berthInventoryRepository
                .findAvailableForJourney(tripId, journeyMask, null);

        List<BerthDto> berths = free.stream()
                .map(b -> new BerthDto(
                        b.getBerthId(),
                        b.getCarriageNo(),
                        b.getBerthNo(),
                        b.getBerthClass(),
                        b.getLevel() != null ? b.getLevel().intValue() : null,
                        b.getPriceVnd()
                ))
                .toList();

        Map<String, Integer> countByClass = free.stream()
                .collect(Collectors.groupingBy(BerthInventory::getBerthClass,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));

        return new AvailabilityResponse(tripId, fromIndex, toIndex, berths.size(), countByClass, berths);
    }

    // journeyMask: set bits [fromIndex .. toIndex-1]
    // Example: from=0, to=6  →  bits 0-5 → 0b00111111 = 63
    static int computeJourneyMask(int fromIndex, int toIndex) {
        return ((1 << (toIndex - fromIndex)) - 1) << fromIndex;
    }
}
