package io.github.yibenwulong.seatflow.payment;

import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import io.github.yibenwulong.seatflow.messaging.OutboxRepository;
import io.github.yibenwulong.seatflow.messaging.RabbitMessagingConfig;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RefundWorkerIT {
    @Test void gatewaySuccessLocalFailureRecoversWithSameNumberAndContinuesOtherRows() {
        try(var f=new PaymentFixture()) {
            var a=f.order(1);f.payments.confirm(f.funds(a.id()));var first=f.refunds.request(1,a.id());
            var b=f.order(2);f.payments.confirm(f.funds(b.id()));var second=f.refunds.request(1,b.id());
            var failOnce=new AtomicBoolean(true);
            var broken=new OutboxRepository(f.jdbc,f.clock) {
                @Override public void append(String type,long id,String payload) {
                    if(type.equals("ORDER_REFUNDED") && id==a.id() && failOnce.compareAndSet(true,false)) throw new IllegalStateException("local completion fault");
                    super.append(type,id,payload);
                }
            };
            var service=new RefundService(f.records,f.orders,new CatalogRepository(f.jdbc),broken,List.of(f.gateway),f.tm,f.clock);
            var worker=new RefundWorker(f.jdbc,service,f.clock);
            var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(RefundWorker.class);
            var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
            boolean additive=logger.isAdditive();logs.start();logger.addAppender(logs);logger.setAdditive(false);
            try {
                assertThat(worker.runOnce(f.clock.instant())).isEqualTo(2);
                assertThat(logs.list).singleElement().satisfies(event->{
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("Refund recovery failed for "+first.id(),"IllegalStateException");
                });
            } finally {logger.detachAppender(logs);logger.setAdditive(additive);logs.stop();}
            assertThat(f.status(a.id())).isEqualTo("REFUNDING");assertThat(f.status(b.id())).isEqualTo("REFUNDED");
            var pending=f.refunds.list(1,a.id()).get(0);
            assertThat(pending.status()).isEqualTo("GATEWAY_SUCCEEDED");assertThat(pending.lastError()).contains("LOCAL_COMPLETION");
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("SOLD");
            assertThat(f.count("mock_gateway_refund")).isEqualTo(2);
            assertThat(worker.runOnce(f.clock.instant())).isZero();
            f.clock.set(pending.nextAttemptAt());
            assertThat(worker.runOnce(f.clock.instant())).isEqualTo(1);
            assertThat(worker.runOnce(f.clock.instant())).isZero();
            assertThat(f.status(a.id())).isEqualTo("REFUNDED");assertThat(f.count("mock_gateway_refund")).isEqualTo(2);
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("AVAILABLE");
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_refund WHERE refund_no=?",Integer.class,first.refundNo())).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ORDER_REFUNDED' AND aggregate_id=?",Integer.class,a.id())).isEqualTo(1);
            assertThat(f.refunds.list(1,b.id()).get(0).id()).isEqualTo(second.id());
        }
    }
    @Test void selectsNullDueExpiredLeasesIncludingAttemptTenAndExcludesFutureAndTerminalRows() {
        try(var f=new PaymentFixture()) {
            var ids=new ArrayList<Long>();
            for(long seat=1;seat<=4;seat++) {var order=f.order(seat);f.payments.confirm(f.funds(order.id()));ids.add(f.refunds.request(1,order.id()).id());}
            var now=f.clock.instant();
            f.jdbc.update("UPDATE refund_record SET status='PROCESSING',attempts=10,next_attempt_at=NULL,lease_until=? WHERE id=?",utc(now.minusSeconds(1)),ids.get(0));
            f.jdbc.update("UPDATE refund_record SET status='PROCESSING',attempts=1,next_attempt_at=NULL,lease_until=? WHERE id=?",utc(now.plusSeconds(30)),ids.get(1));
            f.jdbc.update("UPDATE refund_record SET next_attempt_at=? WHERE id=?",utc(now.plusSeconds(10)),ids.get(2));
            f.refunds.process(ids.get(3));
            var worker=new RefundWorker(f.jdbc,f.refunds,f.clock);
            assertThat(worker.runOnce(now)).isEqualTo(1);
            assertThat(state(f,ids.get(0))).isEqualTo("FAILED");assertThat(state(f,ids.get(1))).isEqualTo("PROCESSING");
            assertThat(state(f,ids.get(2))).isEqualTo("PENDING");assertThat(f.count("mock_gateway_refund")).isEqualTo(1);
            f.clock.set(now.plusSeconds(31));assertThat(worker.runOnce(f.clock.instant())).isEqualTo(2);
            assertThat(worker.runOnce(f.clock.instant())).isZero();
            assertThat(state(f,ids.get(0))).isEqualTo("FAILED");assertThat(f.count("mock_gateway_refund")).isEqualTo(3);
        }
    }
    @Test void actualScheduledWorkerUsesDedicatedSchedulerAndCanBeDisabled() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1);f.payments.confirm(f.funds(order.id()));f.refunds.request(1,order.id());
            var thread=new AtomicReference<String>();
            PaymentGateway gateway=new PaymentGateway() {
                public String provider(){return "mock";}
                public GatewayPayment create(PaymentIntent ignored){throw new UnsupportedOperationException();}
                public VerifiedPayment verifyNotification(NotificationInput ignored){throw new UnsupportedOperationException();}
                public GatewayRefund refund(RefundInstruction instruction){thread.set(Thread.currentThread().getName());return f.gateway.refund(instruction);}
            };
            var service=new RefundService(f.records,f.orders,new CatalogRepository(f.jdbc),f.outbox,List.of(gateway),f.tm,f.clock);
            try(var context=context(f,service,true)) {
                await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(f.status(order.id())).isEqualTo("REFUNDED"));
                assertThat(thread.get()).startsWith("seatflow-refund-");
            }
            try(var context=context(f,service,false)) {assertThat(context.getBeansOfType(RefundWorker.class)).isEmpty();}
        }
    }
    private static AnnotationConfigApplicationContext context(PaymentFixture f,RefundService service,boolean enabled) {
        var context=new AnnotationConfigApplicationContext();
        var properties=new HashMap<String,Object>();
        properties.put("seatflow.messaging.publisher-enabled",false);properties.put("seatflow.messaging.expiry-enabled",false);
        if(!enabled) properties.put("seatflow.payment.refund-worker-enabled",false); // enabled case verifies default-on.
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("worker-test",properties));
        context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,()->f.jdbc);
        context.registerBean(RefundService.class,()->service);context.registerBean(Clock.class,()->f.clock);
        context.register(RabbitMessagingConfig.class,RefundWorker.class);context.refresh();return context;
    }
    private static LocalDateTime utc(Instant now){return LocalDateTime.ofInstant(now,ZoneOffset.UTC);}
    private static String state(PaymentFixture f,long id){return f.jdbc.queryForObject("SELECT status FROM refund_record WHERE id=?",String.class,id);}
}
