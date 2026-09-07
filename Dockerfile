FROM eclipse-temurin:21.0.12_8-jre-jammy

WORKDIR /app
COPY build/libs/payment-status-cache-lab-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
