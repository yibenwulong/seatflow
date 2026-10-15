package io.github.yibenwulong.seatflow.catalog;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/sessions")
public class SessionAdminController {
    private final SessionAdminService service;
    public SessionAdminController(SessionAdminService service) { this.service=service; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowSession create(@Valid @RequestBody CreateSessionRequest request) { return service.create(request); }
}
