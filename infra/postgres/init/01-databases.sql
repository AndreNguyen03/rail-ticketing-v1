-- Three databases, three users, no cross-database access.
-- Postgres cannot JOIN across databases, so the service boundary (ADR-0002)
-- is enforced by the engine, not by discipline.

CREATE USER schedule_user  WITH PASSWORD 'schedule_pw';
CREATE USER inventory_user WITH PASSWORD 'inventory_pw';
CREATE USER booking_user   WITH PASSWORD 'booking_pw';

CREATE DATABASE scheduledb  OWNER schedule_user;
CREATE DATABASE inventorydb OWNER inventory_user;
CREATE DATABASE bookingdb   OWNER booking_user;

REVOKE CONNECT ON DATABASE scheduledb  FROM PUBLIC;
REVOKE CONNECT ON DATABASE inventorydb FROM PUBLIC;
REVOKE CONNECT ON DATABASE bookingdb   FROM PUBLIC;

GRANT CONNECT ON DATABASE scheduledb  TO schedule_user;
GRANT CONNECT ON DATABASE inventorydb TO inventory_user;
GRANT CONNECT ON DATABASE bookingdb   TO booking_user;
