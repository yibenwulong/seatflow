package io.github.yibenwulong.seatflow.catalog;

import io.github.yibenwulong.seatflow.common.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final CatalogRepository repository;
    public CatalogService(CatalogRepository repository) { this.repository = repository; }
    public List<Show> listShows(String city, LocalDate date) { return repository.listShows(city, date); }
    public List<ShowSession> listSessions(long showId) {
        if (!repository.showExists(showId)) throw ApiException.notFound("Show");
        return repository.listSessions(showId);
    }
    public ShowSession getSession(long sessionId) {
        return repository.findSession(sessionId).orElseThrow(() -> ApiException.notFound("Session"));
    }
}
