# Build the jar first: ./mvnw -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/api-gateway-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8088
USER 1000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
