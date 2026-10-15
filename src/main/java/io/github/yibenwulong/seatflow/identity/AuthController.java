package io.github.yibenwulong.seatflow.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public LoginResult login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.username(), request.password());
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "LoginRequest[username=" + username + ", password=REDACTED]"; }
    }
}
