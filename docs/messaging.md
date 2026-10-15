# Messaging and recovery

MySQL owns booking state. RabbitMQ transports order events and expiry hints;
Redis seat-map eviction is repeatable and the display cache has a five-second TTL.
`ORDER_CREATED` / `ORDER_CANCELLED` and payment/refund events carry string
`orderId`, string `sessionId`, and the original ISO UTC `expireAt`. The wire envelope
adds the stable Outbox `eventId` and `eventType`.

## Runtime switches

Normal runtime enables all three flags:

- `seatflow.messaging.publisher-enabled=true`: publish every second.
- `seatflow.messaging.listener-enabled=true`: event and expiry listeners.
- `seatflow.messaging.expiry-enabled=true`: independent database scan every five seconds.

Each recovery job has a dedicated single-thread scheduler: `seatflowOutboxScheduler`,
`seatflowExpiryScheduler`, and the reserved `seatflowRefundScheduler` for the
refund worker. Blocking broker or gateway calls cannot occupy the expiry scan's
thread. The refund worker must explicitly select its named scheduler.

Set each to `false` for deterministic tests or diagnostic CLI runs. Test profile
sets all three to false. Broker credentials use standard Spring Boot environment
variables `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`,
`SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`.
`seatflow.messaging.namespace` defaults to `seatflow`; integration tests use UUID
namespaces. Durable direct exchange, durable queues and persistent messages are
used. Correlated confirms and mandatory returns are both enabled.

## Outbox ownership and failure

`OutboxRepository(JdbcTemplate,Clock)` and caller-transaction-only `append` are
preserved. `claim(now,lease,limit)` uses a short independent MySQL transaction with
`FOR UPDATE SKIP LOCKED` at READ COMMITTED; it increments attempts and assigns a
unique lease token. Exhausted cleanup locks individual eligible rows with SKIP
LOCKED too, avoiding a broad range-locking update. A deadlock retries the whole
short transaction at most three times; rolled-back attempts do not count.
Completion overloads are `markSent(eventId,leaseToken)` and
`markFailed(eventId,leaseToken,nextAttemptAt,reason)`. Both return false unless the
matching claim is still live. There is deliberately no completion method that
omits ownership. Publisher leases last 30 seconds. A batch claims at most two
rows: two broker-confirmed sends per created event have five-second bounded waits.

Delivery succeeds only after ACK and no mandatory return. A created event also
publishes its first expiry hint before being marked SENT. A crash or database
failure after publishing can duplicate either message. Database consumer dedup
and order mutation commit in one REQUIRED transaction; order locks precede seat
locks. Cache eviction happens after commit and may repeat on replay.

Attempts are capped at ten, with retry delays 1,2,4,8,16,32,60,60,60 seconds.
Expired tenth claims become retained FAILED rather than stranded IN_FLIGHT.
`last_error` is bounded to 1000 characters. Failed rows are retained for inspection;
no automatic reset of exhausted attempts is provided.

## Absolute expiry and broker failure

Hints use `expireAt - Clock.now`, capped at a 60-second hop, never a new fifteen-minute
period. The delay queue dead-letters into the expiry queue; early delivery schedules
another remaining-deadline hop, without writing the final expiry dedup record.
Only a due order is deduplicated and expired. The database expiry time is decisive.
Per-message TTL can wait behind an earlier queue head, bounded here by the queue's
60-second TTL. This is a hint latency bound, not a promise of exact broker timing.
The independent five-second database scan handles due UNPAID orders through the
same `OrderService.expire`, including while RabbitMQ is unavailable.

Malformed or failed listener messages reject without immediate requeue into a
retained dead queue (10000 messages, seven-day TTL). Expiry remains recoverable
from MySQL and cache staleness remains bounded by Redis TTL. Inspect dead-letter
headers and application error logs for consumer failures; retrying a corrected
message is operational work rather than an unbounded poison loop.

## Verification

`./mvnw test` runs fast tests with background work disabled.
Real MySQL/Redis component tests (set database variables from `database.md` and
`SEATFLOW_IT_REDIS_HOST/PORT`):

```
./mvnw -Pintegration -Dit.test=OutboxClaimsIT,ExpiryFallbackIT,MessageReplayIT,SchedulingIndependenceIT verify
```

Real RabbitMQ tests additionally require the four `SPRING_RABBITMQ_*` variables:

```
./mvnw -Pintegration -Dit.test=OutboxDeliveryIT verify
```

Missing broker configuration fails explicitly. These tests use real wall-clock TTL,
confirm/return and real message delivery; mutable Clock tests only prove business
expiry. This host has no RabbitMQ, Erlang or Docker; actual broker execution is
pending CI and must not be reported as locally passed.
