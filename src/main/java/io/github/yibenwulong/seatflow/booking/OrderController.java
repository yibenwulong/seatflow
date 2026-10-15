package io.github.yibenwulong.seatflow.booking;

import io.github.yibenwulong.seatflow.identity.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service=service; }
    @PostMapping public OrderView create(@RequestBody CreateOrderRequest request) {
        return service.create(CurrentUser.requireUser().id(),request);
    }
    @GetMapping public List<OrderView> list() { return service.list(CurrentUser.requireUser().id()); }
    @GetMapping("/{id}") public OrderView get(@PathVariable long id) { return service.get(CurrentUser.requireUser().id(),id); }
    @PostMapping("/{id}/cancel") public OrderView cancel(@PathVariable long id) { return service.cancel(CurrentUser.requireUser().id(),id); }
}
