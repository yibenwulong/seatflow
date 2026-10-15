package io.github.yibenwulong.seatflow.booking;

import io.github.yibenwulong.seatflow.common.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.github.yibenwulong.seatflow.booking.BookingFixture.*;
import static org.assertj.core.api.Assertions.*;

class OrderServiceIT {
    @Test void explicitOneAndFourDispersedSeatsArePricedFromServerAndWrittenAtomically() {
        try(var f=new BookingFixture()) {
            var one=f.service.create(1,explicit("one",1));
            assertThat(one.items()).hasSize(1); assertThat(one.totalAmount()).isEqualByComparingTo("280");
            var four=f.service.create(1,explicit("four",3,8,9,16));
            assertThat(four.items()).hasSize(4); assertThat(four.totalAmount()).isEqualByComparingTo("920");
            assertThat(f.held()).isEqualTo(5); assertThat(f.count("order_item")).isEqualTo(5);
            assertThat(f.count("outbox_event")).isEqualTo(2);
            assertThat(one.expireAt()).isEqualTo(f.clock.instant().plusSeconds(900));
        }
    }
    @Test void rejectsMalformedDuplicateMissingDisabledAndCrossSessionSeatsWithoutPartialWrites() {
        try(var f=new BookingFixture()) {
            List<CreateOrderRequest> bad=List.of(explicit("empty"),explicit("five",1,2,3,4,5),
                    explicit("duplicate",1,1),explicit("missing",1,999),explicit("disabled",1,10),
                    new CreateOrderRequest("both",101,SelectionMode.EXPLICIT,List.of(1L),2,null),
                    new CreateOrderRequest("auto-seats",101,SelectionMode.ADJACENT,List.of(1L),2,null),
                    adjacent("auto-one",1),new CreateOrderRequest("zone",101,SelectionMode.EXPLICIT,List.of(1L),null,"PREMIUM"));
            for(var request:bad) assertThatThrownBy(()->f.service.create(1,request)).isInstanceOf(ApiException.class);
            // A physical seat existing elsewhere is not sufficient: membership must be in this session.
            f.jdbc.update("DELETE FROM session_seat WHERE session_id=101 AND seat_id=16");
            assertThatThrownBy(()->f.service.create(1,explicit("cross-session",1,16))).isInstanceOf(ApiException.class);
            assertThat(f.count("ticket_order")).isZero(); assertThat(f.held()).isZero();
            assertThat(f.count("order_item")).isZero(); assertThat(f.count("outbox_event")).isZero();
        }
    }
    @Test void fourMaximumUnitPricesFitAggregateOnRealMysql() {
        try(var f=new BookingFixture()) {
            f.jdbc.update("UPDATE session_seat SET price=2499999999.99 WHERE session_id=101");
            var order=f.service.create(1,explicit("max-price",1,2,3,4));
            assertThat(order.totalAmount()).isEqualByComparingTo("9999999999.96");
            assertThat(f.jdbc.queryForObject("SELECT total_amount FROM ticket_order WHERE id=?",java.math.BigDecimal.class,order.id()))
                    .isEqualByComparingTo("9999999999.96");
            assertThat(order.items()).hasSize(4).allSatisfy(item->assertThat(item.unitPrice()).isEqualByComparingTo("2499999999.99"));
            assertThat(f.held()).isEqualTo(4);
            assertThat(f.count("outbox_event")).isEqualTo(1);
        }
    }
    @Test void legacyHighSnapshotAggregateOverflowIsClear4xxAndRollsBackAllWrites() {
        try(var f=new BookingFixture()) {
            f.jdbc.update("UPDATE session_seat SET price=9999999999.99 WHERE session_id=101");
            assertThatThrownBy(()->f.service.create(1,explicit("legacy-overflow",1,2)))
                    .isInstanceOfSatisfying(ApiException.class,error->{
                        assertThat(error.status().value()).isEqualTo(400);
                        assertThat(error.code()).isEqualTo("INVALID_REQUEST");
                        assertThat(error.getMessage()).isEqualTo("Order total exceeds 9999999999.99");
                    });
            assertThat(f.count("ticket_order")).isZero();
            assertThat(f.count("order_item")).isZero();
            assertThat(f.count("outbox_event")).isZero();
            assertThat(f.held()).isZero();
        }
    }
    @Test void replayReturnsOriginalOrderAndPayloadConflictIs409() {
        try(var f=new BookingFixture()) {
            var order=f.service.create(1,explicit("replay",2,1));
            assertThat(f.service.create(1,explicit("replay",1,2))).isEqualTo(order);
            assertThatThrownBy(()->f.service.create(1,explicit("replay",3)))
                    .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(409));
            f.service.cancel(1,order.id());
            assertThat(f.service.create(1,explicit("replay",1,2)).status()).isEqualTo("CANCELLED");
            assertThat(f.count("ticket_order")).isEqualTo(1);
        }
    }
    @Test void adjacentNeverCrossesAisleAndConflictedExplicitNeverSubstitutes() {
        try(var f=new BookingFixture()) {
            f.service.create(1,explicit("block",1,2,7,8));
            // Available A3,A4,A5,A6 span an aisle: no group of four.
            assertThatThrownBy(()->f.service.create(1,new CreateOrderRequest("aisle",101,SelectionMode.ADJACENT,List.of(),4,"PREMIUM")))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(()->f.service.create(1,explicit("conflict",1,3))).isInstanceOf(ApiException.class);
            assertThat(f.jdbc.queryForObject("SELECT status FROM session_seat WHERE session_id=101 AND seat_id=3",String.class)).isEqualTo("AVAILABLE");
            var pair=f.service.create(1,new CreateOrderRequest("pair",101,SelectionMode.ADJACENT,List.of(),2,"PREMIUM"));
            assertThat(pair.items()).extracting(OrderItemView::seatId).containsExactly(3L,4L);
        }
    }
    @Test void saleWindowIsStartInclusiveEndExclusiveAndExpiryCappedAtShowTime() {
        try(var f=new BookingFixture()) {
            f.clock.set(Instant.parse("2025-12-31T23:59:59Z"));
            assertThatThrownBy(()->f.service.create(1,explicit("early",1))).isInstanceOf(ApiException.class);
            f.clock.set(Instant.parse("2026-01-01T00:00:00Z"));
            f.service.create(1,explicit("start",1));
            f.clock.set(Instant.parse("2099-12-01T10:55:00Z"));
            assertThat(f.service.create(1,explicit("near-show",2)).expireAt()).isEqualTo(Instant.parse("2099-12-01T11:00:00Z"));
            f.clock.set(Instant.parse("2099-12-01T11:00:00Z"));
            assertThatThrownBy(()->f.service.create(1,explicit("end",3))).isInstanceOf(ApiException.class);
        }
    }
    @Test void failureWritingOutboxRollsBackHeaderItemsAndAllHolds() {
        try(var f=new BookingFixture()) {
            f.jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT injected_outbox_failure CHECK(event_type<>'ORDER_CREATED')");
            assertThatThrownBy(()->f.service.create(1,explicit("rollback",1,2))).isInstanceOf(RuntimeException.class);
            assertThat(f.count("ticket_order")).isZero(); assertThat(f.count("order_item")).isZero(); assertThat(f.held()).isZero();
        }
    }
    @Test void physicalSeatIdsAreUsedIndependentlyInBothSessions() {
        try(var f=new BookingFixture()) {
            f.service.create(1,explicit("session101",1));
            var other=f.service.create(1,new CreateOrderRequest("session102",102,SelectionMode.EXPLICIT,List.of(1L),null,null));
            assertThat(other.items()).extracting(OrderItemView::seatId).containsExactly(1L);
            assertThat(f.held()).isEqualTo(2);
        }
    }
}
