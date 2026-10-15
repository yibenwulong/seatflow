# Booking contract

`POST /api/v1/orders` accepts `requestId`, `sessionId`, `mode`, and either
`seatIds` (EXPLICIT, 1–4 distinct physical seat IDs) or `count` (ADJACENT, 2–4)
with optional `priceZone`. EXPLICIT also rejects `count` and `priceZone`.
An absent/empty seat list is accepted for ADJACENT. Prices come from the
session snapshot. A physical `seatId` differs from `session_seat.id`.

`GET /api/v1/orders`, `GET /api/v1/orders/{id}` and
`POST /api/v1/orders/{id}/cancel` use the verified bearer identity.
Body/query user IDs cannot select another account. Other-user read/cancel
returns 403; lists contain only the current user's orders. IDs serialize as
JSON strings; timestamps are UTC ISO instants; amounts are BigDecimal.

The `(userId,requestId)` key is inserted before inspecting inventory, so
concurrent replay also works at MySQL READ COMMITTED. The SHA-256 payload
digest includes session, mode, normalized sorted explicit seat IDs, or
adjacent count and zone. Explicit seat ordering is semantically irrelevant.
The same key and payload returns the current original order, including its
terminal state. Different payload returns 409 `IDEMPOTENCY_CONFLICT`.
If the sales window closes after an initial key miss, a rejected attempt is
rolled back and the key is checked in a fresh REQUIRES_NEW transaction before
returning the sales rejection. This also escapes an outer caller's stale
REPEATABLE_READ snapshot and preserves replay precedence at the exact deadline.

Each create attempt uses REQUIRES_NEW. The new header, final server amount,
all items, all AVAILABLE-to-HELD conditional updates, and ORDER_CREATED
Outbox append commit together. An unsuccessful attempt leaves none of them.
Automatic selection attempts at most three different candidate combinations,
reloading MySQL each time. EXPLICIT attempts once and does not substitute.
Seats are updated individually in increasing physical seat ID order. Each
conditional update must affect exactly one row; a miss rolls back the attempt.
Sales start is inclusive; sales end and show start are exclusive. Expiry is
the earlier of creation plus 15 minutes and show start, using injected Clock.

## Shared transaction APIs

All types are under `io.github.yibenwulong.seatflow`:

- `booking.OrderService.create(long userId, CreateOrderRequest request)` returns
  `OrderView`; `cancel(long userId,long orderId)` returns the current `OrderView`.
- `booking.OrderService.expire(long orderId,Instant now)` returns true only for
  a newly cancelled, due UNPAID order. Missing, terminal, or early calls return
  false. The exact deadline is due. This method uses **REQUIRED** and joins an
  existing payment/consumer transaction, including one already holding the
  same order lock. `cancel` also uses REQUIRED. Neither opens REQUIRES_NEW.
- `booking.OrderRepository.lockOrder(long orderId)` requires an active caller
  transaction and returns `Optional<LockedOrder>` after `SELECT ... FOR UPDATE`.
  The caller must hold this lock before any order state or seat mutation.
- `booking.LockedOrder` is a public immutable record with accessors `id`,
  `userId`, `sessionId`, `requestId`, `requestDigest`, `selectionMode`, `status`,
  `totalAmount`, `expireAt`, `createdAt`, and `updatedAt`. A snapshot does not
  change after a mutation; call `findOrder` or `lockOrder` again to refresh it.
- Public `OrderRepository.items(long)` returns immutable `OrderItemView`
  records in increasing physical seat ID order. Fields are `seatId`, `rowNo`,
  `seatNo`, `priceZone`, and `unitPrice`. Public `findOrder(long)` returns an
  optional immutable row without locking. Repository access does not authorize
  users: package services must apply ownership rules to trusted identities.
- `messaging.OutboxRepository.append(String type,long aggregateId,String payload)`
  requires the caller's active business transaction and uses its JdbcTemplate
  connection. It never commits independently. Failure rolls back the business
  transaction. The injected Clock supplies UTC creation and next-attempt time.

Booking emits `ORDER_CREATED` and `ORDER_CANCELLED`, with aggregate ID equal to
the order ID. Both payloads contain string `orderId`, string `sessionId`, and
ISO `expireAt`; consumers can invalidate the session seat-map cache. Event IDs
are UUIDs. Expiry and explicit cancellation share ORDER_CANCELLED.

Cancellation locks the order first, releases its items in stable seat ID
order with both `owner_order_id=order.id` and `status='HELD'`, checks the full
expected release count, changes UNPAID to CANCELLED, and appends Outbox in the
same transaction. Already CANCELLED calls make no writes. Paid orders reject
explicit cancellation with 409. Old expiry/cancel replays never release seats
reused by another order. An ownership inconsistency on an UNPAID order fails
the whole transaction instead of concealing partial release.

## Verification

Fast tests: `./mvnw test`. Real MySQL booking tests:
`./mvnw -Pintegration '-Dit.test=io.github.yibenwulong.seatflow.booking.*IT' verify`.
Set the three `SEATFLOW_IT_MYSQL_*` variables described in database.md.
Missing configuration fails rather than skipping. Every fixture uses its own
UUID schema and drops only that schema. The authorization test sends actual
TCP HTTP requests through embedded Tomcat on a random port and real MySQL.
Contention uses latches and bounded futures, without arbitrary sleeps.
The booking suite does not claim MQ delivery or Redis invalidation delivery.
