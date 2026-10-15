# Reproducible concurrency and recovery experiments

These are correctness experiments with barriers and database assertions, not a
load benchmark. Use `./mvnw -Pintegration verify` with the real dependency settings
in [testing README](README.md). No arbitrary sleep establishes ordering. Each
fixture owns a UUID schema and drops it. Fixed clocks control business deadlines;
broker timing, when exercised, must use a real broker.

## 1. One-seat contention and request idempotency

Entry: `SeatCompetitionIT`. A latch releases 32 independent users against the
same physical seat. Only a full database hold transaction can win. Recorded real
MySQL result: **32 requests, 1 success, 31 conflicts, 1 order, 1 item, 1 HELD seat,
1 Outbox event**. Concurrent same-user/key/payload replay produced **32 successful
responses but only 1 order, 2 items, 2 HELD seats and 1 Outbox event**. Same key with
different payload produced **1 success and 1 conflict** from 2 requests, leaving
one order/item/HELD seat/Outbox event.

These counts were emitted by actual assertions in the booking run recorded on
2026-10-15 at 13:01:41 +08:00. [Raw observations](booking-observations.json) are
preserved separately from later reruns. The run used native MySQL 8.0.44.

## 2. Overlapping 2/3/4-seat allocation

Entry: `SeatCompetitionIT`, supported by `AllocationTransactionsIT`. Release 24
overlapping requests together, then inspect all durable headers/items/owners.
Observed **4 successes, 20 conflicts, 4 orders, 10 items, 10 HELD seats and 4
Outbox events**. Every winner is complete, distinct, same row/block/zone and
continuous in physical position. Losers have zero durable partial occupancy.
Separate tests reject crossing an aisle or row and accept dispersed EXPLICIT
selection. No throughput, latency or fairness guarantee was measured.

## 3. Cancel replay, payment interleavings and refund recovery

Booking portion entry: `ReleaseReplayIT`. A controlled Clock proves no release
before expiry, one release at the exact deadline, then a new order reuses both
seats. Three old expiry/cancel replays preserve the new owner. Exactly three
Outbox events remain: old create, old cancel and new create. This booking result
was actually run against native MySQL.

The reviewed payment runtime was verified with `PaymentStateIT` (7 real MySQL
cases) and `RefundRecoveryIT` (8). These use independently persisted Mock gateway
facts, not a live external payment provider. Reproduce their affected subset with:

```bash
./mvnw -Pintegration -Dit.test=PaymentStateIT,RefundRecoveryIT verify
```

`cancelAndPaymentBothControlledWinnersSerializeOnOrderLock` holds the order lock
behind latches and runs each controlled winner. Payment winning leaves PAID and
zero refund tasks; cancellation winning leaves CANCELLED and one compensation
task for the collected funds. The before/exact/after deadline test produces PAID
with no refund before expiry, CANCELLED plus one LATE_PAYMENT refund at/after
expiry, even before scanning. A second distinct collection on a paid order gives
two receipts and one DUPLICATE_PAYMENT refund; replay does not add another.

`normalRefundGatewaySuccessSurvivesLocalFailureAndRetryReleasesExactlyOnce`
induces an Outbox failure during local completion after the independent gateway
ledger has recorded success. Observed first state: one external refund fact,
REFUNDING order, SOLD seat, persisted gateway checkpoint and LOCAL_COMPLETION
error. Retry with the same refund number, then replay, leaves exactly one gateway
fact, REFUNDED/COMPLETED and exactly one ORDER_REFUNDED event. Separate late and
post-normal-refund duplicate tests preserve a newer owner's HELD seat.

The final payment subset passed 18 IT with 55 fast tests; a previous broad run
passed 48 IT before one classification case was added. These are separate runs,
not a claimed single final 49-IT run. See [execution ledger](README.md).

Broker-free `OutboxClaimsIT` injects a publisher-success/database-mark failure and
observes the same stable event being published again; `MessageReplayIT` proves
consumer dedup and cancellation roll back together, later commit once, and old
replays preserve new owners. This uses real MySQL/Redis with a controlled
transport, so it does **not** establish an actual RabbitMQ ACK.

`SchedulingIndependenceIT` registers actual production schedules, blocks the
publisher transport and refund scheduler, and still observes five-second DB
expiry cancellation. `RefundWorkerIT` proves actual scheduled refund completion,
expired tenth-lease selection, and continuation to another row after a persisted
failure. Its recovery case leaves gateway-success/retry for the failed row while
another row completes; at due time the same-number retry completes once. The
final combined worker/affected regression passed 59 fast + 21 real MySQL/Redis
cases. Reproduction of this broker-free affected subset:

```bash
./mvnw -Pintegration -Dit.test=RefundWorkerIT,RefundRecoveryIT,OutboxClaimsIT,ExpiryFallbackIT,MessageReplayIT,SchedulingIndependenceIT verify
```

The native CLI demo then independently completed actual TCP API payment/refund:
two PREMIUM seats at 280.00 each, total 560.00, PAID then REFUNDED, one completed
full refund and both seats AVAILABLE. [Observation](native-demo-observation.json).
No failures were injected into that normal lifecycle demonstration.

Controlled failure injection must stay in test fixtures: independent gateway
success facts survive rollback, then retry reuses the same refund number and
does not release a newer owner's seat. Real RabbitMQ confirm/return, wall-clock
TTL, listener delivery and poison dead-lettering remain **unexecuted locally**.
OutboxDeliveryIT contains those three broker cases; CI must actually run them.
No production fault route is exposed for these experiments.
