package io.github.yibenwulong.seatflow.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {
    @Test void testCanReplaceBusinessTimeWithoutAnAdditionalSystemClock() {
        Clock fixed = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC);
        new ApplicationContextRunner().withBean(Clock.class, () -> fixed).withUserConfiguration(ClockConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context.getBean(Clock.class).instant()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
                });
    }
}
