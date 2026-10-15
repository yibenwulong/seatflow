package io.github.yibenwulong.seatflow.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static io.github.yibenwulong.seatflow.support.ExpectedStartupWarning.run;

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
            String cause="SEATFLOW_AUTH_SIGNING_KEY is required outside local/test profiles";
            run("tokenService",cause,()->context(profiles).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage(cause);
            }));
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
        String malformed="SEATFLOW_AUTH_SIGNING_KEY must be Base64";
        run("tokenService",malformed,()->context("local").withPropertyValues("seatflow.auth.signing-key=not-base64!").run(ctx->{
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining(malformed)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("Illegal base64 character 2d");
        }));
        String shortKey="Token signing key must contain at least 32 bytes";
        run("tokenService",shortKey,()->context("local").withPropertyValues("seatflow.auth.signing-key="+Base64.getEncoder().encodeToString(new byte[31])).run(ctx->{
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage(shortKey);
        }));
    }

    @Test void nonPositiveOrFractionalLifetimeRefusesStartup() {
        for (String lifetime : new String[]{"PT0S", "PT-1S", "PT0.5S", "PT1.5S"}) {
            String cause="Token lifetime must be positive whole seconds";
            run("tokenService",cause,()->context("test").withPropertyValues("seatflow.auth.token-lifetime=" + lifetime).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage(cause);
            }));
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
