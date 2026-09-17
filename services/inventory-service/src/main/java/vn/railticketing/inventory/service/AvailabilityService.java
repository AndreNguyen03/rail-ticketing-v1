package vn.railticketing.inventory.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.inventory.domain.BerthInventory;
import vn.railticketing.inventory.repository.BerthInventoryRepository;
import vn.railticketing.inventory.web.dto.AvailabilityResponse;
import vn.railticketing.inventory.web.dto.BerthDto;
import vn.railticketing.inventory.web.dto.BerthMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AvailabilityService {

    private final BerthInventoryRepository berthInventoryRepository;
    private final BerthMapper berthMapper;

    public AvailabilityService(BerthInventoryRepository berthInventoryRepository, BerthMapper berthMapper) {
        this.berthInventoryRepository = berthInventoryRepository;
        this.berthMapper = berthMapper;
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

        List<BerthDto> berths = berthMapper.toDtoList(free);

        Map<String, Integer> countByClass = free.stream()
                .collect(Collectors.groupingBy(BerthInventory::getBerthClass,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));

        return new AvailabilityResponse(tripId, fromIndex, toIndex, berths.size(), countByClass, berths);
    }

    // Journey mask: set bits [from..to-1]. E.g. 0→6 = 0b111111 = 63.
    static int computeJourneyMask(int fromIndex, int toIndex) {
        return ((1 << (toIndex - fromIndex)) - 1) << fromIndex;
    }
}
