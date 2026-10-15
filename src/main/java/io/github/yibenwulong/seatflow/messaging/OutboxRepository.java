package io.github.yibenwulong.seatflow.messaging;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public OutboxRepository(JdbcTemplate jdbc, Clock clock) { this.jdbc=jdbc; this.clock=clock; }

    /** Append only inside the caller business transaction; never independently commit an event. */
    public void append(String type, long aggregateId, String payload) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox append requires a business transaction");
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        jdbc.update("INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,status,attempts,next_attempt_at,created_at) VALUES(?,?,?,?,'PENDING',0,?,?)",
                UUID.randomUUID().toString(),type,aggregateId,payload,now,now);
    }
}
