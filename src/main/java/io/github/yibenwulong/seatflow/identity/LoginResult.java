package io.github.yibenwulong.seatflow.identity;

import java.time.Instant;

public record LoginResult(String token, String tokenType, Instant expiresAt, CurrentUser user) {
    @Override public String toString() { return "LoginResult[token=REDACTED, tokenType=" + tokenType + ", expiresAt=" + expiresAt + ", user=" + user + "]"; }
}
