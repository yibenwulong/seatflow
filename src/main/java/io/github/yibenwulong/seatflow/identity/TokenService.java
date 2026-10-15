package io.github.yibenwulong.seatflow.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small, fixed-algorithm HS256 issuer/verifier. No algorithm or identity is selected by an unverified claim. */
public final class TokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String HEADER = ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private final byte[] key;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Duration lifetime;

    TokenService(byte[] key, Clock clock, ObjectMapper mapper, Duration lifetime) {
        if (key.length < 32) throw new IllegalArgumentException("Token signing key must contain at least 32 bytes");
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.getNano() != 0) throw new IllegalArgumentException("Token lifetime must be positive whole seconds");
        this.key = key.clone(); this.clock = clock; this.mapper = mapper; this.lifetime = lifetime;
    }

    IssuedToken issue(long userId) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(lifetime);
        try {
            // JSON API Long serializers stringify IDs; JWT NumericDate claims must remain numbers.
            var claims = mapper.createObjectNode().put("iss", "seatflow").put("sub", Long.toString(userId))
                    .put("iat", now.getEpochSecond()).put("exp", expiresAt.getEpochSecond());
            String payload = ENCODER.encodeToString(mapper.writeValueAsBytes(claims));
            String unsigned = HEADER + "." + payload;
            return new IssuedToken(unsigned + "." + ENCODER.encodeToString(sign(unsigned)), expiresAt);
        } catch (java.io.IOException e) { throw new IllegalStateException("Unable to issue token", e); }
    }

    long verify(String token) {
        try {
            if (token == null || token.length() > 4096) throw CurrentUser.unauthorized();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0]) ||
                    !MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), Base64.getUrlDecoder().decode(parts[2]))) {
                throw CurrentUser.unauthorized();
            }
            JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!"seatflow".equals(claims.path("iss").asText()) || !claims.path("sub").isTextual() ||
                    !claims.path("exp").isIntegralNumber() || !claims.path("exp").canConvertToLong() ||
                    !claims.path("iat").isIntegralNumber() || !claims.path("iat").canConvertToLong()) throw CurrentUser.unauthorized();
            long issued = claims.path("iat").longValue();
            long expiry = claims.path("exp").longValue();
            long now = clock.instant().getEpochSecond();
            long userId = Long.parseLong(claims.path("sub").textValue());
            if (userId <= 0 || issued > now || expiry <= issued || now >= expiry) throw CurrentUser.unauthorized();
            return userId;
        } catch (java.io.IOException | IllegalArgumentException e) { throw CurrentUser.unauthorized(); }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Token signing unavailable", e); }
    }

    record IssuedToken(String token, Instant expiresAt) {
        @Override public String toString() { return "IssuedToken[token=REDACTED, expiresAt=" + expiresAt + "]"; }
    }
}
