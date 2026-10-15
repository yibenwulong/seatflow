package io.github.yibenwulong.seatflow.catalog;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service) { this.service = service; }
    @GetMapping("/shows")
    public List<Show> listShows(@RequestParam(required=false) String city,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.listShows(city,date);
    }
    @GetMapping("/shows/{id}/sessions")
    public List<ShowSession> listSessions(@PathVariable long id) { return service.listSessions(id); }
    @GetMapping("/sessions/{id}")
    public ShowSession getSession(@PathVariable long id) { return service.getSession(id); }
}
