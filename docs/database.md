# Database contract

MySQL is the authoritative store. `db/migration/V1__seatflow_schema.sql` creates
the entire v1 structure; later changes belong in new versioned migrations.
The `local` profile additionally enables `db/demo/V1_1__local_demo.sql`.
Production migrations create no demonstration users or inventory. Configure a
fresh database when switching from a demo environment to a production profile.

The deterministic fixture has one fictional Shanghai theatre, two UTC sessions
(101/102, December 1/2, 2099), and the same 16 physical hall seats snapshotted
independently into both sessions. Rows A/B each contain LEFT and RIGHT blocks,
four seats per block. B2 is disabled. PREMIUM costs 280.00; STANDARD costs
180.00. Accounts `demo` (USER) and `admin` (ADMIN) have the demonstration
password `demo-seatflow`, stored with BCrypt. These credentials are local/test
examples only.

All DATETIME(6) columns contain UTC. Bind `LocalDateTime` values interpreted as
UTC, or use a connection configured with `connectionTimeZone=UTC` and
`forceConnectionTimeZoneToSession=true`. Business code must obtain time from
the injectable `Clock`. Catalog date filters use UTC calendar dates, inclusive
at midnight and exclusive at the next midnight.

`session_seat` contains the complete immutable layout and price snapshot.
`(session_id,seat_id)` is unique; `seat_id` identifies the physical hall seat.
`id` is only the session-seat row surrogate. Ownership must match the order's
session. AVAILABLE/DISABLED have no owner or hold expiry; HELD has both;
SOLD retains the owner and clears the hold expiry. Order items reference both
their order/session and their session/seat, with `(order_id,seat_id)` unique.

`ticket_order` provides unique `(user_id,request_id)` plus `request_digest` for
payload conflict detection. `payment_intent` provides a stable unique
`merchant_request_no` and one request per `(order_id,provider)`.
`payment_record` stores each actual collection, with unique
`gateway_transaction_id`, `payment_intent_id`, `order_id`, amount, processing
status, and received/processed timestamps. This permits a second distinct
collection under the original merchant request without discarding money facts.
`refund_record` has unique `refund_no` and `(payment_record_id,type)`, separate
gateway/local completion timestamps, attempts, lease, next attempt and error.

`outbox_event` has unique `event_id`, status, attempts, `next_attempt_at`,
`lease_until`, `lease_owner`, and error details. `consumed_event` deduplicates
each `(consumer_name,event_id)`. `mock_gateway_payment` and
`mock_gateway_refund` deliberately have no booking foreign keys: their external
success facts must commit with independent transactions and survive booking
rollbacks. The later gateway adapter supplies that transaction boundary.

Run fast SQL/API tests with `./mvnw test` (H2 compatibility tests). Run real
MySQL migration tests with `./mvnw -Pintegration verify`, setting
`SEATFLOW_IT_MYSQL_URL`, `SEATFLOW_IT_MYSQL_USER`, and
`SEATFLOW_IT_MYSQL_PASSWORD`. The integration user needs CREATE/DROP permission
only for schemas matching `seatflow_it_%`. Each test generates a UUID schema,
migrates it from empty, checks replay and constraints, and drops that schema
in `finally`. Missing configuration fails; integration tests never skip.
