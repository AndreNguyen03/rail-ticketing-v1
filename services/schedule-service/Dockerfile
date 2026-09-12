# Build context is the REPOSITORY ROOT, not this directory: the build needs the
# parent pom. From the root:
#   docker build -f services/schedule-service/Dockerfile -t schedule-service .

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
RUN mvn -B -pl services/schedule-service -am dependency:go-offline

COPY services/schedule-service/src services/schedule-service/src
RUN mvn -B -pl services/schedule-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/services/schedule-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
