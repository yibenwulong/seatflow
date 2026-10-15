# Authentication contract

`POST /api/v1/auth/login` accepts `{"username":"demo","password":"demo-seatflow"}`.
The demo USER and `admin` ADMIN exist only in local/test migrations. Production
migrations create no accounts. Password verification uses BCrypt; passwords
longer than 72 UTF-8 bytes are rejected rather than silently truncated.

A successful response contains `token`, `tokenType` (`Bearer`), `expiresAt` (UTC
ISO instant), and `user` (`id` as a JSON string, `username`, `role`). Supply
`Authorization: Bearer <token>` on subsequent requests. Incorrect credentials
return 401 `INVALID_CREDENTIALS` for both unknown and disabled accounts.

Tokens are signed HS256 JWTs with issuer `seatflow`, string `sub` and numeric
`iat`/`exp`. Default lifetime is one hour (`seatflow.auth.token-lifetime`, ISO
duration). Expiry uses injected `Clock`; a token is invalid at the exact expiry.
The verifier fixes the algorithm/header and uses constant-time signature
comparison. Account enabled state and role are reloaded for every request.
Passwords, password hashes and tokens are not logged; credential-bearing DTOs
redact their `toString()` representations.

Set `SEATFLOW_AUTH_SIGNING_KEY` to Base64-encoded random material of at least
32 bytes. Missing or invalid keys refuse startup outside local/test. Every
active profile must be local/test to permit a generated ephemeral key; mixing
production and local does not bypass this rule. An ephemeral key changes on
restart and is unsuitable for multiple instances. Key rotation invalidates
previous tokens. Never commit actual key material.

Controllers call `CurrentUser.requireUser()` or `CurrentUser.requireAdmin()`
and derive ownership from the returned ID. Request body/query/header user IDs
are untrusted. The authentication filter installs the verified context and
always removes it in `finally`, including failed requests. Context is confined
to the synchronous servlet thread; asynchronous work must receive explicitly
captured identity rather than read a worker thread's context.

All `/api/v1/**` paths default to requiring an authenticated account;
`/api/v1/admin/**` additionally requires ADMIN. Public routes are POST login,
GET catalog reads, and the exact POST `/api/v1/payments/{provider}/notify`
gateway callback shape. The future payment controller must verify gateway
signature, merchant, transaction and amount before processing that callback.
Dev payment completion remains authenticated and must also be local/test gated
by its future controller. Supplying an invalid Authorization header returns
401 even on a public route.

Failures use `code,message,requestId`, including invalid requests (400), missing
or invalid bearer identity (401), role rejection (403), missing resources (404),
unsupported methods/media types (405/415), an opaque unexpected error (500),
and database connection/resource failures or query timeouts (503), including
failures during authentication before a controller is reached.
Business `ApiException` preserves its declared status/code (including 409/503).
`X-Request-Id` is accepted only for 1–64 safe alphanumeric/dot/underscore/hyphen
characters; otherwise the server generates a UUID. The same request retains
the same ID whenever the error handler retrieves it.

Identity-only test controllers exist exclusively in test source. Order
ownership HTTP regression belongs to Task 4 against actual order endpoints;
the actual admin session-creation endpoint belongs to Task 3. Neither is
claimed as implemented or exercised by identity probes.
