# Running and configuration

The four-service Compose file is a local demonstration. MySQL gets a dedicated
`seatflow` database/user with rights limited to that database. Integration tests
need separate CREATE/DROP rights on `seatflow_it_%`; never reuse a production
database for tests. MySQL alone persists a named volume. RabbitMQ outage is
recovered through database Outbox and expiry scanning; this is not a production
durability/failover configuration.

For native Java startup, set these variables for your own local services:

| Variable | Purpose |
|---|---|
| `SEATFLOW_MYSQL_URL` | JDBC MySQL URL, database `seatflow`, UTC connection/session options |
| `SEATFLOW_MYSQL_USER` / `SEATFLOW_MYSQL_PASSWORD` | dedicated application database account |
| `SEATFLOW_REDIS_HOST` / `SEATFLOW_REDIS_PORT` | Redis endpoint |
| `SPRING_RABBITMQ_HOST` / `SPRING_RABBITMQ_PORT` | RabbitMQ endpoint |
| `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD` | dedicated broker account |
| `SEATFLOW_AUTH_SIGNING_KEY` | Base64 random bytes, at least 32 bytes outside local/test |

JDBC example: `jdbc:mysql://localhost:3306/seatflow?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`.
For an isolated local MySQL 8 service without TLS, the explicitly local Compose
URL also enables public-key retrieval. Configure TLS and credentials explicitly
for any shared deployment. `.env` is ignored by Git and used by Compose; native
Java does not automatically read it.

```bash
./mvnw -B -ntp package
java -jar target/seatflow-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
python tools/demo.py --base-url http://localhost:8080
```

The demo needs `seatflow.payment.refund-worker-enabled=true` to reach REFUNDED.
Normal Compose explicitly enables publisher, listener, expiry and refund workers.
Tests that deliberately isolate a service can disable:

```text
seatflow.messaging.publisher-enabled=false
seatflow.messaging.listener-enabled=false
seatflow.messaging.expiry-enabled=false
seatflow.payment.refund-worker-enabled=false
```

The read-only checker does not start Spring or any worker. There is no public
diagnostic/fault administration API. Failure injection lives in tests only.

The native TCP demonstration was actually executed on 2026-10-15 with a fresh
UUID MySQL database, local profile, native Redis and a private application port.
For the same broker-free scope, configure your isolated MySQL/Redis variables,
then run:

```bash
java -jar target/seatflow-0.1.0-SNAPSHOT.jar --spring.profiles.active=local --server.address=127.0.0.1 --server.port=18080 --seatflow.messaging.publisher-enabled=false --seatflow.messaging.listener-enabled=false --seatflow.messaging.expiry-enabled=true --seatflow.payment.refund-worker-enabled=true
python tools/demo.py --base-url http://127.0.0.1:18080
```

Point the checker's five database fields at that same deployment and run
`python tools/consistency.py ORDER_ID` using the ID printed by the demo. The
observed run reached REFUNDED and a consistent 560.00 collection/refund snapshot;
both selected seats were AVAILABLE with no owner. Publisher/listener were off,
so this is not RabbitMQ delivery evidence. Outbox remains durable and pending
until publication is enabled. Production-style Compose enables all four workers.

## Image provenance

Official image metadata was inspected on 2026-10-15 for these exact tags:

| Use | Tag | Official metadata |
|---|---|---|
| MySQL | `mysql:8.4.11` | [mysql](https://github.com/docker-library/official-images/blob/master/library/mysql) |
| Redis | `redis:7.4.11-alpine` | [redis](https://github.com/docker-library/official-images/blob/master/library/redis) |
| RabbitMQ | `rabbitmq:4.3.6-management-alpine` | [rabbitmq](https://github.com/docker-library/official-images/blob/master/library/rabbitmq) |
| JDK builder | `eclipse-temurin:17.0.20.1_1-jdk-noble` | [eclipse-temurin](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin) |
| JRE runtime | `eclipse-temurin:17.0.20.1_1-jre-noble` | same official metadata |

The multi-stage build uses the project's Maven 3.9.16 Wrapper; it does not depend
on an assumed Maven image tag. Exact tags reduce unintended changes but are not
immutable digests. Initial hosted [CI 38692223197](https://github.com/yibenwulong/seatflow/actions/runs/38692223197)
at `db1d72683ab51f23d626792ebda805c143cfcb5e` pulled and ran these images, passed
all three jobs, and completed clean Compose startup, actual HTTP demo and checker
(560.00 collected/refunded, two AVAILABLE/unowned seats, no pending/failed refunds).
They were not run on this Docker-less Windows workstation. Native local evidence
uses MySQL **8.0.44** and Redis **5.0.14.1**. See
[quality workflow results](https://github.com/yibenwulong/seatflow/actions/workflows/ci.yml)
for each commit's full hosted verification.

Flyway 11.7.2's upstream MySQL tested-version advisory threshold is 8.1; its
warning on 8.4 remains visible despite the current migrations and initial 65 IT /
Compose pass on 8.4.11. See [database contract](database.md). Redis also reported
a CI host memory-overcommit advisory; no host settings were changed and this
learning-project run makes no production infrastructure validation claim.
