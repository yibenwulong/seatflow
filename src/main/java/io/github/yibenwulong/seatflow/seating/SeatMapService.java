package io.github.yibenwulong.seatflow.seating;

import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import io.github.yibenwulong.seatflow.common.ApiException;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class SeatMapService {
    private final CatalogRepository repository;
    private final SeatMapCache cache;
    private final Clock clock;
    public SeatMapService(CatalogRepository repository,SeatMapCache cache,Clock clock) {
        this.repository=repository; this.cache=cache; this.clock=clock;
    }
    public SeatMapView getSeatMap(long sessionId) {
        return cache.get(sessionId).orElseGet(() -> {
            var session=repository.findSession(sessionId).orElseThrow(() -> ApiException.notFound("Session"));
            var seats=repository.listSessionSeats(sessionId).stream().map(s -> new SeatView(s.seatId(),s.rowNo(),s.seatNo(),
                    s.position(),s.blockId(),s.priceZone(),s.price(),s.status(),s.x(),s.y())).toList();
            var view=new SeatMapView(sessionId,session.layoutVersion(),seats,clock.instant());
            cache.put(view);
            return view;
        });
    }
}
