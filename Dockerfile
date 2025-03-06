# Use a base image with Java pre-installed
FROM eclipse-temurin:21-jdk-jammy

# Create a directory to hold your app
WORKDIR /app

# Copy the JAR file from Gradle's build folder
COPY build/libs/echno_backend-*.jar /app/echno_backend.jar

# Expose port 8080
EXPOSE 8080

# Command to run the app
ENTRYPOINT ["java", "-jar", "/app/echno_backend.jar"]