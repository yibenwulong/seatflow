package io.github.yibenwulong.seatflow.payment;
import io.github.yibenwulong.seatflow.booking.*;
import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import io.github.yibenwulong.seatflow.messaging.OutboxRepository;
import io.github.yibenwulong.seatflow.support.MySqlTestDatabase;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
final class PaymentFixture implements AutoCloseable {
    final MySqlTestDatabase db=MySqlTestDatabase.create();
    final JdbcTemplate jdbc=db.jdbc();
    final DataSourceTransactionManager tm=new DataSourceTransactionManager(jdbc.getDataSource());
    final TransactionTemplate tx=new TransactionTemplate(tm);
    final MutableClock clock=new MutableClock();
    final OrderRepository orders=new OrderRepository(jdbc);
    final OutboxRepository outbox=new OutboxRepository(jdbc,clock);
    final OrderService booking=new OrderService(orders,new CatalogRepository(jdbc),outbox,tm,clock);
    final PaymentRepository records=new PaymentRepository(jdbc);
    final MockPaymentGateway gateway=new MockPaymentGateway(jdbc,tm,clock,"secret");
    final PaymentService payments=new PaymentService(records,orders,booking,outbox,List.of(gateway),tm,clock,"mock");
    final RefundService refunds=new RefundService(records,orders,new CatalogRepository(jdbc),outbox,List.of(gateway),tm,clock);
    OrderView order(long seat) { return booking.create(1,new CreateOrderRequest(UUID.randomUUID().toString(),101,SelectionMode.EXPLICIT,List.of(seat),null,null)); }
    VerifiedPayment funds(long order) { var p=payments.create(1,order); return gateway.complete(p.transactionId()); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    String status(long order) { return orders.findOrder(order).orElseThrow().status(); }
    long refundId() { return jdbc.queryForObject("SELECT MIN(id) FROM refund_record",Long.class); }
    @Override public void close() { db.close(); }
    static final class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-10-15T00:00:00Z");
        void set(Instant value) { now=value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
