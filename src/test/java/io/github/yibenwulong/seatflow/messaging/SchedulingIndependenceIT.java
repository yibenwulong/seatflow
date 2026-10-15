package io.github.yibenwulong.seatflow.messaging;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Runs the production @Scheduled registrations against real MySQL, without a broker. */
class SchedulingIndependenceIT {
    @Test void blockedPublisherCannotStarveScheduledExpiry() throws Exception {
        try(var f=new MessagingFixture()) {
            var order=f.create();f.clock.advance(Duration.ofMinutes(15));
            var publicationStarted=new CountDownLatch(1);var releasePublication=new CountDownLatch(1);
            var refundStarted=new CountDownLatch(1);var releaseRefund=new CountDownLatch(1);
            EventTransport blocked=event->{
                publicationStarted.countDown();
                if(!releasePublication.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("test transport timed out");
            };
            var context=new AnnotationConfigApplicationContext();
            context.registerBean(OutboxPublisher.class,()->new OutboxPublisher(f.outbox,blocked,f.clock));
            context.registerBean(OrderExpiryJob.class,()->new OrderExpiryJob(f.jdbc,f.service,f.clock));
            context.register(RabbitMessagingConfig.class);
            try {
                context.refresh();
                assertThat(publicationStarted.await(5,TimeUnit.SECONDS)).isTrue();
                // Reserve the future refund worker's real scheduler too: both network lanes block.
                var refundScheduler=context.getBean("seatflowRefundScheduler",org.springframework.scheduling.TaskScheduler.class);
                refundScheduler.schedule(()->{
                    refundStarted.countDown();
                    try {releaseRefund.await(20,TimeUnit.SECONDS);}
                    catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
                },java.time.Instant.now());
                assertThat(refundStarted.await(2,TimeUnit.SECONDS)).isTrue();
                // Both network lanes remain blocked throughout the actual five-second scheduled scan.
                await().atMost(Duration.ofSeconds(8)).untilAsserted(()->{
                    assertThat(f.orders.findOrder(order.id()).orElseThrow().status()).isEqualTo("CANCELLED");
                    assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("AVAILABLE");
                });
                assertThat(releasePublication.getCount()).isEqualTo(1);
                assertThat(releaseRefund.getCount()).isEqualTo(1);
            } finally {releasePublication.countDown();releaseRefund.countDown();context.close();}
        }
    }
}
