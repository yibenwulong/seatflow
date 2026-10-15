package io.github.yibenwulong.seatflow.messaging;
import io.github.yibenwulong.seatflow.support.MySqlTestDatabase;
import io.github.yibenwulong.seatflow.booking.*;
import io.github.yibenwulong.seatflow.catalog.CatalogRepository;
import java.time.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
class MessagingFixture implements AutoCloseable {
    final MySqlTestDatabase db=MySqlTestDatabase.create();
    final org.springframework.jdbc.core.JdbcTemplate jdbc=db.jdbc();
    final DataSourceTransactionManager transactions=new DataSourceTransactionManager(jdbc.getDataSource());
    final TransactionTemplate tx=new TransactionTemplate(transactions);
    final MutableClock clock=new MutableClock();
    final OutboxRepository outbox=new OutboxRepository(jdbc,clock);
    final OrderRepository orders=new OrderRepository(jdbc);
    final OrderService service=new OrderService(orders,new CatalogRepository(jdbc),outbox,transactions,clock);
    OrderView create() {return service.create(1,new CreateOrderRequest(java.util.UUID.randomUUID().toString(),101,SelectionMode.EXPLICIT,java.util.List.of(1L),null,null));}
    public void close(){db.close();}
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-15T00:00:00Z");
        void advance(Duration d){now=now.plus(d);}
        public Instant instant(){return now;} public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;}
    }
}
