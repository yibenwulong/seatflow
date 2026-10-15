# Test-development notes and real development findings

The useful interview narrative is a falsifiable invariant, a controlled
interleaving, the durable evidence and the remaining limit. A test count alone
does not establish correctness or production capacity.

| Finding during SeatFlow development | Reproduction and cause | Regression and effect |
|---|---|---|
| UTC midnight filter omitted a session | JVM Asia/Shanghai, UTC calendar boundary; implicit JDBC Timestamp conversion shifted the bound. Expected 2 shows, observed 1. | Explicit UTC LocalDateTime bounds; real MySQL and fast catalog regression preserve the calendar meaning. |
| Alternate URL spelling bypassed ADMIN check | Matrix parameter URL reached the controller while a literal request-path rule missed the admin shape. Expected 403, observed 200. | Authorize matched MVC route; actual admin controller also calls requireAdmin. |
| Corrupt Redis JSON `null` caused NPE | Existing value parsed successfully to Java null; deserialization success was incorrectly assumed to mean a usable map. | Null/wrong-session/malformed payload becomes a cache miss and real MySQL supplies the seat map. |
| Idempotent replay lost precedence at sales cutoff | Loser missed the key, winner committed, then sales closed. Original REPEATABLE READ lookup was stale. | A fresh independent lookup after rejected attempt finds the canonical order; deterministic latch/Clock regressions include an older outer snapshot. |

These are observed development findings, not production incidents or allegations
about the former airline project. The first three were reproduced as failing
tests before the fixes. The booking cutoff finding came from independent review
and then four failing real MySQL cases. Outbox/receipt rollback failures injected
for recovery testing must be described as induced faults, separately from these
discovered defects.

For transaction tests, assert the whole committed state: header count, item count,
owner/state, amount and event count. To prove partial rollback, force a later
statement to fail after an earlier one succeeded, then query from a separate
transaction. A thrown exception alone cannot prove the earlier writes rolled
back. MySQL CHECK constraints in disposable fixtures avoid requiring SUPER for
triggers. Do not add failure switches to public production controllers.

For concurrent tests, latch/Phaser controls who reaches each boundary; bounded
futures expose deadlocks. Keep a fixed clock for business expiry and a separate
real-time bound for test completion. A receipt success fact in the same rollback
transaction would erase the external event and make recovery tests falsely pass.
Gateway fixtures therefore persist independently from the booking transaction.

For caching, test actual TTL, corrupt payload and connection outage against a
real Redis instance; an in-memory mock does not establish protocol or expiry.
For MQ, a mocked confirm callback cannot establish broker routing. Use actual
RabbitMQ and verify returned messages, confirm ACK, duplicate event idempotency,
lease expiry and database fallback separately.

The current test strategy emphasizes state-machine branches: deadline before/
exactly/after, terminal replay, wrong owner, duplicate key with same/different
payload, each collection needing application or compensation, and refund
gateway/local progress. No measured coverage percentage is available. Candidate
improvements should follow uncovered decisions and mutation failures, with
coverage reports added only when actually generated.
