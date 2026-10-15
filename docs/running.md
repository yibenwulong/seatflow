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
immutable digests. These tags were found in upstream metadata; they have not yet
been pulled or run on this Docker-less workstation. CI must establish actual
container startup. Native local evidence uses MySQL **8.0.44** and Redis
**5.0.14.1**, which must not be conflated with these CI images.
