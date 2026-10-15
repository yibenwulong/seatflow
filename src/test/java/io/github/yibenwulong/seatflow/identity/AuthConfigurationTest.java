package io.github.yibenwulong.seatflow.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class AuthConfigurationTest {
    private ApplicationContextRunner context(String... profiles) {
        return new ApplicationContextRunner().withUserConfiguration(AuthConfiguration.class)
                .withBean(Clock.class, Clock::systemUTC).withBean(ObjectMapper.class, ObjectMapper::new)
                .withInitializer(ctx -> {
                    ctx.getEnvironment().setActiveProfiles(profiles);
                    ctx.getEnvironment().setConversionService(new org.springframework.boot.convert.ApplicationConversionService());
                });
    }

    @Test void missingKeyRefusesDefaultProductionAndMixedProfiles() {
        for (String[] profiles : new String[][]{{}, {"production"}, {"local", "production"}}) {
            context(profiles).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasRootCauseMessage("SEATFLOW_AUTH_SIGNING_KEY is required outside local/test profiles");
            });
        }
    }

    @Test void localAndTestUseDifferentEphemeralKeysOnEachStartup() {
        for (String profile : new String[]{"local", "test"}) {
            context(profile).run(first -> {
                assertThat(first).hasNotFailed();
                String token = first.getBean(TokenService.class).issue(1).token();
                context(profile).run(second -> {
                    assertThat(second).hasNotFailed();
                    assertThatThrownBy(() -> second.getBean(TokenService.class).verify(token))
                            .isInstanceOf(io.github.yibenwulong.seatflow.common.ApiException.class);
                });
            });
        }
    }

    @Test void productionConfiguredKeySurvivesRestart() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        context("production").withPropertyValues("seatflow.auth.signing-key=" + key).run(first -> {
            assertThat(first).hasNotFailed();
            String token = first.getBean(TokenService.class).issue(1).token();
            context("production").withPropertyValues("seatflow.auth.signing-key=" + key).run(second -> {
                assertThat(second).hasNotFailed();
                assertThat(second.getBean(TokenService.class).verify(token)).isEqualTo(1);
            });
        });
    }

    @Test void malformedOrShortConfiguredKeysRefuseStartupEvenInLocal() {
        for (String key : new String[]{"not-base64!", Base64.getEncoder().encodeToString(new byte[31])}) {
            context("local").withPropertyValues("seatflow.auth.signing-key=" + key).run(ctx -> assertThat(ctx).hasFailed());
        }
    }

    @Test void zeroOrNegativeLifetimeRefusesStartup() {
        for (String lifetime : new String[]{"PT0S", "PT-1S"}) {
            context("test").withPropertyValues("seatflow.auth.token-lifetime=" + lifetime).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasRootCauseMessage("Token lifetime must be positive whole seconds");
            });
        }
    }

    @Test void validConfiguredLifetimeIsApplied() {
        context("test").withPropertyValues("seatflow.auth.token-lifetime=PT2H").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var before = java.time.Instant.now().plusSeconds(7199);
            assertThat(ctx.getBean(TokenService.class).issue(1).expiresAt()).isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(java.time.Instant.now().plusSeconds(7200));
        });
    }
}
