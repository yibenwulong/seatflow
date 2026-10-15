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

Primary references:

- [Official Java SDK](https://github.com/alipay/alipay-sdk-java-all)
- [Alipay refund request and retry semantics](https://aipay.alipay.com/docs/subscribe/api-list/other-basic-interfaces/alipay-trade-refund.html)
- [Alipay sandbox Java gateway example](https://aipay.alipay.com/products)
