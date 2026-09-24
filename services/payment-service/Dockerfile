FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY services/payment-service/pom.xml services/payment-service/
COPY services/api-gateway/pom.xml services/api-gateway/
COPY services/schedule-service/pom.xml services/schedule-service/
COPY services/inventory-service/pom.xml services/inventory-service/
COPY services/booking-service/pom.xml services/booking-service/
COPY services/quota-service/pom.xml services/quota-service/
RUN mvn -B -pl services/payment-service -am dependency:go-offline
COPY services/payment-service/src services/payment-service/src
RUN mvn -B -pl services/payment-service -am package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/services/payment-service/target/*.jar app.jar
ENTRYPOINT ["java","-jar","/app/app.jar"]
