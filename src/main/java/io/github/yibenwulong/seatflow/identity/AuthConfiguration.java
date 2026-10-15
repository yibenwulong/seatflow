package io.github.yibenwulong.seatflow.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class AuthConfiguration {
    @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean TokenService tokenService(Environment env, Clock clock, ObjectMapper mapper) {
        String configured = env.getProperty("seatflow.auth.signing-key", "");
        byte[] key;
        if (configured.isBlank()) {
            // Every active profile must be developmental: mixing production with local must not bypass validation.
            String[] profiles = env.getActiveProfiles();
            if (profiles.length == 0 || !Arrays.stream(profiles).allMatch(p -> p.equals("local") || p.equals("test"))) {
                throw new IllegalStateException("SEATFLOW_AUTH_SIGNING_KEY is required outside local/test profiles");
            }
            key = new byte[32]; new SecureRandom().nextBytes(key);
        } else {
            try { key = Base64.getDecoder().decode(configured); }
            catch (IllegalArgumentException e) { throw new IllegalStateException("SEATFLOW_AUTH_SIGNING_KEY must be Base64", e); }
        }
        return new TokenService(key, clock, mapper, env.getProperty("seatflow.auth.token-lifetime", Duration.class, Duration.ofHours(1)));
    }
}
