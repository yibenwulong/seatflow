package io.github.yibenwulong.seatflow.identity;

import io.github.yibenwulong.seatflow.SeatFlowApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=SeatFlowApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationFailureApiTest {
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    // Inject a failing external database boundary; authentication, filter and JSON resolver remain real.
    @MockitoBean UserAccountRepository accounts;

    @Test void databaseOutageDuringTokenAuthenticationHasOpaque503Contract() throws Exception {
        when(accounts.byId(1)).thenThrow(new CannotGetJdbcConnectionException("private database address"));
        mvc.perform(get("/api/v1/shows").header("Authorization", "Bearer " + tokens.issue(1).token()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("A required dependency is unavailable"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        org.assertj.core.api.Assertions.assertThatThrownBy(CurrentUser::requireUser)
                .isInstanceOf(io.github.yibenwulong.seatflow.common.ApiException.class);
    }

    @Test void unexpectedAuthenticationFailureUsesOpaque500Contract() throws Exception {
        when(accounts.byId(1)).thenThrow(new IllegalStateException("private authentication detail"));
        mvc.perform(get("/api/v1/shows").header("Authorization", "Bearer " + tokens.issue(1).token()))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
