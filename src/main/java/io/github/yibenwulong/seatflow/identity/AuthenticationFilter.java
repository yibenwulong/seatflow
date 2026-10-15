package io.github.yibenwulong.seatflow.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final HandlerExceptionResolver errors;
    public AuthenticationFilter(AuthService auth, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.auth = auth; this.errors = errors;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CurrentUser.clear();
        try {
            try {
                var headers = Collections.list(request.getHeaders("Authorization"));
                if (!headers.isEmpty()) {
                    String header = headers.get(0);
                    if (headers.size() != 1 || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                            || header.length() <= 7 || header.substring(7).contains(" ")) throw CurrentUser.unauthorized();
                    CurrentUser.install(auth.authenticate(header.substring(7)));
                }
            } catch (RuntimeException error) {
                errors.resolveException(request, response, null, error);
                return;
            }
            chain.doFilter(request, response);
        } finally {
            // Servlet container threads are reused. Never let identity survive this request, including errors.
            CurrentUser.clear();
        }
    }
}
