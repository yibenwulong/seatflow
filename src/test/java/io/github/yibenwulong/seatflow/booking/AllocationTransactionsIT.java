package io.github.yibenwulong.seatflow.booking;

import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.yibenwulong.seatflow.booking.BookingFixture.*;
import static org.assertj.core.api.Assertions.*;

/** Deterministically inject lost conditional holds, while exercising real MySQL rollback/commit. */
class AllocationTransactionsIT {
    @Test void automaticAttemptsUseThreeDifferentCandidatesAndIndependentTransactions() {
        try(var f=new BookingFixture()) {
            var attempts=new TrackingRepository(f,2);
            var service=new OrderService(attempts,new CatalogRepository(f.jdbc),f.outbox,f.transactions,f.clock);
            f.tx.executeWithoutResult(outer->{
                long outerConnection=f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
                var order=service.create(1,adjacent("retry",2));
                assertThat(order.items()).extracting(OrderItemView::seatId).containsExactly(3L,4L);
                assertThat(attempts.firstSeats).containsExactly(1L,2L,3L);
                assertThat(attempts.connections).hasSize(3).doesNotContain(outerConnection);
                outer.setRollbackOnly();
            });
            // Each failed header was rolled back, and final attempt committed independently of outer rollback.
            assertThat(f.count("ticket_order")).isEqualTo(1); assertThat(f.count("order_item")).isEqualTo(2);
            assertThat(f.count("outbox_event")).isEqualTo(1); assertThat(f.held()).isEqualTo(2);
        }
    }
    @Test void automaticRetriesStopAtThreeAndExplicitAttemptsNeverSubstitute() {
        try(var f=new BookingFixture()) {
            var attempts=new TrackingRepository(f,100);
            var service=new OrderService(attempts,new CatalogRepository(f.jdbc),f.outbox,f.transactions,f.clock);
            assertThatThrownBy(()->service.create(1,adjacent("exhaust",2)))
                    .isInstanceOf(io.github.yibenwulong.seatflow.common.ApiException.class);
            assertThat(attempts.firstSeats).containsExactly(1L,2L,3L);
            assertThat(f.count("ticket_order")).isZero(); assertThat(f.held()).isZero();
            attempts.firstSeats.clear();
            assertThatThrownBy(()->service.create(1,explicit("explicit-once",8,5)))
                    .isInstanceOf(io.github.yibenwulong.seatflow.common.ApiException.class);
            assertThat(attempts.firstSeats).containsExactly(5L);
            assertThat(f.count("ticket_order")).isZero(); assertThat(f.held()).isZero();
        }
    }
    @Test void partialConditionalHoldFailureRollsBackFirstSeatAndOrderItems() {
        try(var f=new BookingFixture()) {
            var repository=new OrderRepository(f.jdbc) {
                @Override boolean hold(long session,long seat,long order,Instant expiry) {
                    return seat!=2 && super.hold(session,seat,order,expiry);
                }
            };
            var service=new OrderService(repository,new CatalogRepository(f.jdbc),f.outbox,f.transactions,f.clock);
            assertThatThrownBy(()->service.create(1,explicit("partial",1,2)))
                    .isInstanceOf(io.github.yibenwulong.seatflow.common.ApiException.class);
            assertThat(f.count("ticket_order")).isZero(); assertThat(f.count("order_item")).isZero();
            assertThat(f.count("outbox_event")).isZero(); assertThat(f.held()).isZero();
        }
    }
    private static final class TrackingRepository extends OrderRepository {
        final BookingFixture f;
        final int failures;
        final Set<Long> orders=new HashSet<>(),connections=new HashSet<>();
        final List<Long> firstSeats=new ArrayList<>();
        TrackingRepository(BookingFixture f,int failures) { super(f.jdbc); this.f=f; this.failures=failures; }
        @Override boolean hold(long session,long seat,long order,Instant expiry) {
            if(orders.add(order)) {
                firstSeats.add(seat); connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                if(orders.size()<=failures) return false;
            }
            return super.hold(session,seat,order,expiry);
        }
    }
}
