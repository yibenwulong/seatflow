package io.github.yibenwulong.seatflow.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yibenwulong.seatflow.SeatFlowApplication;
import io.github.yibenwulong.seatflow.common.ApiException;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = SeatFlowApplication.class)
@ActiveProfiles("test")
class AuthServiceTest {
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    @Test void correctPasswordReturnsTrustedUserAndSignedToken() {
        var result = auth.login("demo", "demo-seatflow");
        assertThat(result.user()).isEqualTo(new CurrentUser(1, "demo", Role.USER));
        assertThat(auth.authenticate(result.token())).isEqualTo(result.user());
        assertThat(result.tokenType()).isEqualTo("Bearer");
    }

    @Test void wrongPasswordAndUnknownUsernameHaveSameFailure() {
        for (String username : new String[]{"demo", "missing-user"}) {
            assertThatThrownBy(() -> auth.login(username, "wrong"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS"));
        }
    }

    @Test @Transactional void disabledAccountsCannotLoginOrReuseIssuedTokens() {
        String token = auth.login("demo", "demo-seatflow").token();
        jdbc.update("UPDATE user_account SET enabled=false WHERE id=1");
        assertThatThrownBy(() -> auth.login("demo", "demo-seatflow")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> auth.authenticate(token)).isInstanceOf(ApiException.class);
    }

    @Test @Transactional void roleComesFromDatabaseRatherThanStaleToken() {
        String token = auth.login("admin", "demo-seatflow").token();
        jdbc.update("UPDATE user_account SET role='USER' WHERE id=2");
        assertThat(auth.authenticate(token).role()).isEqualTo(Role.USER);
    }

    @Test void signedTokenExpiresExactlyAtDeadlineAndRejectsTamperingAndOtherKeys() {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 7);
        TokenService issuer = new TokenService(key, Clock.fixed(now, ZoneOffset.UTC), new ObjectMapper(), Duration.ofHours(1));
        String token = issuer.issue(1).token();
        assertThat(issuer.verify(token)).isEqualTo(1);
        TokenService boundary = new TokenService(key, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC), new ObjectMapper(), Duration.ofHours(1));
        assertThatThrownBy(() -> boundary.verify(token)).isInstanceOf(ApiException.class);
        String[] pieces = token.split("\\.");
        String changed = new String(Base64.getUrlDecoder().decode(pieces[1]), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"sub\":\"1\"", "\"sub\":\"2\"");
        String forged = pieces[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + pieces[2];
        assertThatThrownBy(() -> issuer.verify(forged)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> issuer.verify("not-a-token")).isInstanceOf(ApiException.class);
        key[0] = 9;
        TokenService other = new TokenService(key, Clock.fixed(now, ZoneOffset.UTC), new ObjectMapper(), Duration.ofHours(1));
        assertThatThrownBy(() -> other.verify(token)).isInstanceOf(ApiException.class);
    }
}
