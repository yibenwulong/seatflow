package io.github.yibenwulong.seatflow.messaging;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ExpiryFallbackIT {
    @Test void unavailableBrokerCannotPreventExactDeadlineCancellationAndRelease() {
        try(var f=new MessagingFixture()) {
            var order=f.create();
            new OutboxPublisher(f.outbox,event->{throw new IllegalStateException("MQ unavailable");},f.clock).publishBatch();
            var job=new OrderExpiryJob(f.jdbc,f.service,f.clock);
            assertThat(job.runOnce(order.expireAt().minusNanos(1000))).isZero();
            assertThat(job.runOnce(order.expireAt())).isEqualTo(1);
            assertThat(job.runOnce(order.expireAt().plusSeconds(5))).isZero();
            assertThat(f.orders.findOrder(order.id()).orElseThrow().status()).isEqualTo("CANCELLED");
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("AVAILABLE");
        }
    }
}
