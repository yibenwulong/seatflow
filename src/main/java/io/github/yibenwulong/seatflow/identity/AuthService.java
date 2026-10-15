package io.github.yibenwulong.seatflow.identity;

import io.github.yibenwulong.seatflow.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserAccountRepository accounts;
    private final TokenService tokens;
    private final BCryptPasswordEncoder passwords;
    private final String dummyHash;

    public AuthService(UserAccountRepository accounts, TokenService tokens, BCryptPasswordEncoder passwords) {
        this.accounts = accounts; this.tokens = tokens; this.passwords = passwords;
        this.dummyHash = passwords.encode("unused-account-timing-placeholder");
    }

    public LoginResult login(String username, String password) {
        var account = accounts.byUsername(username).orElse(null);
        boolean matches = password != null && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
                && passwords.matches(password, account == null ? dummyHash : account.passwordHash());
        if (!matches || account == null || !account.enabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
        }
        var issued = tokens.issue(account.user().id());
        return new LoginResult(issued.token(), "Bearer", issued.expiresAt(), account.user());
    }

    CurrentUser authenticate(String token) {
        return accounts.byId(tokens.verify(token)).filter(UserAccountRepository.Account::enabled)
                .map(UserAccountRepository.Account::user).orElseThrow(CurrentUser::unauthorized);
    }
}
