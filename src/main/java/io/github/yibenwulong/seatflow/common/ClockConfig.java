package io.github.yibenwulong.seatflow.common;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock clock() { return Clock.systemUTC(); }
}
