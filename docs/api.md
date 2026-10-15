# HTTP API

Base `/api/v1`. JSON IDs are strings, money is a JSON decimal, timestamps are UTC
ISO instants. Authenticated calls use `Authorization: Bearer <token>`. Errors have
`code`, `message`, `requestId`; 400 invalid input, 401 missing/invalid identity,
403 ownership/role, 404 missing resource, 409 state or idempotency conflict,
503 unavailable dependency. `X-Request-Id` accepts safe characters only.

| Method/path | Identity | Request / result |
|---|---|---|
| POST `/auth/login` | public | `{username,password}` → `{token,tokenType,expiresAt,user}` |
| GET `/shows?city=Shanghai&date=2099-12-01` | public | show array; UTC calendar date filter |
| GET `/shows/{id}/sessions` | public | session array |
| GET `/sessions/{id}` | public | session detail |
| GET `/sessions/{id}/seats` | public | `{sessionId,layoutVersion,seats,asOf}`; omits private owners |
| POST `/admin/sessions` | ADMIN | show/hall/time/zone prices → created snapshot, HTTP 201 |
| POST `/orders` | USER/ADMIN | selection below → order snapshot |
| GET `/orders` | owner | own order array |
| GET `/orders/{id}` | owner | order and item snapshots |
| POST `/orders/{id}/cancel` | owner | no body; UNPAID→CANCELLED, replay safe |
| POST `/orders/{id}/payments` | owner | no body → payment intent view |
| POST `/dev/payments/{id}/complete` | owner, local/test | payment **intent** ID; no body → `{status:"PROCESSED"}` |
| POST `/orders/{id}/refunds` | owner | no body → refund view, initially PENDING |
| GET `/orders/{id}/refunds` | owner | refund views; poll COMPLETED/FAILED with a bound |
| POST `/payments/{provider}/notify` | verified gateway | form notification; adapter verifies origin and fields |

```json
{"requestId":"example-request-1","sessionId":"101","mode":"ADJACENT","count":2}
```

```json
{"requestId":"example-request-2","sessionId":"101","mode":"EXPLICIT","seatIds":["1","9"]}
```

EXPLICIT accepts 1–4 unique physical seat IDs and rejects `count`/`priceZone`.
ADJACENT accepts count 2–4 and optional `priceZone`, with no explicit seats. The
same request key and normalized payload returns the original current order;
different payload returns 409. Client amount and userId never choose server price
or owner. Session-seat row IDs must not be used as physical seat IDs.

Order fields: `id,sessionId,requestId,mode,status,totalAmount,expireAt,createdAt,items`.
Items contain `seatId,rowNo,seatNo,priceZone,unitPrice`. Payment fields:
`id,orderId,provider,merchantRequestNo,transactionId,amount,checkoutUrl`.
Refund fields: `id,orderId,refundNo,type,amount,status,attempts,nextAttemptAt,lastError`.
Default provider is `mock`; its checkoutUrl is null. Ordinary refunds are full
order refunds before show start. Successful request submission does not imply
gateway or local completion; query the persisted progress.

See [authentication](authentication.md), [booking](booking.md), and the payment
contract delivered with the runtime for detailed verification/adapter behavior.
