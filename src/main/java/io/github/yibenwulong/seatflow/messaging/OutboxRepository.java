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

    /** A short independent transaction; SKIP LOCKED permits multiple dispatchers. */
    public java.util.List<OutboxClaim> claim(java.time.Instant now,java.time.Duration lease,int limit) {
        if(limit<1 || limit>1000 || lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("Invalid claim bounds");
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // READ COMMITTED avoids range/gap locks while changing the dispatch index.
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        for(int retry=0;;retry++) {
            try {return tx.execute(status->{
            var at=utc(now);
            var exhausted=jdbc.queryForList("SELECT id FROM outbox_event WHERE attempts>=10 AND (status='PENDING' OR (status='IN_FLIGHT' AND lease_until<=?)) ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",Long.class,at,limit);
            for(long id:exhausted) jdbc.update("UPDATE outbox_event SET status='FAILED',lease_until=NULL,lease_owner=NULL,last_error=CONCAT('Claim lease expired; attempts exhausted',CASE WHEN last_error IS NULL THEN '' ELSE CONCAT(': ',LEFT(last_error,900)) END) WHERE id=?",id);
            var candidates=jdbc.query("SELECT event_id,event_type,aggregate_id,payload,attempts FROM outbox_event WHERE attempts<10 AND ((status='PENDING' AND next_attempt_at<=?) OR (status='IN_FLIGHT' AND lease_until<=?)) ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs,n)->new OutboxClaim(rs.getString("event_id"),rs.getString("event_type"),rs.getLong("aggregate_id"),rs.getString("payload"),rs.getInt("attempts")+1,UUID.randomUUID().toString()),at,at,limit);
            for(var event:candidates) jdbc.update("UPDATE outbox_event SET status='IN_FLIGHT',attempts=?,lease_until=?,lease_owner=? WHERE event_id=?",event.attempts(),utc(now.plus(lease)),event.leaseToken(),event.eventId());
            return java.util.List.copyOf(candidates);
            });}
            catch(org.springframework.dao.PessimisticLockingFailureException contention) {
                if(retry>=2) throw contention; // bounded whole-transaction retry; rolled-back attempts do not count.
            }
        }
    }
    /** Completion requires this still-live claim; a stale process cannot overwrite a new owner. */
    public boolean markSent(String id,String token) {
        return jdbc.update("UPDATE outbox_event SET status='SENT',sent_at=?,lease_until=NULL,lease_owner=NULL,last_error=NULL WHERE event_id=? AND status='IN_FLIGHT' AND lease_owner=? AND lease_until>?",utc(clock.instant()),id,token,utc(clock.instant()))==1;
    }
    public boolean markFailed(String id,String token,java.time.Instant next,String reason) {
        String bounded=reason==null?"Unknown delivery failure":reason.substring(0,Math.min(1000,reason.length()));
        return jdbc.update("UPDATE outbox_event SET status=CASE WHEN attempts>=10 THEN 'FAILED' ELSE 'PENDING' END,next_attempt_at=?,last_error=?,lease_until=NULL,lease_owner=NULL WHERE event_id=? AND status='IN_FLIGHT' AND lease_owner=? AND lease_until>?",utc(next),bounded,id,token,utc(clock.instant()))==1;
    }
    private static LocalDateTime utc(java.time.Instant value) {return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}

    /** Append only inside the caller business transaction; never independently commit an event. */
    public void append(String type, long aggregateId, String payload) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox append requires a business transaction");
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        jdbc.update("INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,status,attempts,next_attempt_at,created_at) VALUES(?,?,?,?,'PENDING',0,?,?)",
                UUID.randomUUID().toString(),type,aggregateId,payload,now,now);
    }
}
