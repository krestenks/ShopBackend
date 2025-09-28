# Dockerfile
FROM eclipse-temurin:17-jdk-jammy


WORKDIR /app


# Copy jar built by CI into image
COPY dist/ShopBackend.jar /app/ShopBackend.jar


# Create directory for SQLite DB and make it a volume in deployments
RUN mkdir -p /data
VOLUME ["/data"]


# Expose expected ports
EXPOSE 9090 9091 9092


# Default command
CMD ["java", "-jar", "/app/ShopBackend.jar", "--db.path=/data/ShopManager.db"]