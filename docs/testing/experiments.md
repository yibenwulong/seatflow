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

Payment/cancel race, duplicate/late collection, external refund success followed
by local commit failure, and publisher ACK followed by local status failure are
required extensions. Their reviewed runtime test names and observed results
are **pending** Task 5/6 integration evidence. Do not treat this paragraph or
the CI job definition as a successful recovery run.

Controlled failure injection must stay in test fixtures: independent gateway
success facts survive rollback, then retry reuses the same refund number and
does not release a newer owner's seat. Broker recovery must distinguish ACK,
unroutable return, duplicate delivery and expired publisher leases. No production
fault route is exposed for these experiments.
