package io.github.yibenwulong.seatflow.messaging;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.yibenwulong.seatflow.seating.SeatMapCache;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class MessageReplayIT {
    @Test void earlyHintsDoNotDeduplicateDueCheckAndReplayCannotReleaseNewOwners() {
        try(var f=new MessagingFixture()) {
            var order=f.create();
            var consumer=new OrderEventConsumer(f.jdbc,f.orders,f.service,mock(SeatMapCache.class),f.transactions,f.clock);
            var event=new OrderEvent("event-one","ORDER_CREATED",Long.toString(order.id()),"101",order.expireAt().toString());
            assertThat(consumer.handleExpiry(event)).isFalse();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event",Integer.class)).isZero();
            f.clock.advance(Duration.ofMinutes(15));
            assertThat(consumer.handleExpiry(event)).isTrue();
            var newOrder=f.create();
            consumer.handleExpiry(event);
            assertThat(f.jdbc.queryForObject("SELECT owner_order_id FROM session_seat WHERE session_id=101 AND seat_id=1",Long.class)).isEqualTo(newOrder.id());
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event",Integer.class)).isEqualTo(1);
        }
    }
    @Test void consumerSideEffectFailureRollsBackDedupAndCancellationTogether() {
        try(var f=new MessagingFixture()) {
            var order=f.create(); f.clock.advance(Duration.ofMinutes(15));
            var event=new OrderEvent("event-rollback","ORDER_CREATED",Long.toString(order.id()),"101",order.expireAt().toString());
            var brokenOutbox=new OutboxRepository(f.jdbc,f.clock) {
                @Override public void append(String type,long id,String payload) {throw new IllegalStateException("fault append");}
            };
            var brokenService=new io.github.yibenwulong.seatflow.booking.OrderService(f.orders,new io.github.yibenwulong.seatflow.catalog.CatalogRepository(f.jdbc),brokenOutbox,f.transactions,f.clock);
            var consumer=new OrderEventConsumer(f.jdbc,f.orders,brokenService,mock(SeatMapCache.class),f.transactions,f.clock);
            // Throw after real order/seat writes, then inspect real database rollback.
            assertThatThrownBy(()->consumer.handleExpiry(event)).isInstanceOf(RuntimeException.class);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event",Integer.class)).isZero();
            assertThat(f.orders.findOrder(order.id()).orElseThrow().status()).isEqualTo("UNPAID");
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("HELD");
            var recovered=new OrderEventConsumer(f.jdbc,f.orders,f.service,mock(SeatMapCache.class),f.transactions,f.clock);
            assertThat(recovered.handleExpiry(event)).isTrue();
        }
    }
    @Test void realRedisCacheIsInvalidatedByCommittedEventAndItsReplay() {
        String host=System.getenv("SEATFLOW_IT_REDIS_HOST"), port=System.getenv("SEATFLOW_IT_REDIS_PORT");
        if(host==null || port==null) throw new IllegalStateException("SEATFLOW_IT_REDIS_HOST/PORT required; real Redis test never skips");
        var factory=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(host,Integer.parseInt(port));factory.afterPropertiesSet();
        var redis=new org.springframework.data.redis.core.StringRedisTemplate(factory);
        String prefix="seatflow:messaging:it:"+java.util.UUID.randomUUID()+":";
        String key=prefix+101;
        try(var f=new MessagingFixture()) {
            var order=f.create();
            var cache=io.github.yibenwulong.seatflow.seating.SeatMapCacheTestSupport.isolated(redis,JsonMapper.builder().build(),prefix);
            var consumer=new OrderEventConsumer(f.jdbc,f.orders,f.service,cache,f.transactions,f.clock);
            var event=new OrderEvent("real-cache-event","ORDER_CREATED",Long.toString(order.id()),"101",order.expireAt().toString());
            redis.opsForValue().set(key,"stale",Duration.ofSeconds(5));consumer.handle(event);
            assertThat(redis.hasKey(key)).isFalse();
            redis.opsForValue().set(key,"stale-again",Duration.ofSeconds(5));consumer.handle(event);
            assertThat(redis.hasKey(key)).isFalse();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event",Integer.class)).isEqualTo(1);
        } finally {
            redis.delete(key);
            factory.destroy();
        }
    }
    @Test void cacheEvictionCanRepeatButDatabaseEventIsRecordedOnce() {
        try(var f=new MessagingFixture()) {
            var order=f.create(); var cache=mock(SeatMapCache.class);
            var consumer=new OrderEventConsumer(f.jdbc,f.orders,f.service,cache,f.transactions,f.clock);
            var event=new OrderEvent("cache-event","ORDER_CREATED",Long.toString(order.id()),"101",order.expireAt().toString());
            consumer.handle(event);consumer.handle(event);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event",Integer.class)).isEqualTo(1);
            verify(cache,times(2)).evict(101);
        }
    }
}
