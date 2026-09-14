package vn.railticketing.inventory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.inventory.domain.BerthInventory;
import vn.railticketing.inventory.domain.Hold;
import vn.railticketing.inventory.domain.HoldBerth;
import vn.railticketing.inventory.exception.HoldNotFoundException;
import vn.railticketing.inventory.exception.InsufficientInventoryException;
import vn.railticketing.inventory.repository.BerthInventoryRepository;
import vn.railticketing.inventory.repository.HoldRepository;
import vn.railticketing.inventory.web.dto.BerthDto;
import vn.railticketing.inventory.web.dto.CreateHoldRequest;
import vn.railticketing.inventory.web.dto.HoldResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static vn.railticketing.inventory.service.AvailabilityService.computeJourneyMask;

@Service
public class HoldService {

    private final HoldRepository holdRepository;
    private final BerthInventoryRepository berthInventoryRepository;

    @Value("${inventory.hold.ttl-seconds}")
    private long ttlSeconds;

    public HoldService(HoldRepository holdRepository,
                       BerthInventoryRepository berthInventoryRepository) {
        this.holdRepository = holdRepository;
        this.berthInventoryRepository = berthInventoryRepository;
    }

    @Transactional(
            readOnly    = true,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED
    )
    public HoldResponse getHold(UUID holdId) {
        Hold hold = holdRepository.findByIdWithBerths(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));
        return toResponse(hold);
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public HoldResponse createHold(CreateHoldRequest request, UUID idempotencyKey) {
        // Idempotency: same key → return original result
        return holdRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toResponse)
                .orElseGet(() -> doCreateHold(request, idempotencyKey));
    }

    private HoldResponse doCreateHold(CreateHoldRequest request, UUID idempotencyKey) {
        int journeyMask = computeJourneyMask(request.fromStationIndex(), request.toStationIndex());

        // Lock berths atomically. FOR UPDATE SKIP LOCKED ensures competing requests
        // pick different berths instead of queuing behind each other.
        List<BerthInventory> locked = berthInventoryRepository.lockAvailableForJourney(
                request.tripId(), journeyMask, request.preferredClass(), request.quantity());

        if (locked.size() < request.quantity()) {
            throw new InsufficientInventoryException(request.quantity(), locked.size());
        }

        // Flip held bits for each selected berth
        for (BerthInventory berth : locked) {
            berth.setHeldMask(berth.getHeldMask() | journeyMask);
        }

        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        Hold hold = Hold.create(
                request.tripId(),
                (short) request.fromStationIndex(),
                (short) request.toStationIndex(),
                journeyMask,
                expiresAt,
                idempotencyKey
        );

        for (BerthInventory berth : locked) {
            hold.getHoldBerths().add(new HoldBerth(hold, berth.getBerthId()));
        }

        holdRepository.save(hold);
        return toResponse(hold, locked, expiresAt);
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public void releaseHold(UUID holdId) {
        holdRepository.findByIdWithBerths(holdId).ifPresent(hold -> {
            clearHeldBits(hold);
            holdRepository.delete(hold);
        });
        // If hold not found → silently succeed (idempotent)
    }

    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public void commitHold(UUID holdId) {
        Hold hold = holdRepository.findByIdWithBerths(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));

        List<Long> berthIds = hold.getHoldBerths().stream()
                .map(HoldBerth::getBerthId).toList();

        List<BerthInventory> berths = berthInventoryRepository.findAllByBerthIdIn(berthIds);
        int journeyMask = hold.getJourneyMask();

        for (BerthInventory berth : berths) {
            berth.setOccupiedMask(berth.getOccupiedMask() | journeyMask);
            berth.setHeldMask(berth.getHeldMask() & ~journeyMask);
        }

        holdRepository.delete(hold);
    }

    // Called by HoldExpiryJob — same logic as releaseHold but hold is already loaded
    @Transactional(
            readOnly    = false,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            rollbackFor = Exception.class
    )
    public void expireHold(Hold hold) {
        clearHeldBits(hold);
        holdRepository.delete(hold);
    }

    private void clearHeldBits(Hold hold) {
        List<Long> berthIds = hold.getHoldBerths().stream()
                .map(HoldBerth::getBerthId).toList();

        if (berthIds.isEmpty()) return;

        List<BerthInventory> berths = berthInventoryRepository.findAllByBerthIdIn(berthIds);
        int journeyMask = hold.getJourneyMask();
        for (BerthInventory berth : berths) {
            berth.setHeldMask(berth.getHeldMask() & ~journeyMask);
        }
    }

    private HoldResponse toResponse(Hold hold) {
        List<Long> berthIds = hold.getHoldBerths().stream()
                .map(HoldBerth::getBerthId).toList();
        List<BerthInventory> berths = berthInventoryRepository.findAllByBerthIdIn(berthIds);
        return toResponse(hold, berths, hold.getExpiresAt());
    }

    private HoldResponse toResponse(Hold hold, List<BerthInventory> berths, Instant expiresAt) {
        List<BerthDto> berthDtos = berths.stream()
                .map(b -> new BerthDto(
                        b.getBerthId(), b.getCarriageNo(), b.getBerthNo(),
                        b.getBerthClass(),
                        b.getLevel() != null ? b.getLevel().intValue() : null,
                        b.getPriceVnd()
                ))
                .toList();

        long totalPrice = berths.stream().mapToLong(BerthInventory::getPriceVnd).sum();
        long remainingTtl = java.time.Duration.between(Instant.now(), expiresAt).toSeconds();

        return new HoldResponse(
                hold.getHoldId(),
                hold.getTripId(),
                hold.getFromStationIndex(),
                hold.getToStationIndex(),
                berthDtos,
                totalPrice,
                expiresAt,
                Math.max(0, remainingTtl)
        );
    }
}
