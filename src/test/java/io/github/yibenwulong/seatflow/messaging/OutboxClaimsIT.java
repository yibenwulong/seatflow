package io.github.yibenwulong.seatflow.messaging;

import io.github.yibenwulong.seatflow.support.MySqlTestDatabase;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class OutboxClaimsIT {
    @Test void claimsAreExclusiveLeaseFencedAndCrashAtAttemptTenRetainsFailure() {
        try(var db=MySqlTestDatabase.create()) {
            var jdbc=db.jdbc(); var clock=new MessagingFixture.MutableClock();
            var repository=new OutboxRepository(jdbc,clock);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            tx.executeWithoutResult(s->repository.append("ORDER_CREATED",1,"{}"));
            var initial=repository.claim(clock.instant(),Duration.ofSeconds(30),10);
            assertThat(initial).hasSize(1);
            var first=initial.get(0);
            assertThat(repository.claim(clock.instant(),Duration.ofSeconds(30),10)).isEmpty();
            clock.advance(Duration.ofSeconds(30));
            assertThat(repository.markSent(first.eventId(),first.leaseToken())).isFalse();
            var second=repository.claim(clock.instant(),Duration.ofSeconds(30),10).get(0);
            assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
            assertThat(repository.markSent(first.eventId(),first.leaseToken())).isFalse();
            assertThat(repository.markFailed(first.eventId(),first.leaseToken(),clock.instant(),"stale")).isFalse();
            assertThat(repository.markSent(second.eventId(),second.leaseToken())).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event",String.class)).isEqualTo("SENT");
            tx.executeWithoutResult(st->repository.append("ORDER_CREATED",2,"{}"));
            for(int attempt=1;attempt<=10;attempt++) {
                var crashed=repository.claim(clock.instant(),Duration.ofSeconds(30),10);
                assertThat(crashed).hasSize(1);assertThat(crashed.get(0).attempts()).isEqualTo(attempt);
                clock.advance(Duration.ofSeconds(30));
            }
            assertThat(repository.claim(clock.instant(),Duration.ofSeconds(30),10)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id=2",String.class)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE aggregate_id=2",String.class)).contains("exhausted");
        }
    }
    @Test void twoDispatchersCannotClaimTheSameEvent() throws Exception {
        try(var f=new MessagingFixture()) {
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                for(int round=0;round<10;round++) {
                    f.tx.executeWithoutResult(st->{f.outbox.append("ORDER_CREATED",1,"{}");f.outbox.append("ORDER_CREATED",2,"{}");});
                    var start=new java.util.concurrent.CountDownLatch(1);
                    java.util.concurrent.Callable<String> task=()->{
                        start.await();var event=f.outbox.claim(f.clock.instant(),Duration.ofSeconds(30),1).get(0);
                        assertThat(f.outbox.markSent(event.eventId(),event.leaseToken())).isTrue();return event.eventId();
                    };
                    var a=pool.submit(task);var b=pool.submit(task);start.countDown();
                    assertThat(a.get(10,java.util.concurrent.TimeUnit.SECONDS)).isNotEqualTo(b.get(10,java.util.concurrent.TimeUnit.SECONDS));
                }
                assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE status='SENT'",Integer.class)).isEqualTo(20);
            } finally {pool.shutdownNow();}
        }
    }
    @Test void failuresBackOffAndStopAfterTenAttempts() {
        try(var f=new MessagingFixture()) {
            f.tx.executeWithoutResult(s->f.outbox.append("ORDER_CREATED",1,"{}"));
            var publisher=new OutboxPublisher(f.outbox,event->{throw new IllegalStateException("broker down");},f.clock);
            for(int n=1;n<=10;n++) {
                assertThat(publisher.publishBatch()).isEqualTo(1);
                assertThat(f.jdbc.queryForObject("SELECT attempts FROM outbox_event",Integer.class)).isEqualTo(n);
                assertThat(publisher.publishBatch()).isZero();
                f.clock.advance(Duration.ofSeconds(Math.min(60,1L<<(n-1))));
            }
            assertThat(f.jdbc.queryForObject("SELECT status FROM outbox_event",String.class)).isEqualTo("FAILED");
            assertThat(f.jdbc.queryForObject("SELECT last_error FROM outbox_event",String.class)).contains("broker down");
        }
    }
    @Test void brokerSuccessBeforeDatabaseFailureCanBePublishedAgain() {
        try(var f=new MessagingFixture()) {
            f.tx.executeWithoutResult(s->f.outbox.append("ORDER_CREATED",1,"{}"));
            var sent=new ArrayList<String>();
            var broken=new OutboxRepository(f.jdbc,f.clock) {
                @Override public boolean markSent(String id,String token) {throw new IllegalStateException("database fault");}
                @Override public boolean markFailed(String id,String token,Instant next,String reason) {throw new IllegalStateException("database fault");}
            };
            var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(OutboxPublisher.class);
            var captured=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
            boolean additive=logger.isAdditive();captured.start();logger.addAppender(captured);logger.setAdditive(false);
            try {
                new OutboxPublisher(broken,event->sent.add(event.eventId()),f.clock).publishBatch();
                assertThat(captured.list).singleElement().satisfies(log->{
                    assertThat(log.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(log.getFormattedMessage()).contains("Outbox failure persistence failed; lease will recover event");
                    assertThat(log.getThrowableProxy().getMessage()).isEqualTo("database fault");
                });
            } finally {logger.detachAppender(captured);logger.setAdditive(additive);captured.stop();}
            f.clock.advance(Duration.ofSeconds(30));
            new OutboxPublisher(f.outbox,event->sent.add(event.eventId()),f.clock).publishBatch();
            assertThat(sent).hasSize(2); assertThat(sent.get(0)).isEqualTo(sent.get(1));
            assertThat(f.jdbc.queryForObject("SELECT status FROM outbox_event",String.class)).isEqualTo("SENT");
        }
    }
}
