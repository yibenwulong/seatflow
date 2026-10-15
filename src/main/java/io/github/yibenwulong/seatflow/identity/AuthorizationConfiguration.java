package io.github.yibenwulong.seatflow.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                // MVC strips matrix parameters and decodes path segments when choosing a controller.
                // Authorize that matched route, so alternate URL spellings cannot weaken ADMIN checks.
                Object mapping = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                if (handler instanceof HandlerMethod && mapping != null) path = mapping.toString();
                String method = request.getMethod();
                if (method.equals("POST") && path.equals("/api/v1/auth/login")) return true;
                if (method.equals("GET") && (path.equals("/api/v1/shows")
                        || path.matches("/api/v1/shows/[^/]+/sessions")
                        || path.matches("/api/v1/sessions/[^/]+(?:/seats)?"))) return true;
                // External gateway identity: its future controller MUST verify signature, merchant,
                // transaction and amount before acting. This exception never permits dev completion.
                if (method.equals("POST") && path.matches("/api/v1/payments/[^/]+/notify")) return true;
                if (path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/")) CurrentUser.requireAdmin();
                else CurrentUser.requireUser();
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }
}
