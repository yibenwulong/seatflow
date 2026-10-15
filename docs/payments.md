# Payment adapters

The default provider is `mock`. It records simulated external collection/refund
facts in MySQL using independent transactions. These records are deliberately
not booking transactions and survive a failed booking commit. This is a local
simulation, not a real external payment provider.

Mock notifications require an HMAC signature and a matching successful ledger
fact. A fresh in-memory signing key is generated unless
`seatflow.payment.mock-secret` is explicitly configured. The development
completion flow does not expose that signing key.

The optional Alipay sandbox adapter uses the official Java SDK. Enable it only
with all of these environment variables:

| Variable | Value |
| --- | --- |
| SEATFLOW_PAYMENT_ALIPAY_ENABLED | true |
| SEATFLOW_PAYMENT_ALIPAY_APP_ID | sandbox application ID |
| SEATFLOW_PAYMENT_ALIPAY_SELLER_ID | sandbox seller account UID |
| SEATFLOW_PAYMENT_ALIPAY_PRIVATE_KEY | sandbox application private key |
| SEATFLOW_PAYMENT_ALIPAY_PUBLIC_KEY | Alipay sandbox public key |
| SEATFLOW_PAYMENT_ALIPAY_NOTIFY_URL | publicly reachable HTTPS notification URL |

The host is fixed to the sandbox gateway. Missing required configuration rejects
startup when enabled; disabled mode needs no Alipay credentials. Do not put keys
in committed YAML or example files. The adapter checks RSA2 signatures,
application/seller identity, successful collection status, and valid transaction
fields. The booking service additionally checks the stable merchant intent and
its server-side amount.

Refund retries retain `out_request_no`. Alipay documents that retries with the
same refund request number have only one refund effect. Unit tests exercise
real RSA signatures and adapter field mapping with a mocked SDK transport.
They do not make network requests or establish that a live sandbox account
works. No live sandbox test has been run.

The sandbox refund API does not supply a separate refund transaction ID.
Its stored gateway_refund_id is a bounded, deterministic local reference to
the transaction/request pair. Provider reconciliation uses the actual payment
transaction ID and refund_no (out_request_no), not that local reference.

Primary references:

- [Official Java SDK](https://github.com/alipay/alipay-sdk-java-all)
- [Alipay refund request and retry semantics](https://aipay.alipay.com/docs/subscribe/api-list/other-basic-interfaces/alipay-trade-refund.html)
- [Alipay sandbox Java gateway example](https://aipay.alipay.com/products)

## HTTP contract

All paths start with `/api/v1`. User routes require a bearer token and derive
the user ID from the verified identity. The configured provider is
`seatflow.payment.provider` (default `mock`). Select `alipay` only after
enabling/configuring the sandbox bean.

| Method/path | Request | Response |
| --- | --- | --- |
| POST /orders/{orderId}/payments | no body | PaymentView |
| POST /dev/payments/{id}/complete | no body; id is PaymentView.id | {"status":"PROCESSED"} |
| POST /payments/{provider}/notify | gateway form fields | plain text success after verified processing |
| POST /orders/{orderId}/refunds | no body | RefundView |
| GET /orders/{orderId}/refunds | no body | RefundView array |

PaymentView fields: `id` (payment intent ID), `orderId`, `provider`,
`merchantRequestNo`, `transactionId`, `amount`, `checkoutUrl`. IDs are
strings; amount is a JSON number. Mock returns a transaction ID and null
checkoutUrl; sandbox precreate returns its QR content in checkoutUrl and has
no transaction ID until notification.

RefundView fields: `id`, `orderId`, `refundNo`, `type`, `amount`,
`status`, `attempts`, `nextAttemptAt` (UTC ISO timestamp or null),
`lastError` (null or safe failure category). POST refunds only enqueues
the operation; callers poll GET for completion. A replay returns the same
normal refund record. Other-user access returns 403.

Development completion registers only when there is at least one active
profile and every active profile is exactly local or test. Mixed profiles
(including local,staging) do not mount the route. Completing an expired or
cancelled mock payment intentionally models late successful funds and queues
compensation. Replaying the same transaction has no second financial effect.
It is not a production payment shortcut.

## Transaction and worker contract

`PaymentService.create(userId,orderId)` checks ownership, UNPAID state and the
deadline under an order lock, persists a stable per-order/provider intent in a
short transaction, then calls the gateway outside a transaction.
`confirm(VerifiedPayment)` accepts trusted adapter output only. HTTP binds form
fields to NotificationInput and verifies them first. Confirmation locks the
order, then reads Clock; only `now < expireAt` may sell HELD seats. Exact/late
callbacks expire an unscanned UNPAID order in the same transaction and enqueue
LATE_PAYMENT. Any additional distinct successful transaction after an existing
collection enqueues DUPLICATE_PAYMENT, including a second collection on a
cancelled order whose first collection was late. Identical transaction replay makes
no writes.

`RefundService.process(long refundId)` is the worker entry point. It suspends
any caller transaction; local changes use separate short transactions that
lock order, then refund record. The worker selects only PENDING, PROCESSING and
GATEWAY_SUCCEEDED IDs, null/due next_attempt_at, and null/expired lease_until, in ID order.
Do not filter attempts below 10: an expired PROCESSING lease on attempt 10
needs to become FAILED.

States:

- PENDING: queued or waiting for another gateway attempt.
- PROCESSING: claimed for 30 seconds; attempts incremented under the order lock.
- GATEWAY_SUCCEEDED: external success checkpoint persisted, local completion
  still pending. Recovery skips the gateway call.
- COMPLETED: local completion committed; next_attempt_at/lease cleared.
- FAILED: ten attempts exhausted; retained for operator investigation; new
  automated process calls stop without another gateway attempt.

Failures record a safe phase/category, clear the owned lease, and schedule
1, 2, 4, 8, 16, 32, then 60-second capped retry intervals. process rethrows
after recording a failed attempt; the worker must catch each row's exception
and continue. Ineligible, terminal, missing or actively leased IDs are no-ops.
Gateway success before checkpoint failure is recovered using the same
refund_no; it must not create a second external refund. A checkpoint is saved
before the final local completion transaction.

Verified success for the same stable refund_no is monotonic funds truth: an
already in-flight response may reconcile after its lease expires, while a newer
claim is active, or after expired attempt ten has become FAILED. Under the
order/refund locks it records success and completes locally once; another success
or terminal replay makes no second event or money effect. FAILED stops new calls,
but does not discard that previously started verified response. Stale failures
are fenced by attempt and cannot clear a newer claim. This refund policy differs
from Outbox's strict live-lease ACK policy. Latch-controlled real MySQL tests cover
old success versus a live newer claim and old success versus tenth-attempt FAILED;
the compensation case also preserves the new seat owner.

NORMAL refund requests are whole-order and must precede show start. Completion
releases only that order's SOLD seats in increasing seat ID order and sets
REFUNDED. Ownership inconsistency aborts the entire local completion.
LATE_PAYMENT and DUPLICATE_PAYMENT only reconcile funds, preserving the
order's business terminal state and any new owner's seats.

Outbox events are committed with local order/seat/funds state. Event types:
ORDER_PAID, REFUND_REQUESTED, ORDER_REFUNDED,
PAYMENT_COMPENSATION_REQUESTED, PAYMENT_COMPENSATION_COMPLETED. All have order
ID aggregate and the same payload as booking: string orderId/sessionId and ISO
expireAt. Joined expiration also emits ORDER_CANCELLED.

The refund worker owns polling/configuration; this package owns claims,
retries and funds transitions. The flag is
`seatflow.payment.refund-worker-enabled` (default true). No payment logic depends on MQ
delivery for refund retry eligibility.
