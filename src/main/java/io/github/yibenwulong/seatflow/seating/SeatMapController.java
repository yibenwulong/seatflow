package io.github.yibenwulong.seatflow.seating;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sessions")
public class SeatMapController {
    private final SeatMapService service;
    public SeatMapController(SeatMapService service) { this.service=service; }
    @GetMapping("/{id}/seats")
    public SeatMapView get(@PathVariable long id) { return service.getSeatMap(id); }
}
