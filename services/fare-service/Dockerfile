# Build context is the REPOSITORY ROOT.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml .
COPY services/api-gateway/pom.xml services/api-gateway/
COPY services/schedule-service/pom.xml services/schedule-service/
COPY services/inventory-service/pom.xml services/inventory-service/
COPY services/booking-service/pom.xml services/booking-service/
COPY services/payment-service/pom.xml services/payment-service/
COPY services/quota-service/pom.xml services/quota-service/
COPY services/fare-service/pom.xml services/fare-service/
RUN mvn -B -pl services/fare-service -am dependency:go-offline

COPY services/fare-service/src services/fare-service/src
RUN mvn -B -pl services/fare-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/services/fare-service/target/*.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
