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

## Historical local verification ledger

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
- Merged runtime `./mvnw.cmd -B -ntp package`: **59 fast tests**, zero
  failures/errors/skips, BUILD SUCCESS. This command does not execute IT.
- Actual native TCP demo after reviewed runtime integration: login, catalog/map,
  two adjacent seats, payment creation/Mock completion, PAID query, refund request,
  scheduled worker completion and REFUNDED query all succeeded. The subsequent
  real CLI snapshot had no violations, two AVAILABLE/unowned seats, one REFUNDED
  collection of **560.00**, one COMPLETED NORMAL refund of **560.00**, and no
  pending/failed refunds. [Recorded observation](native-demo-observation.json).
  It used a fresh UUID schema on native MySQL8.0.44 and Redis5.0.14.1; the owned
  application process was stopped and only that schema dropped afterward.
  Publisher/listener were false, expiry/refund worker true; it establishes
  the native HTTP/automatic-refund lifecycle without broker delivery.
- RabbitMQ delivery/recovery and Docker/Compose execution: **not executed locally**;
  this workstation has neither runtime. CI workflow definition alone is not proof.
- No branch coverage percentage has been collected. Suite counts are execution
  counts, not coverage. Improve branch coverage using the concrete gaps identified
  by review and the failure matrix, rather than inventing a percentage.

Reviewed runtime recovery evidence was collected in these separate executions:

| Execution | Observed result | Scope |
|---|---|---|
| Payment broad regression before final classification delta | 55 fast + 48 IT, all zero failures/errors/skips | real MySQL/Redis, includes existing booking/auth/cache |
| Final payment classification delta | 55 fast + 18 payment IT, all zero | 7 PaymentStateIT, 8 RefundRecoveryIT, 2 MockGatewayIT, 1 actual TCP PaymentAuthorizationIT |
| Final worker/messaging regression | 59 fast + 21 affected IT, all zero | 3 RefundWorkerIT, 8 RefundRecoveryIT, 10 broker-free messaging/expiry/scheduler cases |

After the one extra payment classification case there are 49 distinct IT in that
Task5 inventory: 31 unchanged cases passed in the broad run and 18 affected cases
passed in the final scoped run. **There was no single final 49-IT full run.** Do
not add overlapping suite counts as if they were distinct cases. The worker run
adds actual scheduled polling/continuation evidence while using simulated gateway
facts persisted in real MySQL. Five fast Alipay adapter tests use real RSA
signatures and offline SDK transport; they do not verify a live sandbox.

Actual broker entry was attempted locally before the final worker stage:
OutboxDeliveryIT reported three explicit missing-SPRING_RABBITMQ_HOST errors and
zero skips. No broker operation ran in that historical local attempt. The
subsequent hosted run below supplies the real broker and container evidence.
See [engineering notes](engineering.md) and [checker limitations](consistency.md).

## Initial hosted evidence and final review delta

Initial hosted [quality run 38692223197](https://github.com/yibenwulong/seatflow/actions/runs/38692223197)
at exact historical SHA `db1d72683ab51f23d626792ebda805c143cfcb5e` had all three
jobs SUCCESS: fast, integration and Compose-demo. It ran **59 fast Java tests**,
**65 IT** (including **3 real RabbitMQ OutboxDeliveryIT cases**) and **22 Python
tests**, all without failures/errors/skips. The integration job repeats fast
tests; do not add those overlapping counts. Actual service versions were MySQL
**8.4.11**, Redis **7.4.11**, RabbitMQ **4.3.6**. Clean Compose built/started the
application and actual HTTP demo reached PAID -> COMPLETED refund -> REFUNDED.
The CLI snapshot had no violations, **560.00** collected and refunded, two
AVAILABLE/unowned seats, and no pending/failed refunds. The downloaded job logs
and demo/checker JSON were inspected during delivery review. Earlier native-only
observations above retain their historical scope.

The final review delta was verified locally with a fresh **61-fast-test** run,
**25 Python tests**, and **7 affected real MySQL IT**: two stale-success races,
two price/aggregate boundary cases and three SessionAdminIT cases. The scoped
integration command also reran the same 61 fast cases; it is not another distinct
inventory. MySQL here was native 8.0.44; no RabbitMQ/Docker ran locally. These
results document the affected subset. Full hosted results are reported per
commit in the [quality workflow results](https://github.com/yibenwulong/seatflow/actions/workflows/ci.yml);
a result at an earlier SHA is not evidence for another commit.

Known compatibility advisory: Spring Boot 3.5.13 manages Flyway **11.7.2**, whose
[MySQL version check](https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/MySQLDatabase.java#L243)
uses **8.1** as its tested-version advisory threshold. The MySQL 8.4 warning stays
visible. Current migrations, all initial 65 IT and clean Compose passed on
8.4.11; this does not guarantee every future migration. Any future Flyway upgrade
must check the effective dependency version and pass fresh full CI. No managed
version override or SQL migration change was made to hide the advisory. Redis's
host memory-overcommit advisory in CI remains an infrastructure observation;
no host setting was changed and production tuning was not validated.
