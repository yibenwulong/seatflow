package io.github.yibenwulong.seatflow.messaging;
import io.github.yibenwulong.seatflow.booking.*;
import io.github.yibenwulong.seatflow.seating.SeatMapCache;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
@Service
public class OrderEventConsumer {
    private final JdbcTemplate jdbc; private final OrderRepository orders; private final OrderService service;
    private final SeatMapCache cache; private final TransactionTemplate tx; private final Clock clock;
    public OrderEventConsumer(JdbcTemplate jdbc,OrderRepository orders,OrderService service,SeatMapCache cache,PlatformTransactionManager transactions,Clock clock) {
        this.jdbc=jdbc;this.orders=orders;this.service=service;this.cache=cache;this.tx=new TransactionTemplate(transactions);this.clock=clock;
    }
    public void handle(OrderEvent event) {
        Long session=tx.execute(s->{
            var order=orders.lockOrder(Long.parseLong(event.orderId())).orElse(null);
            if(order==null) return null;
            record("order-events",event.eventId());
            return order.sessionId();
        });
        // Repeating a cache eviction is safe, including after a prior commit/lost ACK.
        if(session!=null) cache.evict(session);
    }
    /** Returns false for early hints WITHOUT recording the final event ID. */
    public boolean handleExpiry(OrderEvent event) {
        Long session=tx.execute(s->{
            var order=orders.lockOrder(Long.parseLong(event.orderId())).orElse(null);
            if(order==null) return null;
            Instant now=clock.instant();
            if(now.isBefore(order.expireAt())) return null;
            if(record("order-expiry",event.eventId())) service.expire(order.id(),now); // REQUIRED joins dedup transaction.
            return order.sessionId();
        });
        if(session!=null) cache.evict(session);
        return session!=null;
    }
    private boolean record(String consumer,String id) {
        // Same-order events serialize on its order lock before dedup; no seat can lock first.
        var existing=jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE consumer_name=? AND event_id=?",Integer.class,consumer,id);
        if(existing!=null && existing>0) return false;
        jdbc.update("INSERT INTO consumed_event(consumer_name,event_id,consumed_at) VALUES(?,?,?)",consumer,id,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        return true;
    }
}
