package io.github.yibenwulong.seatflow.booking;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static io.github.yibenwulong.seatflow.booking.BookingFixture.*;
import static org.assertj.core.api.Assertions.*;

class ReleaseReplayIT {
    @Test void expiresOnlyAtDeadlineAndOldExpiryCannotReleaseNewOwnersSeats() {
        try(var f=new BookingFixture()) {
            var first=f.service.create(1,explicit("R1",1,2));
            assertThat(f.service.expire(first.id(),first.expireAt().minusNanos(1000))).isFalse();
            assertThat(f.held()).isEqualTo(2);
            f.clock.set(first.expireAt());
            assertThat(f.service.expire(first.id(),f.clock.instant())).isTrue();
            var second=f.service.create(2,explicit("R2",1,2));
            assertThat(f.service.expire(first.id(),f.clock.instant())).isFalse();
            assertThat(f.service.cancel(1,first.id()).status()).isEqualTo("CANCELLED");
            assertThat(f.service.cancel(1,first.id()).status()).isEqualTo("CANCELLED");
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE owner_order_id=? AND status='HELD'",Long.class,second.id())).isEqualTo(2);
            assertThat(f.count("outbox_event")).isEqualTo(3);
        }
    }
    @Test void cancellationReplayAndExpiryOnPaidOrMissingOrdersHaveNoEffects() {
        try(var f=new BookingFixture()) {
            var order=f.service.create(1,explicit("cancel",1));
            assertThat(f.service.cancel(1,order.id())).isEqualTo(f.service.cancel(1,order.id()));
            assertThat(f.count("outbox_event")).isEqualTo(2);
            assertThat(f.service.expire(9999,Instant.MAX)).isFalse();
            var paid=f.service.create(1,explicit("paid",2));
            f.tx.executeWithoutResult(s->{ f.orders.lockOrder(paid.id());
                f.jdbc.update("UPDATE ticket_order SET status='PAID' WHERE id=?",paid.id());
                f.jdbc.update("UPDATE session_seat SET status='SOLD',hold_expires_at=NULL WHERE owner_order_id=?",paid.id()); });
            assertThat(f.service.expire(paid.id(),paid.expireAt())).isFalse();
            assertThatThrownBy(()->f.service.cancel(1,paid.id())).isInstanceOf(io.github.yibenwulong.seatflow.common.ApiException.class);
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE owner_order_id=?",String.class,paid.id())).isEqualTo("SOLD");
        }
    }
    @Test void expiryJoinsCallerHoldingOrderLockAndRollbackUndoesCancellationSeatsAndOutbox() {
        try(var f=new BookingFixture()) {
            var order=f.service.create(1,explicit("joined",1,2));
            f.tx.executeWithoutResult(status->{
                var locked=f.orders.lockOrder(order.id()).orElseThrow();
                assertThat(locked.userId()).isEqualTo(1);
                assertThat(f.service.expire(order.id(),order.expireAt())).isTrue();
                assertThat(f.held()).isZero();
                assertThat(f.count("outbox_event")).isEqualTo(2);
                status.setRollbackOnly();
            });
            assertThat(f.service.get(1,order.id()).status()).isEqualTo("UNPAID");
            assertThat(f.held()).isEqualTo(2); assertThat(f.count("outbox_event")).isEqualTo(1);
        }
    }
    @Test void cancellationJoinsCallerAndLocksAndOutboxRequireBusinessTransaction() {
        try(var f=new BookingFixture()) {
            assertThatThrownBy(()->f.orders.lockOrder(1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->f.outbox.append("TEST",1,"{}")).isInstanceOf(IllegalStateException.class);
            var order=f.service.create(1,explicit("cancel-joined",1));
            f.tx.executeWithoutResult(status->{ f.orders.lockOrder(order.id()); f.service.cancel(1,order.id()); status.setRollbackOnly(); });
            assertThat(f.service.get(1,order.id()).status()).isEqualTo("UNPAID");
            assertThat(f.held()).isEqualTo(1); assertThat(f.count("outbox_event")).isEqualTo(1);
        }
    }
}
