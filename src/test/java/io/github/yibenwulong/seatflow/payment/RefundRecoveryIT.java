package io.github.yibenwulong.seatflow.payment;
import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import io.github.yibenwulong.seatflow.messaging.OutboxRepository;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RefundRecoveryIT {
    @Test void staleFailedAttemptCannotClearNewWorkersLease() throws Exception {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); f.payments.confirm(f.funds(order.id())); var refund=f.refunds.request(1,order.id());
            var firstStarted=new java.util.concurrent.CountDownLatch(1); var secondStarted=new java.util.concurrent.CountDownLatch(1);
            var releaseFirst=new java.util.concurrent.CountDownLatch(1); var releaseSecond=new java.util.concurrent.CountDownLatch(1);
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            PaymentGateway controlled=new PaymentGateway() {
                public String provider() { return "mock"; }
                public GatewayPayment create(PaymentIntent i) { throw new UnsupportedOperationException(); }
                public VerifiedPayment verifyNotification(NotificationInput i) { throw new UnsupportedOperationException(); }
                public GatewayRefund refund(RefundInstruction i) {
                    if(calls.incrementAndGet()==1) {
                        firstStarted.countDown(); PaymentStateIT.await(releaseFirst); throw new IllegalStateException("old worker failed");
                    }
                    secondStarted.countDown(); PaymentStateIT.await(releaseSecond); return f.gateway.refund(i);
                }
            };
            var service=new RefundService(f.records,f.orders,new CatalogRepository(f.jdbc),f.outbox,List.of(controlled),f.tm,f.clock);
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var first=pool.submit(()->assertThatThrownBy(()->service.process(refund.id())).isInstanceOf(IllegalStateException.class));
                assertThat(firstStarted.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                service.process(refund.id()); assertThat(calls.get()).isEqualTo(1);
                f.clock.set(f.clock.instant().plusSeconds(31));
                var second=pool.submit(()->service.process(refund.id()));
                assertThat(secondStarted.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                releaseFirst.countDown(); first.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(f.jdbc.queryForObject("SELECT status FROM refund_record WHERE id=?",String.class,refund.id())).isEqualTo("PROCESSING");
                assertThat(f.jdbc.queryForObject("SELECT lease_until IS NOT NULL FROM refund_record WHERE id=?",Boolean.class,refund.id())).isTrue();
                releaseSecond.countDown(); second.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(f.status(order.id())).isEqualTo("REFUNDED");
                assertThat(f.count("mock_gateway_refund")).isEqualTo(1);
            } finally { releaseFirst.countDown(); releaseSecond.countDown(); pool.shutdownNow(); }
        }
    }
    @Test void secondCollectionAfterNormalRefundCannotChangeTerminalOrderOrNewSeatOwner() {
        try(var f=new PaymentFixture()) {
            var old=f.order(1); var first=f.funds(old.id()); f.payments.confirm(first);
            var normal=f.refunds.request(1,old.id()); f.refunds.process(normal.id());
            var current=f.order(1);
            f.jdbc.update("INSERT INTO mock_gateway_payment VALUES(?,?,?,'SUCCESS',?)","post-refund-txn",first.merchantRequestNo(),first.amount(),LocalDateTime.ofInstant(f.clock.instant(),ZoneOffset.UTC));
            f.payments.notify("mock",f.gateway.notification("post-refund-txn"));
            var compensation=f.refunds.list(1,old.id()).stream().filter(r->r.type().equals("DUPLICATE_PAYMENT")).findFirst().orElseThrow();
            f.refunds.process(compensation.id());
            assertThat(f.status(old.id())).isEqualTo("REFUNDED");
            assertThat(f.jdbc.queryForObject("SELECT owner_order_id FROM session_seat WHERE session_id=101 AND seat_id=1",Long.class)).isEqualTo(current.id());
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("HELD");
        }
    }
    @Test void externalSuccessBeforeGatewayCheckpointFailureReplaysSameRefundNumber() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); f.payments.confirm(f.funds(order.id()));
            var refund=f.refunds.request(1,order.id());
            var failing=new PaymentRepository(f.jdbc) {
                @Override void gatewaySucceeded(long id,GatewayRefund result,Instant now) { throw new IllegalStateException("Injected checkpoint failure"); }
            };
            var service=new RefundService(failing,f.orders,new CatalogRepository(f.jdbc),f.outbox,List.of(f.gateway),f.tm,f.clock);
            assertThatThrownBy(()->service.process(refund.id())).isInstanceOf(IllegalStateException.class);
            assertThat(f.count("mock_gateway_refund")).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT gateway_succeeded_at FROM refund_record WHERE id=?",Object.class,refund.id())).isNull();
            assertThat(f.status(order.id())).isEqualTo("REFUNDING");
            f.clock.set(f.clock.instant().plusSeconds(2));
            f.refunds.process(refund.id());
            assertThat(f.jdbc.queryForObject("SELECT refund_no FROM mock_gateway_refund",String.class)).isEqualTo(refund.refundNo());
            assertThat(f.count("mock_gateway_refund")).isEqualTo(1);
            assertThat(f.status(order.id())).isEqualTo("REFUNDED");
        }
    }
    @Test void exhaustedAttemptWithExpiredLeaseBecomesFailedInsteadOfStickingProcessingForever() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); f.payments.confirm(f.funds(order.id()));
            var refund=f.refunds.request(1,order.id());
            f.jdbc.update("UPDATE refund_record SET attempts=10,status='PROCESSING',lease_until=? WHERE id=?",LocalDateTime.ofInstant(f.clock.instant().minusSeconds(1),ZoneOffset.UTC),refund.id());
            f.refunds.process(refund.id());
            assertThat(f.jdbc.queryForObject("SELECT status FROM refund_record WHERE id=?",String.class,refund.id())).isEqualTo("FAILED");
            assertThat(f.count("mock_gateway_refund")).isZero();
        }
    }
    @Test void failureBackoffIsBoundedAndExternalCallSuspendsAnOuterTransaction() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); f.payments.confirm(f.funds(order.id()));
            var refund=f.refunds.request(1,order.id());
            PaymentGateway failing=new PaymentGateway() {
                public String provider() { return "mock"; }
                public GatewayPayment create(PaymentIntent i) { throw new UnsupportedOperationException(); }
                public VerifiedPayment verifyNotification(NotificationInput i) { throw new UnsupportedOperationException(); }
                public GatewayRefund refund(RefundInstruction i) {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    throw new IllegalStateException("simulated unavailable gateway");
                }
            };
            var service=new RefundService(f.records,f.orders,new CatalogRepository(f.jdbc),f.outbox,List.of(failing),f.tm,f.clock);
            for(int attempt=1;attempt<=10;attempt++) {
                Instant before=f.clock.instant();
                assertThatThrownBy(()->f.tx.execute(s->{ service.process(refund.id()); return null; })).isInstanceOf(IllegalStateException.class);
                var row=f.refunds.list(1,order.id()).get(0);
                assertThat(row.attempts()).isEqualTo(attempt);
                assertThat(row.status()).isEqualTo(attempt==10?"FAILED":"PENDING");
                if(attempt<10) {
                    long delay=Math.min(60,1L<<(attempt-1));
                    assertThat(row.nextAttemptAt()).isEqualTo(before.plusSeconds(delay));
                    service.process(refund.id());
                    assertThat(f.refunds.list(1,order.id()).get(0).attempts()).isEqualTo(attempt);
                    f.clock.set(row.nextAttemptAt());
                }
            }
            service.process(refund.id());
            assertThat(f.refunds.list(1,order.id()).get(0).attempts()).isEqualTo(10);
            assertThat(f.status(order.id())).isEqualTo("REFUNDING");
        }
    }
    @Test void normalRefundGatewaySuccessSurvivesLocalFailureAndRetryReleasesExactlyOnce() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); f.payments.confirm(f.funds(order.id()));
            assertThatThrownBy(()->f.refunds.request(2,order.id())).isInstanceOf(RuntimeException.class);
            var refund=f.refunds.request(1,order.id());
            assertThat(f.refunds.request(1,order.id()).id()).isEqualTo(refund.id());
            var failing=new OutboxRepository(f.jdbc,f.clock) {
                @Override public void append(String type,long id,String payload) {
                    if(type.equals("ORDER_REFUNDED")) throw new IllegalStateException("Injected local commit failure");
                    super.append(type,id,payload);
                }
            };
            var service=new RefundService(f.records,f.orders,new CatalogRepository(f.jdbc),failing,List.of(f.gateway),f.tm,f.clock);
            assertThatThrownBy(()->service.process(refund.id())).isInstanceOf(IllegalStateException.class);
            assertThat(f.count("mock_gateway_refund")).isEqualTo(1);
            assertThat(f.status(order.id())).isEqualTo("REFUNDING");
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("SOLD");
            assertThat(f.refunds.list(1,order.id()).get(0).lastError()).contains("LOCAL_COMPLETION");
            assertThat(f.jdbc.queryForObject("SELECT gateway_succeeded_at IS NOT NULL FROM refund_record WHERE id=?",Boolean.class,refund.id())).isTrue();
            f.clock.set(f.clock.instant().plusSeconds(2)); f.refunds.process(refund.id()); f.refunds.process(refund.id());
            assertThat(f.count("mock_gateway_refund")).isEqualTo(1);
            assertThat(f.status(order.id())).isEqualTo("REFUNDED");
            assertThat(f.jdbc.queryForObject("SELECT status FROM refund_record WHERE id=?",String.class,refund.id())).isEqualTo("COMPLETED");
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ORDER_REFUNDED'",Long.class)).isEqualTo(1);
        }
    }
    @Test void latePaymentRefundNeverReleasesNewOwnersHeldSeats() {
        try(var f=new PaymentFixture()) {
            var old=f.order(1); var funds=f.funds(old.id()); f.booking.cancel(1,old.id());
            var current=f.order(1);
            f.payments.confirm(funds); f.refunds.process(f.refundId()); f.refunds.process(f.refundId());
            assertThat(f.status(old.id())).isEqualTo("CANCELLED");
            assertThat(f.status(current.id())).isEqualTo("UNPAID");
            assertThat(f.jdbc.queryForObject("SELECT owner_order_id FROM session_seat WHERE session_id=101 AND seat_id=1",Long.class)).isEqualTo(current.id());
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("HELD");
        }
    }
    @Test void normalRefundAtShowStartIsRejected() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); f.payments.confirm(f.funds(order.id()));
            f.clock.set(Instant.parse("2099-12-01T11:30:00Z"));
            f.jdbc.update("UPDATE show_session SET starts_at=? WHERE id=101",LocalDateTime.ofInstant(f.clock.instant(),ZoneOffset.UTC));
            assertThatThrownBy(()->f.refunds.request(1,order.id())).isInstanceOf(RuntimeException.class);
            assertThat(f.count("refund_record")).isZero();
        }
    }
}
