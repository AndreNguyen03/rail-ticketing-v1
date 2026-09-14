package vn.railticketing.schedule.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.railticketing.schedule.domain.Carriage;
import vn.railticketing.schedule.domain.Trip;
import vn.railticketing.schedule.domain.TripStop;
import vn.railticketing.schedule.exception.TripNotFoundException;
import vn.railticketing.schedule.repository.BerthRepository;
import vn.railticketing.schedule.repository.CarriageRepository;
import vn.railticketing.schedule.repository.TripRepository;
import vn.railticketing.schedule.web.dto.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
public class TripService {

    private final TripRepository tripRepository;
    private final BerthRepository berthRepository;
    private final CarriageRepository carriageRepository;

    public TripService(TripRepository tripRepository,
                       BerthRepository berthRepository,
                       CarriageRepository carriageRepository) {
        this.tripRepository = tripRepository;
        this.berthRepository = berthRepository;
        this.carriageRepository = carriageRepository;
    }

    @Transactional(
            readOnly    = true,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED
    )
    public List<TripSummaryDto> searchTrips(String fromCode, String toCode, LocalDate date) {
        List<Object[]> rows = tripRepository.searchTrips(fromCode, toCode, date);
        return rows.stream().map(row -> {
            Trip trip         = (Trip) row[0];
            TripStop fromStop = (TripStop) row[1];
            TripStop toStop   = (TripStop) row[2];

            int durationMinutes = (int) Duration.between(
                    fromStop.getDepartsAt(), toStop.getArrivesAt()
            ).toMinutes();

            long minPrice = berthRepository
                    .findMinPriceVndByTripId(trip.getTripId())
                    .orElse(0L);

            return new TripSummaryDto(
                    trip.getTripId(),
                    trip.getTrainCode(),
                    trip.getServiceDate(),
                    fromStop.getId().getStationIndex(),
                    toStop.getId().getStationIndex(),
                    fromStop.getDepartsAt(),
                    toStop.getArrivesAt(),
                    durationMinutes,
                    minPrice
            );
        }).toList();
    }

    @Transactional(
            readOnly    = true,
            isolation   = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED
    )
    public TripDetailDto getTrip(Long tripId) {
        Trip trip = tripRepository.findByIdWithStops(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));

        // Second query: carriages + berths. Avoids mixing two collection fetches
        // in a single query (Hibernate multi-bag exception).
        List<Carriage> carriages = carriageRepository
                .findAllWithBerthsByTripId(tripId)
                .stream()
                .sorted(Comparator.comparingInt(Carriage::getCarriageNo))
                .toList();

        List<StationDto> stations = trip.getStops().stream()
                .map(s -> new StationDto(
                        s.getId().getStationIndex(),
                        s.getStationCode(),
                        s.getStation() != null ? s.getStation().getStationName() : s.getStationCode(),
                        s.getArrivesAt(),
                        s.getDepartsAt()
                ))
                .toList();

        List<CarriageDto> carriageDtos = carriages.stream()
                .map(c -> new CarriageDto(
                        c.getCarriageNo(),
                        c.getBerthClass(),
                        c.getBerths().stream()
                                .map(b -> new BerthDto(
                                        b.getBerthId(),
                                        b.getCarriageNo(),
                                        b.getBerthNo(),
                                        b.getBerthClass(),
                                        b.getLevel() != null ? b.getLevel().intValue() : null,
                                        b.getPriceVnd()
                                ))
                                .toList()
                ))
                .toList();

        return new TripDetailDto(
                trip.getTripId(),
                trip.getTrainCode(),
                trip.getServiceDate(),
                stations,
                carriageDtos
        );
    }
}
