package io.github.yibenwulong.seatflow.messaging;
import io.github.yibenwulong.seatflow.booking.OrderService;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Component
public class OrderExpiryJob {
    private static final Logger log=LoggerFactory.getLogger(OrderExpiryJob.class);
    private final JdbcTemplate jdbc;private final OrderService orders;private final Clock clock;
    public OrderExpiryJob(JdbcTemplate jdbc,OrderService orders,Clock clock) {this.jdbc=jdbc;this.orders=orders;this.clock=clock;}
    public int runOnce(Instant now) {
        var ids=jdbc.queryForList("SELECT id FROM ticket_order WHERE status='UNPAID' AND expire_at<=? ORDER BY expire_at,id LIMIT 200",Long.class,LocalDateTime.ofInstant(now,ZoneOffset.UTC));
        int cancelled=0;
        for(long id:ids) {
            try {if(orders.expire(id,now)) cancelled++;}
            catch(RuntimeException failure) {log.warn("Expiry scan failed for order {}; later scans retry",id,failure);}
        }
        return cancelled;
    }
    public void scheduledRun() {runOnce(clock.instant());}
}
