package io.github.yibenwulong.seatflow.seating;

import java.time.Instant;
import java.util.List;

public record SeatMapView(long sessionId, int layoutVersion, List<SeatView> seats, Instant asOf) {
    public SeatMapView { seats = List.copyOf(seats); }
}
