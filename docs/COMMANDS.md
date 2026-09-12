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

Database alone, services run from the IDE — the normal development loop:

    docker compose up -d postgres

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
