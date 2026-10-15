package io.github.yibenwulong.seatflow.booking;

import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import io.github.yibenwulong.seatflow.messaging.OutboxRepository;
import io.github.yibenwulong.seatflow.support.MySqlTestDatabase;
import java.time.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class BookingFixture implements AutoCloseable {
    final MySqlTestDatabase db=MySqlTestDatabase.create();
    final JdbcTemplate jdbc=db.jdbc();
    final DataSourceTransactionManager transactions=new DataSourceTransactionManager(jdbc.getDataSource());
    final TransactionTemplate tx=new TransactionTemplate(transactions);
    final MutableClock clock=new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
    final OrderRepository orders=new OrderRepository(jdbc);
    final OutboxRepository outbox=new OutboxRepository(jdbc,clock);
    final OrderService service=new OrderService(orders,new CatalogRepository(jdbc),outbox,transactions,clock);
    static CreateOrderRequest explicit(String key,long... ids) {
        return new CreateOrderRequest(key,101,SelectionMode.EXPLICIT,java.util.Arrays.stream(ids).boxed().toList(),null,null);
    }
    static CreateOrderRequest adjacent(String key,int count) {
        return new CreateOrderRequest(key,101,SelectionMode.ADJACENT,List.of(),count,null);
    }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    long held() { return jdbc.queryForObject("SELECT COUNT(*) FROM session_seat WHERE status='HELD'",Long.class); }
    @Override public void close() { db.close(); }
    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now=now; }
        void set(Instant now) { this.now=now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
