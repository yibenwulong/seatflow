package io.github.yibenwulong.seatflow.seating;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import io.github.yibenwulong.seatflow.support.MySqlTestDatabase;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real Redis and MySQL. Every key and database belongs to this test invocation. */
class SeatMapCacheIT {
    @Test void roundTripsWithFiveSecondTtlExpiresAndEvictsWithoutChangingSnapshot() {
        try (var db = MySqlTestDatabase.create()) {
            var factory = factory(Integer.parseInt(required("SEATFLOW_IT_REDIS_PORT")));
            try {
                var redis = new StringRedisTemplate(factory);
                String prefix="seatflow:it:"+UUID.randomUUID()+":";
                var cache=new SeatMapCache(redis,mapper(),prefix);
                var clock=Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"),ZoneOffset.UTC);
                var service=new SeatMapService(new CatalogRepository(db.jdbc()),cache,clock);
                assertThat(cache.get(101)).isEmpty();
                var original=service.getSeatMap(101);
                assertThat(original.seats()).hasSize(16);
                assertThat(original.asOf()).isEqualTo(clock.instant());
                assertThat(cache.get(101)).contains(original);
                assertThat(redis.getExpire(prefix+101,java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L,5000L);
                db.jdbc().update("UPDATE hall_seat SET row_no='CHANGED',seat_no='EDIT',x=999,y=999,price_zone='EDIT',enabled=false WHERE id=1");
                db.jdbc().update("UPDATE session_seat SET status='DISABLED' WHERE session_id=101 AND seat_id=1");
                assertThat(service.getSeatMap(101)).isEqualTo(original); // cache hit, including original status/asOf
                await().atMost(Duration.ofSeconds(7)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> assertThat(cache.get(101)).isEmpty());
                var refreshed=service.getSeatMap(101);
                var seat=refreshed.seats().get(0);
                assertThat(seat.rowNo()).isEqualTo("A");
                assertThat(seat.seatNo()).isEqualTo("1");
                assertThat(seat.x()).isEqualByComparingTo("1.00");
                assertThat(seat.y()).isEqualByComparingTo("1.00");
                assertThat(seat.priceZone()).isEqualTo("PREMIUM");
                assertThat(seat.price()).isEqualByComparingTo("280.00");
                assertThat(seat.status()).isEqualTo("DISABLED");
                cache.evict(101);
                assertThat(cache.get(101)).isEmpty();
                // Corrupt cache payloads are also misses, never HTTP 500 failures.
                redis.opsForValue().set(prefix+101,"{broken",Duration.ofSeconds(5));
                assertThat(service.getSeatMap(101)).isEqualTo(refreshed);
                redis.opsForValue().set(prefix+101,"null",Duration.ofSeconds(5));
                assertThat(service.getSeatMap(101)).isEqualTo(refreshed);
                cache.evict(101);
            } finally { factory.destroy(); }
        }
    }

    @Test void redisConnectionFailureFallsBackToRealMysqlAndDoesNotBreakEvictionOrWrites() {
        try (var db=MySqlTestDatabase.create()) {
            var factory=factory(1);
            try {
                var cache=new SeatMapCache(new StringRedisTemplate(factory),mapper(),"seatflow:unavailable:");
                var service=new SeatMapService(new CatalogRepository(db.jdbc()),cache,Clock.systemUTC());
                var view=service.getSeatMap(102);
                assertThat(view.sessionId()).isEqualTo(102);
                assertThat(view.seats()).hasSize(16);
                assertThatCode(() -> cache.put(view)).doesNotThrowAnyException();
                assertThatCode(() -> cache.evict(102)).doesNotThrowAnyException();
                assertThatThrownBy(() -> service.getSeatMap(999)).hasMessage("Session not found");
            } finally { factory.destroy(); }
        }
    }

    static com.fasterxml.jackson.databind.ObjectMapper mapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }
    static LettuceConnectionFactory factory(int port) {
        var config=new RedisStandaloneConfiguration(required("SEATFLOW_IT_REDIS_HOST"),port);
        var factory=new LettuceConnectionFactory(config,LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet();
        return factory;
    }
    static String required(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException(key+" required; integration tests never skip");
        return value;
    }
}
