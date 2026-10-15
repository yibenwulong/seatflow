package io.github.yibenwulong.seatflow.payment;

import java.time.*;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Poll IDs only; RefundService owns the claim, retries and every funds-state write. */
@Component
@ConditionalOnProperty(name="seatflow.payment.refund-worker-enabled",havingValue="true",matchIfMissing=true)
public class RefundWorker {
    private static final Logger log=LoggerFactory.getLogger(RefundWorker.class);
    private final JdbcTemplate jdbc;
    private final RefundService refunds;
    private final Clock clock;

    public RefundWorker(JdbcTemplate jdbc,RefundService refunds,Clock clock) {
        this.jdbc=jdbc;this.refunds=refunds;this.clock=clock;
    }

    /** Returns eligible IDs attempted, including failures persisted by RefundService. */
    public int runOnce(Instant now) {
        Objects.requireNonNull(now,"now");
        var at=LocalDateTime.ofInstant(now,ZoneOffset.UTC);
        var ids=jdbc.queryForList("SELECT id FROM refund_record WHERE status IN ('PENDING','PROCESSING','GATEWAY_SUCCEEDED') AND (next_attempt_at IS NULL OR next_attempt_at<=?) AND (lease_until IS NULL OR lease_until<=?) ORDER BY id LIMIT 200",Long.class,at,at);
        for(long id:ids) {
            try {refunds.process(id);}
            catch(RuntimeException failure) {
                // The service saved its bounded safe phase/retry state; continue independent rows.
                log.warn("Refund recovery failed for {} ({})",id,failure.getClass().getSimpleName());
            }
        }
        return ids.size();
    }

    @Scheduled(scheduler="seatflowRefundScheduler",fixedDelay=1000,initialDelay=1000)
    public void scheduledRun() {runOnce(clock.instant());}
}
