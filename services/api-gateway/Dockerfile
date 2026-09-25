# Build context is the REPOSITORY ROOT, not this directory: the build needs the
# parent pom. From the root:
#   docker build -f services/api-gateway/Dockerfile -t api-gateway .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Every module pom, not just this service's. The root pom is an aggregator that
# declares all four modules, and Maven refuses to read it when a declared module
# directory is missing — even with -pl, which only narrows what gets BUILT, not
# what gets READ.
#
# Poms come before sources so dependency resolution is cached and only re-runs
# when a pom changes. The cost of copying all four is that editing any service
# pom invalidates this layer in all four images.
COPY pom.xml .
COPY services/api-gateway/pom.xml services/api-gateway/
COPY services/schedule-service/pom.xml services/schedule-service/
COPY services/inventory-service/pom.xml services/inventory-service/
COPY services/booking-service/pom.xml services/booking-service/
COPY services/payment-service/pom.xml services/payment-service/
COPY services/quota-service/pom.xml services/quota-service/
COPY services/fare-service/pom.xml services/fare-service/
RUN mvn -B -pl services/api-gateway -am dependency:go-offline

COPY services/api-gateway/src services/api-gateway/src
RUN mvn -B -pl services/api-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/services/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
