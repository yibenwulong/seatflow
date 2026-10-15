package io.github.yibenwulong.seatflow.messaging;
import java.time.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
@Service
public class OutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository repository; private final EventTransport transport; private final Clock clock;
    public OutboxPublisher(OutboxRepository repository,EventTransport transport,Clock clock) {
        this.repository=repository;this.transport=transport;this.clock=clock;
    }
    public int publishBatch() {
        var claims=repository.claim(clock.instant(),Duration.ofSeconds(30),2);
        for(var event:claims) {
            try {
                transport.publish(event);
                if(!repository.markSent(event.eventId(),event.leaseToken())) log.warn("Outbox claim no longer owned: {}",event.eventId());
            } catch(Exception failure) {
                try {repository.markFailed(event.eventId(),event.leaseToken(),clock.instant().plusSeconds(backoff(event.attempts())),failure.toString());}
                catch(RuntimeException persistence) {log.warn("Outbox failure persistence failed; lease will recover event {}",event.eventId(),persistence);}
            }
        }
        return claims.size();
    }
    static long backoff(int attempt) {return Math.min(60,1L<<Math.min(6,Math.max(0,attempt-1)));}
}
