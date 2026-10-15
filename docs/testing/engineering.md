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
| Expired tenth refund attempt stayed PROCESSING | A crashed final lease was excluded from further processing instead of becoming terminal. Preserved failing test expected FAILED. | Include expired attempt ten in recovery selection and retain FAILED without another gateway effect. |
| Stale refund failure cleared a newer lease | A blocked older attempt failed after a newer worker claimed the task. Preserved failing test showed the current lease was lost. | Fence failure updates by the claimed attempt; safe LOCAL_COMPLETION diagnostic remains queryable. |
| Second collection on a cancelled order was misclassified | First collection was late; a distinct second collection yielded two LATE_PAYMENT tasks instead of LATE_PAYMENT then DUPLICATE_PAYMENT. | Current receipt read under order lock preserves both money facts and their distinct compensation types. |
| Concurrent Outbox dispatch deadlocked | Real MySQL race errored in a broad exhausted-claim UPDATE. The isolated two-dispatcher test reproduced it. | Select exhausted rows individually with SKIP LOCKED at READ COMMITTED and bounded whole-transaction retry; ten two-thread rounds then passed. |
| Blocked publisher starved scheduled expiry | Actual Spring schedules shared a worker thread; after eight seconds a due order remained UNPAID. | Dedicated publisher, expiry and refund schedulers; real scheduled expiry succeeds while both network-work lanes are blocked. |

These are observed development findings, not production incidents or allegations
about the former airline project. The first three were reproduced as failing
tests before the fixes. The booking cutoff finding came from independent review
and then four failing real MySQL cases. Outbox/receipt rollback failures injected
for recovery testing must be described as induced faults, separately from these
discovered defects.

Alipay's five final adapter tests verify RSA validation and unsuccessful-response
rejection using offline SDK transport. The preserved `alipay-red.log` contains
missing-class compilation failure only; it is not evidence of a historical
semantic SDK-isSuccess assertion failure. No live sandbox request was executed.
Final review added two latch-controlled real MySQL regressions for authoritative
same-refund-number success from an old in-flight worker versus a newer live claim
and expired tenth-attempt FAILED. Both assert one external money fact, one local
completion/event and terminal replay; late-payment compensation also preserves
the new owner's HELD seat. This documents the accepted monotonic funds-truth
policy; it does not claim to exhaust every possible interleaving.

Delivery review found a separate diagnostic defect: a REFUNDED 560.00 order with
only 1.00 collected and NORMAL-refunded was wrongly certified consistent. Three
new fixed-case failures preceded the fix; the checker now requires the full
order amount and coherent linked receipt/refund progress. Real isolated MySQL
smoke also rejects the 1.00 case and accepts the restored 560.00 case. CI teardown
failure paths were checked with the actual Bash script and fake Docker commands;
that is shell-control evidence, not container execution.

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

Final review also reproduced unsupported refund status CORRUPT being certified
consistent. A fixed-case assertion failed before the whitelist fix; the CLI now
returns 1 with UNKNOWN_REFUND_STATE and retains valid FAILED visibility. The
price boundary review reproduced a legacy two-seat DECIMAL aggregate overflow;
admin input now caps each new price at 2499999999.99 and a transactional total
guard turns incompatible legacy selections into a clear 400 without partial
writes. Real MySQL tests store four maximum-price seats totaling 9999999999.96
and assert complete rollback for the legacy overflow.

Negative-startup auth/Alipay tests assert their precise wrapped validation cause.
Their scoped capture consumes only the expected context WARN and forwards other
diagnostics, restoring logger configuration in finally. Dependency advisories,
including Flyway's MySQL version warning, are not globally muted.
