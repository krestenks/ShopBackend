# Use official OpenJDK 17
FROM eclipse-temurin:17-jdk-alpine

# Set working directory
WORKDIR /app

# Copy Gradle wrapper and source
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY src src

# Make Gradle wrapper executable
RUN chmod +x ./gradlew

# Build the jar
RUN ./gradlew clean shadowJar -x test

# Copy the jar to /app
RUN mkdir -p dist
RUN cp build/libs/*-all.jar dist/ShopBackend.jar || cp build/libs/*.jar dist/ShopBackend.jar

# Make /data persistent
VOLUME ["/data"]

# Expose ports your app listens on
EXPOSE 9090 9091 9092

# Runtime command, passing SQLite DB path
CMD ["java", "-jar", "/app/dist/ShopBackend.jar", "--db.path=/data/ShopManager.db"]
