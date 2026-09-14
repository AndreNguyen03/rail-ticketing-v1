package vn.railticketing.inventory.client.dto;

import java.util.List;

public record TripsResponse(List<ScheduleTripSummary> trips) {}
