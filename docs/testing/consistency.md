# Read-only order consistency checker

`python tools/consistency.py ORDER_ID` inspects **only the configured current
deployment**. It does not infer historical ownership or query another database.
Set all six fields explicitly; no fallback production credentials exist:

```text
SEATFLOW_CHECK_MYSQL_HOST
SEATFLOW_CHECK_MYSQL_PORT
SEATFLOW_CHECK_MYSQL_DATABASE
SEATFLOW_CHECK_MYSQL_USER
SEATFLOW_CHECK_MYSQL_PASSWORD
```

Prefer a dedicated account with SELECT rights on this deployment's database.
Do not pass credentials as CLI arguments. The tool never logs password or driver
exception text. Output includes order/business/payment IDs and should be handled
as operational data. No user password, hash or bearer-token table is selected.

The driver pins PyMySQL 1.1.2 with RSA authentication support. The tool sets
REPEATABLE READ and starts `WITH CONSISTENT SNAPSHOT, READ ONLY` before selecting
order, items, intents, collections, refunds and current session-seat ownership.
It includes every item seat, even if reused by a newer order, plus unexpected
seats still owned by this order. A finally rollback closes the snapshot; it takes
no business row locks and performs no repair.

The checks compare item count/price total/session, every active order's expected
HELD/SOLD ownership, HELD expiry, extra owned seats and terminal ownership.
For CANCELLED/REFUNDED, a different current owner is valid. Money checks require
an applied collection for PAID/REFUNDING, a NORMAL task for REFUNDING, completed
normal refund for REFUNDED, matching receipt/refund amounts, and a durable
compensation for COMPENSATING collections. PENDING/PROCESSING/GATEWAY_SUCCEEDED
refunds are visible progress; FAILED IDs are explicitly listed and need inspection.

JSON contains `consistent`, stable `violations`, `funds` and raw `snapshot`.
Decimal amounts are strings to preserve exactness; MySQL DATETIME is emitted as
UTC ISO time. Exit 0 means the inspected snapshot meets these checks, 1 means
inconsistency/not found, 2 means configuration or database read failed. A pending
refund can be consistent while still needing later completion.

This is a focused invariant checker, not a bank reconciliation system. It cannot
prove a gateway's external settlement or that future retries will succeed. It
does not validate broker delivery or Redis freshness. Failed workers require
their own persisted diagnostics and bounded recovery tests.

`python -m tools.mysql_smoke` is the explicit real-driver test. It requires
`SEATFLOW_IT_MYSQL_*`, creates only its UUID schema, applies the known V1 schema
and local seeds, induces four cases, then drops that schema in finally. It runs
without Spring workers. It is intentionally outside fast unittest discovery and
is not silently skipped when prerequisites are absent.
