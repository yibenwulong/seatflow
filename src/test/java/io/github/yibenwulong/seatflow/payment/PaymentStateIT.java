package io.github.yibenwulong.seatflow.payment;
import java.math.BigDecimal;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PaymentStateIT {
    @Test void secondCollectionOnCancelledOrderIsDuplicateEvenWhenFirstWasLate() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); var first=f.funds(order.id()); f.booking.cancel(1,order.id());
            f.payments.confirm(first);
            f.jdbc.update("INSERT INTO mock_gateway_payment VALUES(?,?,?,'SUCCESS',?)","second-late-txn",first.merchantRequestNo(),first.amount(),LocalDateTime.ofInstant(f.clock.instant(),ZoneOffset.UTC));
            f.payments.notify("mock",f.gateway.notification("second-late-txn"));
            assertThat(f.refunds.list(1,order.id())).extracting(RefundView::type).containsExactly("LATE_PAYMENT","DUPLICATE_PAYMENT");
            assertThat(f.count("payment_record")).isEqualTo(2);
            assertThat(f.status(order.id())).isEqualTo("CANCELLED");
        }
    }
    @Test void failedPaymentCommitRollsBackSeatsReceiptAndEventButKeepsCollectedFundsForReplay() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); var funds=f.funds(order.id());
            var failing=new io.github.yibenwulong.seatflow.messaging.OutboxRepository(f.jdbc,f.clock) {
                @Override public void append(String type,long id,String payload) { throw new IllegalStateException("outbox write failed"); }
            };
            var service=new PaymentService(f.records,f.orders,f.booking,failing,java.util.List.of(f.gateway),f.tm,f.clock,"mock");
            assertThatThrownBy(()->service.confirm(funds)).isInstanceOf(IllegalStateException.class);
            assertThat(f.status(order.id())).isEqualTo("UNPAID");
            assertThat(f.count("payment_record")).isZero();
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("HELD");
            assertThat(f.gateway.verifyNotification(f.gateway.notification(funds.transactionId()))).isEqualTo(funds);
            f.payments.confirm(funds);
            assertThat(f.count("payment_record")).isEqualTo(1);
            assertThat(f.status(order.id())).isEqualTo("PAID");
        }
    }
    @Test void callbackWaitingForOrderLockUsesTimeAfterAcquisition() throws Exception {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); var funds=f.funds(order.id());
            var held=new CountDownLatch(1); var trying=new CountDownLatch(1); var release=new CountDownLatch(1);
            var observing=new io.github.yibenwulong.seatflow.booking.OrderRepository(f.jdbc) {
                @Override public java.util.Optional<io.github.yibenwulong.seatflow.booking.LockedOrder> lockOrder(long id) {
                    trying.countDown(); return super.lockOrder(id);
                }
            };
            var service=new PaymentService(f.records,observing,f.booking,f.outbox,java.util.List.of(f.gateway),f.tm,f.clock,"mock");
            var pool=Executors.newFixedThreadPool(2);
            try {
                var holder=pool.submit(()->f.tx.execute(s->{ f.orders.lockOrder(order.id()); held.countDown(); await(release); return null; }));
                assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
                var payment=pool.submit(()->service.confirm(funds));
                assertThat(trying.await(10,TimeUnit.SECONDS)).isTrue();
                f.clock.set(order.expireAt()); release.countDown();
                holder.get(15,TimeUnit.SECONDS); payment.get(15,TimeUnit.SECONDS);
                assertThat(f.status(order.id())).isEqualTo("CANCELLED");
                assertThat(f.count("refund_record")).isEqualTo(1);
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }
    @Test void ownPaymentUsesStableServerIntentAndRejectsOtherOwnerOrAlteredFunds() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1);
            var first=f.payments.create(1,order.id());
            assertThat(f.payments.create(1,order.id())).isEqualTo(first);
            assertThat(first.amount()).isEqualByComparingTo(order.totalAmount());
            assertThatThrownBy(()->f.payments.create(2,order.id())).isInstanceOf(RuntimeException.class);
            var funds=f.gateway.complete(first.transactionId());
            assertThatThrownBy(()->f.payments.confirm(new VerifiedPayment("mock",funds.merchantRequestNo(),funds.transactionId(),BigDecimal.ONE))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->f.payments.confirm(new VerifiedPayment("alipay",funds.merchantRequestNo(),funds.transactionId(),funds.amount()))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->f.payments.confirm(new VerifiedPayment("mock","wrong",funds.transactionId(),funds.amount()))).isInstanceOf(RuntimeException.class);
            assertThat(f.count("payment_record")).isZero();
            f.payments.confirm(funds); f.payments.confirm(funds);
            assertThat(f.status(order.id())).isEqualTo("PAID");
            assertThat(f.count("payment_record")).isEqualTo(1);
            assertThat(f.count("refund_record")).isZero();
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("SOLD");
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ORDER_PAID'",Long.class)).isEqualTo(1);
        }
    }
    @Test void beforeDeadlineSellsButEqualAndAfterUnscannedDeadlineCancelAndCompensate() {
        for(int offset:new int[]{-1,0,1}) try(var f=new PaymentFixture()) {
            var order=f.order(1); var funds=f.funds(order.id());
            f.clock.set(order.expireAt().plusSeconds(offset));
            f.payments.confirm(funds);
            assertThat(f.status(order.id())).isEqualTo(offset<0?"PAID":"CANCELLED");
            assertThat(f.count("refund_record")).isEqualTo(offset<0?0:1);
            if(offset>=0) {
                assertThat(f.jdbc.queryForObject("SELECT type FROM refund_record",String.class)).isEqualTo("LATE_PAYMENT");
                assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("AVAILABLE");
            }
        }
    }
    @Test void differentSecondCollectionIsPersistedAndCompensatedWithoutChangingPaidOrder() {
        try(var f=new PaymentFixture()) {
            var order=f.order(1); var funds=f.funds(order.id()); f.payments.confirm(funds);
            f.jdbc.update("INSERT INTO mock_gateway_payment VALUES(?,?,?,'SUCCESS',?)","distinct-txn",funds.merchantRequestNo(),funds.amount(),LocalDateTime.ofInstant(f.clock.instant(),ZoneOffset.UTC));
            var second=f.gateway.verifyNotification(f.gateway.notification("distinct-txn"));
            f.payments.confirm(second); f.payments.confirm(second);
            assertThat(f.count("payment_record")).isEqualTo(2);
            assertThat(f.count("refund_record")).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT type FROM refund_record",String.class)).isEqualTo("DUPLICATE_PAYMENT");
            f.refunds.process(f.refundId());
            assertThat(f.status(order.id())).isEqualTo("PAID");
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=1",String.class)).isEqualTo("SOLD");
        }
    }
    @Test void cancelAndPaymentBothControlledWinnersSerializeOnOrderLock() throws Exception {
        for(boolean payWins:new boolean[]{true,false}) try(var f=new PaymentFixture()) {
            var order=f.order(1); var funds=f.funds(order.id());
            var held=new CountDownLatch(1); var contenderStarted=new CountDownLatch(1); var release=new CountDownLatch(1);
            var pool=Executors.newFixedThreadPool(2);
            try {
                var winner=pool.submit(()->f.tx.execute(s->{
                    f.orders.lockOrder(order.id()).orElseThrow(); held.countDown();
                    await(release);
                    if(payWins) f.payments.confirm(funds); else f.booking.cancel(1,order.id());
                    return null;
                }));
                assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
                var loser=pool.submit(()->{
                    contenderStarted.countDown();
                    if(payWins) assertThatThrownBy(()->f.booking.cancel(1,order.id())).isInstanceOf(RuntimeException.class);
                    else f.payments.confirm(funds);
                });
                assertThat(contenderStarted.await(10,TimeUnit.SECONDS)).isTrue(); release.countDown();
                winner.get(15,TimeUnit.SECONDS); loser.get(15,TimeUnit.SECONDS);
                assertThat(f.status(order.id())).isEqualTo(payWins?"PAID":"CANCELLED");
                assertThat(f.count("refund_record")).isEqualTo(payWins?0:1);
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }
    static void await(CountDownLatch latch) { try { if(!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("Barrier timeout"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
}
