FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the pre-built jar file
COPY target/*.jar app.jar

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]