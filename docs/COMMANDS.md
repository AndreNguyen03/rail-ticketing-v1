# Commands

No task runner (ADR-0006). CI runs exactly these commands.

## Infrastructure

    docker compose up -d postgres          # start
    docker compose down                    # stop, keep data
    docker compose down -v                 # stop and WIPE data
                                           #   required after editing infra/postgres/init/*.sql,
                                           #   those scripts only run on an empty volume
    docker compose exec postgres psql -U postgres -c "\l"

Port 5432 already taken? Create `.env` with `POSTGRES_PORT=5433`.
This changes the host mapping only — services always reach `postgres:5432`
over the compose network.

## Databases

| database    | user           | password     | owner |
|-------------|----------------|--------------|-------|
| scheduledb  | schedule_user  | schedule_pw  | yes   |
| inventorydb | inventory_user | inventory_pw | yes   |
| bookingdb   | booking_user   | booking_pw   | yes   |

Cross-database access is denied by privilege, not convention (ADR-0002):

    docker compose exec postgres psql -U booking_user -d bookingdb   -c "select 1"  # OK
    docker compose exec postgres psql -U booking_user -d inventorydb -c "select 1"  # FATAL

## Contract

    docker run --rm -v "${PWD}:/spec" -w /spec redocly/cli lint contracts/openapi.yaml

`-w /spec` matters: without it redocly ignores `redocly.yaml` and falls back to
its own `recommended` ruleset, which changes between image versions.

Human-readable view on http://localhost:8090:

    docker run --rm -p 8090:80 -e SWAGGER_JSON=/spec/contracts/openapi.yaml       -v "${PWD}:/spec" swaggerapi/swagger-ui

## Services

| service           | port | database    | plane      |
|-------------------|------|-------------|------------|
| api-gateway       | 8080 | —           | queue      |
| schedule-service  | 8081 | scheduledb  | catalog    |
| inventory-service | 8082 | inventorydb | contention |
| booking-service   | 8083 | bookingdb   | contention |
| payment-service   | 8084 | —           | fulfillment |

Infra only, services run from the IDE (or `java -jar` after `./mvnw package`) —
the normal development loop, avoids rebuilding a Docker image per code change:

    docker compose --profile services up -d postgres redis kafka

Every service's `application.yml` defaults to `localhost` for Postgres/Redis. Kafka is
the exception: its `advertised.listeners` is `kafka:9092` (container-DNS only, unreachable
from the host), so it also publishes an `EXTERNAL` listener on host port `29092` — a
host-run booking-service/payment-service needs `KAFKA_BOOTSTRAP_SERVERS=localhost:29092`
(the in-container default `kafka:9092` stays unchanged for `--profile services` runs).

Everything in containers:

    docker compose --profile services up -d --build
    docker compose --profile services logs -f inventory-service
    docker compose --profile services down

Build one service's image by hand (note the context is the repository root,
the build needs the parent pom):

    docker build -f services/inventory-service/Dockerfile -t inventory-service .

## Build

    ./mvnw validate
    ./mvnw -T1C package -DskipTests
    ./mvnw -pl services/<name> -am package     # one service plus what it needs

## Spring Boot 4 artifact renames (ADR-0007)

Most search results target Boot 3.x. These ids changed:

| Boot 3.x                      | Boot 4.1                                            |
|-------------------------------|-----------------------------------------------------|
| spring-boot-starter-web       | spring-boot-starter-webmvc                          |
| flyway-core                   | spring-boot-starter-flyway (+ flyway-database-postgresql) |
| spring-boot-starter-test      | per-starter: spring-boot-starter-webmvc-test, -data-jpa-test, ... |
| org.testcontainers:postgresql | org.testcontainers:testcontainers-postgresql        |
| spring-cloud-starter-gateway  | spring-cloud-starter-gateway-server-webmvc          |
| spring-boot-starter-aop       | spring-boot-starter-aspectj                         |

Third-party libraries lag the rename too: `io.github.resilience4j:resilience4j-spring-boot3`
(pinned explicitly — not in any imported BOM) still targets Boot 3's `actuate.health` package
as of 2.3.0, so its health indicator/metrics autoconfiguration silently no-ops on Boot 4.1 —
the circuit breaker itself still works (verified in `docs/baseline.md` §6a), only the
actuator wiring is dead. Confirm with `--debug` and grep the condition report for
`CircuitBreakersHealthIndicatorAutoConfiguration` before assuming a health/metrics gap is a
config mistake.
