package io.github.yibenwulong.seatflow.identity;

import io.github.yibenwulong.seatflow.common.ApiException;
import org.springframework.http.HttpStatus;

/** Trusted request identity, installed only after token and enabled-account verification. */
public record CurrentUser(long id, String username, Role role) {
    private static final ThreadLocal<CurrentUser> CONTEXT = new ThreadLocal<>();

    public static CurrentUser requireUser() {
        CurrentUser user = CONTEXT.get();
        if (user == null) throw unauthorized();
        return user;
    }

    public static CurrentUser requireAdmin() {
        CurrentUser user = requireUser();
        if (user.role() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Administrator role required");
        }
        return user;
    }

    static void install(CurrentUser user) { CONTEXT.set(user); }
    static void clear() { CONTEXT.remove(); }
    static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid bearer token is required");
    }
}
