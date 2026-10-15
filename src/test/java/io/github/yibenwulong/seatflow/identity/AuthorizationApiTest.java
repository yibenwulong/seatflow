package io.github.yibenwulong.seatflow.identity;

import io.github.yibenwulong.seatflow.SeatFlowApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SeatFlowApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({AuthorizationApiTest.Probes.class, AuthorizationApiTest.TimeConfig.class})
class AuthorizationApiTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired MutableClock clock;
    @Autowired ObjectMapper mapper;
    @BeforeEach void resetClock() { clock.now = Instant.parse("2026-10-15T00:00:00Z"); }
    @AfterEach void contextIsAlwaysCleared() {
        org.assertj.core.api.Assertions.assertThatThrownBy(CurrentUser::requireUser).isInstanceOf(io.github.yibenwulong.seatflow.common.ApiException.class);
    }

    @Test void loginUsesBcryptSeedAndSerializesUserIdAsString() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"username\":\"demo\",\"password\":\"demo-seatflow\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.id").isString()).andExpect(jsonPath("$.user.id").value("1"))
                .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test void wrongPasswordHasStableUnauthorizedErrorAndRequestId() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .header("X-Request-Id", "login-check")
                .content("{\"username\":\"demo\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.requestId").value("login-check"));
    }

    @Test void missingAndMalformedTokensCannotReachProtectedController() throws Exception {
        mvc.perform(get("/api/v1/identity-probe")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED")).andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        for (String header : new String[]{"Basic abc", "Bearer nonsense", "Bearer ", "Bearer a.b.c"}) {
            mvc.perform(get("/api/v1/identity-probe").header("Authorization", header))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test void suppliedUserIdCannotReplaceTrustedIdentityAndNextRequestHasNoIdentity() throws Exception {
        String token = auth.login("demo", "demo-seatflow").token();
        mvc.perform(get("/api/v1/identity-probe").param("userId", "2").header("X-User-Id", "2").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("1")).andExpect(jsonPath("$.id").isString());
        mvc.perform(get("/api/v1/identity-probe")).andExpect(status().isUnauthorized());
    }

    @Test void userCannotCallAdminAndAdminCan() throws Exception {
        mvc.perform(get("/api/v1/admin/identity-probe").header("Authorization", "Bearer " + auth.login("demo", "demo-seatflow").token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/identity-probe").header("Authorization", "Bearer " + auth.login("admin", "demo-seatflow").token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test void expiredTokenReturnsUnauthorizedAtExactExpiry() throws Exception {
        String token = auth.login("demo", "demo-seatflow").token();
        clock.now = clock.now.plusSeconds(3600);
        mvc.perform(get("/api/v1/identity-probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test void publicCatalogStillWorksAndValidationErrorsUseContract() throws Exception {
        mvc.perform(get("/api/v1/shows")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("invalid-json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test void errorsDoNotExposeImplementationDetailsAndContextClearsAfterFailure() throws Exception {
        String token = auth.login("demo", "demo-seatflow").token();
        mvc.perform(get("/api/v1/identity-probe/failure").header("Authorization", "Bearer " + token).header("X-Request-Id", "failure-check"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred")).andExpect(jsonPath("$.requestId").value("failure-check"));
        mvc.perform(get("/api/v1/identity-probe")).andExpect(status().isUnauthorized());
    }

    @Test void concurrentRequestsKeepEachUsersVerifiedContext() throws Exception {
        String userToken = auth.login("demo", "demo-seatflow").token();
        String adminToken = auth.login("admin", "demo-seatflow").token();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i=0; i<12; i++) {
                boolean admin = i % 2 == 1;
                tasks.add(() -> {
                    mvc.perform(get("/api/v1/identity-probe").header("Authorization", "Bearer " + (admin ? adminToken : userToken)))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(admin ? "2" : "1"));
                    org.assertj.core.api.Assertions.assertThatThrownBy(CurrentUser::requireUser)
                            .isInstanceOf(io.github.yibenwulong.seatflow.common.ApiException.class);
                    return null;
                });
            }
            for (var future : pool.invokeAll(tasks)) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }

    @Test void duplicateAuthorizationHeadersAndInvalidRequestIdsAreRejectedSafely() throws Exception {
        String token = auth.login("demo", "demo-seatflow").token();
        mvc.perform(get("/api/v1/identity-probe").header("Authorization", "Bearer " + token, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/identity-probe").header("X-Request-Id", "unsafe request id"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.matchesPattern("[A-Za-z0-9._-]{1,64}")));
    }

    @Test void futureMutationsDefaultToProtectedAndDevCompletionRequiresIdentity() throws Exception {
        mvc.perform(post("/api/v1/future-feature")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/dev/payments/1/complete")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/mock/notify")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test void adminProtectionUsesMvcMappingWhenPathContainsMatrixParameters() throws Exception {
        String token = auth.login("demo", "demo-seatflow").token();
        mvc.perform(get("/api/v1/admin;ignored/identity-probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test void requireAdminAlsoProtectsControllersOutsideAdminPath() throws Exception {
        mvc.perform(get("/api/v1/identity-probe/admin-context").header("Authorization", "Bearer " + auth.login("demo", "demo-seatflow").token()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/identity-probe/admin-context").header("Authorization", "Bearer " + auth.login("admin", "demo-seatflow").token()))
                .andExpect(status().isOk());
    }

    // Test-only identity probes exercise the real security boundary, not nonexistent order ownership logic.
    @RestController static class Probes {
        @GetMapping("/api/v1/identity-probe") CurrentUser me() { return CurrentUser.requireUser(); }
        @GetMapping("/api/v1/admin/identity-probe") CurrentUser admin() { return CurrentUser.requireUser(); }
        @GetMapping("/api/v1/identity-probe/admin-context") CurrentUser adminContext() { return CurrentUser.requireAdmin(); }
        @GetMapping("/api/v1/identity-probe/failure") void failure() { throw new IllegalStateException("private-detail"); }
    }
    @TestConfiguration(proxyBeanMethods=false) static class TimeConfig {
        @Bean @Primary MutableClock authTestClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
}
