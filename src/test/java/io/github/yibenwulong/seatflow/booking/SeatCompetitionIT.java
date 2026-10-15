package io.github.yibenwulong.seatflow.booking;

import io.github.yibenwulong.seatflow.common.ApiException;
import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import io.github.yibenwulong.seatflow.catalog.ShowSession;
import io.github.yibenwulong.seatflow.messaging.OutboxRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import static io.github.yibenwulong.seatflow.booking.BookingFixture.*;
import static org.assertj.core.api.Assertions.*;

class SeatCompetitionIT {
    @Test void concurrentReplayAlsoWorksWithReadCommittedWhenWinnerCommitsBeforeInventoryRead() throws Exception {
        concurrentReplay(Connection.TRANSACTION_READ_COMMITTED,WindowChange.NONE,false);
    }
    @Test void replayAfterExactSalesEndReturnsOriginalFromFreshRepeatableReadTransaction() throws Exception {
        concurrentReplay(Connection.TRANSACTION_REPEATABLE_READ,WindowChange.SALES_END,false);
    }
    @Test void replayAfterExactShowStartReturnsOriginalOrder() throws Exception {
        concurrentReplay(Connection.TRANSACTION_REPEATABLE_READ,WindowChange.SHOW_START,false);
    }
    @Test void replayAfterSaleClosureReturnsOriginalOrder() throws Exception {
        concurrentReplay(Connection.TRANSACTION_READ_COMMITTED,WindowChange.CLOSED,false);
    }
    @Test void replayAfterSalesEndStillRejectsDifferentPayloadAsIdempotencyConflict() throws Exception {
        concurrentReplay(Connection.TRANSACTION_REPEATABLE_READ,WindowChange.SALES_END,true);
    }
    private enum WindowChange { NONE, SALES_END, SHOW_START, CLOSED }
    private static void concurrentReplay(int isolation,WindowChange change,boolean differentPayload) throws Exception {
        try(var f=new BookingFixture()) {
            if(change==WindowChange.SALES_END)
                f.jdbc.update("UPDATE show_session SET sales_end_at='2026-10-15 00:01:00' WHERE id=101");
            var dataSource=new org.springframework.jdbc.datasource.DelegatingDataSource(f.jdbc.getDataSource()) {
                @Override public Connection getConnection() throws SQLException {
                    var connection=super.getConnection(); connection.setTransactionIsolation(isolation); return connection;
                }
            };
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            CountDownLatch missedKey=new CountDownLatch(1),winnerCommitted=new CountDownLatch(1);
            var catalog=new CatalogRepository(jdbc) {
                @Override public Optional<ShowSession> findSession(long sessionId) {
                    missedKey.countDown();
                    try { if(!winnerCommitted.await(20,TimeUnit.SECONDS)) throw new AssertionError("winner timeout"); }
                    catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                    return super.findSession(sessionId);
                }
            };
            var transactions=new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
            var service=new OrderService(new OrderRepository(jdbc),catalog,new OutboxRepository(jdbc,f.clock),transactions,f.clock);
            var pool=Executors.newSingleThreadExecutor();
            try {
                java.util.function.Supplier<Object> request=()->{
                    try { return service.create(1,explicit("window-key",differentPayload?2:1)); } catch(ApiException e) { return e; }
                };
                var replay=pool.submit(()->{
                    if(change==WindowChange.SALES_END && !differentPayload) {
                        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(caller->{
                            // Establish an outer caller's stale REPEATABLE_READ snapshot as well.
                            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order",Long.class)).isZero();
                            var result=request.get(); caller.setRollbackOnly(); return result;
                        });
                    }
                    return request.get();
                });
                assertThat(missedKey.await(20,TimeUnit.SECONDS)).isTrue();
                var winner=f.service.create(1,explicit("window-key",1));
                switch(change) {
                    case SALES_END -> f.clock.set(java.time.Instant.parse("2026-10-15T00:01:00Z"));
                    case SHOW_START -> f.clock.set(java.time.Instant.parse("2099-12-01T11:00:00Z"));
                    case CLOSED -> f.jdbc.update("UPDATE show_session SET status='CLOSED' WHERE id=101");
                    case NONE -> { }
                }
                winnerCommitted.countDown();
                var result=replay.get(20,TimeUnit.SECONDS);
                if(differentPayload) assertThat(result).isInstanceOfSatisfying(ApiException.class,e->{
                    assertThat(e.status().value()).isEqualTo(409); assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
                else assertThat(result).isEqualTo(winner);
                assertThat(f.count("ticket_order")).isEqualTo(1);
                assertThat(f.count("outbox_event")).isEqualTo(1); assertThat(f.held()).isEqualTo(1);
            } finally { winnerCommitted.countDown(); pool.shutdownNow(); }
        }
    }
    @Test void thirtyTwoUsersCompeteForOneSeatWithExactlyOneCompleteWinner() throws Exception {
        try(var f=new BookingFixture()) {
            users(f,32);
            var results=race(32,i->f.service.create(i+10,explicit("single-"+i,1)));
            assertThat(results.stream().filter(OrderView.class::isInstance)).hasSize(1);
            assertConflictsOnly(results);
            assertThat(f.count("ticket_order")).isEqualTo(1); assertThat(f.count("order_item")).isEqualTo(1);
            assertThat(f.held()).isEqualTo(1); assertThat(f.count("outbox_event")).isEqualTo(1);
            experiment(f,"32-users-one-seat",results);
        }
    }
    @Test void thirtyTwoSameKeyConcurrentRequestsReturnExactlyTheSameOrder() throws Exception {
        try(var f=new BookingFixture()) {
            var results=race(32,i->f.service.create(1,explicit("same-key",1,2)));
            assertThat(results).allMatch(OrderView.class::isInstance);
            assertThat(new HashSet<>(results)).hasSize(1);
            assertThat(f.count("ticket_order")).isEqualTo(1); assertThat(f.count("order_item")).isEqualTo(2);
            assertThat(f.count("outbox_event")).isEqualTo(1); assertThat(f.held()).isEqualTo(2);
            experiment(f,"32-concurrent-same-key",results);
        }
    }
    @Test void sameKeyWithDifferentPayloadHasOneWinnerAndOne409() throws Exception {
        try(var f=new BookingFixture()) {
            var results=race(2,i->f.service.create(1,explicit("different",i+1)));
            assertThat(results.stream().filter(OrderView.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(ApiException.class::isInstance).map(ApiException.class::cast))
                    .singleElement().satisfies(e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
            assertThat(f.count("ticket_order")).isEqualTo(1); assertThat(f.held()).isEqualTo(1);
            experiment(f,"same-key-different-payload",results);
        }
    }
    @Test void overlappingTwoThreeAndFourSeatRequestsNeverLeavePartialOrDuplicateOwnership() throws Exception {
        try(var f=new BookingFixture()) {
            users(f,24);
            var results=race(24,i->f.service.create(i+10,adjacent("overlap-"+i,2+i%3)));
            assertConflictsOnly(results);
            var winners=results.stream().filter(OrderView.class::isInstance).map(OrderView.class::cast).toList();
            assertThat(winners).isNotEmpty();
            Set<Long> occupied=new HashSet<>();
            for(var order:winners) {
                assertThat(order.items()).hasSizeBetween(2,4);
                var ids=order.items().stream().map(OrderItemView::seatId).toList();
                assertThat(ids).allMatch(occupied::add);
                assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE owner_order_id=? AND status='HELD'",Long.class,order.id())).isEqualTo(ids.size());
                var layout=f.jdbc.queryForList("SELECT row_no,block_id,price_zone,position FROM session_seat WHERE owner_order_id=? ORDER BY position",order.id());
                assertThat(layout.stream().map(row->row.get("row_no"))).containsOnly(layout.get(0).get("row_no"));
                assertThat(layout.stream().map(row->row.get("block_id"))).containsOnly(layout.get(0).get("block_id"));
                assertThat(layout.stream().map(row->row.get("price_zone"))).containsOnly(layout.get(0).get("price_zone"));
                for(int j=1;j<layout.size();j++) assertThat(((Number)layout.get(j).get("position")).intValue())
                        .isEqualTo(((Number)layout.get(j-1).get("position")).intValue()+1);
            }
            assertThat(f.held()).isEqualTo(occupied.size());
            assertThat(f.count("order_item")).isEqualTo(occupied.size());
            assertThat(f.count("ticket_order")).isEqualTo(winners.size());
            assertThat(f.count("outbox_event")).isEqualTo(winners.size());
            experiment(f,"overlapping-2-3-4",results);
        }
    }
    private static void users(BookingFixture f,int count) {
        for(int i=0;i<count;i++) f.jdbc.update("INSERT INTO user_account(id,username,password_hash,role,enabled,created_at) VALUES(?,?,'unused','USER',true,'2026-01-01')",i+10,"race-"+i);
    }
    private static void experiment(BookingFixture f,String scenario,List<Object> results) {
        long successes=results.stream().filter(OrderView.class::isInstance).count();
        long conflicts=results.stream().filter(ApiException.class::isInstance).count();
        System.out.printf("SEATFLOW_EXPERIMENT {\"scenario\":\"%s\",\"requests\":%d,\"successResponses\":%d,\"conflicts\":%d,\"orders\":%d,\"items\":%d,\"heldSeats\":%d,\"outboxEvents\":%d}%n",
                scenario,results.size(),successes,conflicts,f.count("ticket_order"),f.count("order_item"),f.held(),f.count("outbox_event"));
    }
    private static void assertConflictsOnly(List<Object> results) {
        assertThat(results).allSatisfy(result->{
            if(result instanceof OrderView) return;
            assertThat(result).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(409));
        });
    }
    static List<Object> race(int count,IntFunction<OrderView> action) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(count);
        CountDownLatch ready=new CountDownLatch(count),start=new CountDownLatch(1);
        List<Future<Object>> futures=new ArrayList<>();
        try {
            for(int i=0;i<count;i++) { final int index=i; futures.add(pool.submit(()->{
                ready.countDown(); if(!start.await(20,TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                try { return action.apply(index); } catch(ApiException conflict) { return conflict; }
            })); }
            assertThat(ready.await(20,TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<Object> results=new ArrayList<>(); for(var future:futures) results.add(future.get(45,TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
