# Build context is the REPOSITORY ROOT, not this directory: the build needs the
# parent pom. From the root:
#   docker build -f services/quota-service/Dockerfile -t quota-service .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Every module pom must be present. The root pom is an aggregator that declares
# all modules, and Maven refuses to read it when a declared module directory is
# missing — even with -pl, which only narrows what gets BUILT, not what gets READ.
COPY pom.xml .
COPY services/api-gateway/pom.xml services/api-gateway/
COPY services/schedule-service/pom.xml services/schedule-service/
COPY services/inventory-service/pom.xml services/inventory-service/
COPY services/booking-service/pom.xml services/booking-service/
COPY services/payment-service/pom.xml services/payment-service/
COPY services/quota-service/pom.xml services/quota-service/
COPY services/fare-service/pom.xml services/fare-service/
COPY services/ticket-service/pom.xml services/ticket-service/
COPY services/notification-service/pom.xml services/notification-service/
RUN mvn -B -pl services/quota-service -am dependency:go-offline

COPY services/quota-service/src services/quota-service/src
RUN mvn -B -pl services/quota-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/services/quota-service/target/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
