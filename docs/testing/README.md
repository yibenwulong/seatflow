# Test entry points and evidence

| Entry | Scope | Missing dependency |
|---|---|---|
| `./mvnw test` | Java algorithms, HTTP boundary, H2 compatibility and controlled gateway tests | build fails |
| `python -m unittest discover -s tools/tests -v` | fixed-data checker behavior | no database required |
| `./mvnw -Pintegration verify` | real MySQL transactions, Redis and RabbitMQ tests | explicit failure, no skip |
| `python -m tools.mysql_smoke` | checker SQL on real disposable MySQL schema | explicit failure |
| `python tools/demo.py` | actual local/test HTTP business lifecycle | nonzero on failure or bounded timeout |

Use Java 17, Maven Wrapper 3.9.16 and Python 3.13. Install CLI dependency with
`python -m pip install -r tools/requirements.txt`, preferably in a virtualenv.

Real integration requires all of:

```text
SEATFLOW_IT_MYSQL_URL=jdbc:mysql://localhost:3306/mysql?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
SEATFLOW_IT_MYSQL_USER=your_isolated_test_account
SEATFLOW_IT_MYSQL_PASSWORD=your_local_test_password
SEATFLOW_IT_REDIS_HOST=localhost
SEATFLOW_IT_REDIS_PORT=6379
SPRING_RABBITMQ_HOST=localhost
SPRING_RABBITMQ_PORT=5672
SPRING_RABBITMQ_USERNAME=your_local_broker_user
SPRING_RABBITMQ_PASSWORD=your_local_broker_password
```

IT creates and drops UUID databases named `seatflow_it_<uuid>`. Grant the test
account CREATE/DROP and fixture privileges only on that pattern, or use the
disposable root account in CI's throwaway container. The ordinary app account
has rights only on `seatflow`. Redis and broker fixtures use isolated names.
Never point IT at valuable schemas or shared broker fixtures.

The CI `quality` workflow has three independent jobs: fast; service-container
integration; clean Compose build/start plus actual HTTP demo and CLI snapshot.
Reports and failure logs are uploaded with `if: always()`. Pipeline failure
cannot be hidden by `tee`: test steps enable `pipefail`. Compose cleanup removes
only its CI project and volumes.

## Verification ledger (delivery stage)

- Reviewed booking base: Java fast suite **49 tests**, zero failures/errors/skips.
- Checker TDD: initial **12 fixed tests**, **9 expected assertion failures**;
  implemented checker then **12 passed**. Added unknown-state/refund-overallocation
  regressions failed twice, then all **15 fixed cases** passed. Three CLI/driver
  checks brought the initial tool suite to **18 passed**. Review added four
  terminal-refund cases: **22 tests, 3 expected failures** before the fix, then
  **22 passed**. These are not real DB tests.
- Actual checker smoke: native **MySQL 8.0.44**, new UUID schema migrated and seeded,
  normal case consistent; partial/stale owner detected; pending refund visible;
  a 560.00 terminal order with only 1.00 collected/refunded rejected with CLIexit1,
  corrected full collection/refund accepted with CLIexit0;
  a separate attempted UPDATE in READ ONLY transaction rejected with MySQL **1792**.
  The SQL-induced cases are not HTTP payment/booking validation.
- Previous reviewed tasks ran real MySQL booking/auth/admin and Redis cache tests.
  Preserved booking observations appear in [experiments](experiments.md).
- Full payment HTTP demo after reviewed runtime integration: **pending**.
- RabbitMQ delivery/recovery and Docker/Compose execution: **not executed locally**;
  this workstation has neither runtime. CI workflow definition alone is not proof.
- No branch coverage percentage has been collected. Suite counts are execution
  counts, not coverage. Improve branch coverage using the concrete gaps identified
  by review and the failure matrix, rather than inventing a percentage.

Final runtime/CI verification must update this ledger with observed results.
See [engineering notes](engineering.md) and [checker limitations](consistency.md).
