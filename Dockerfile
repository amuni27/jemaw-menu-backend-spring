# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies first (faster rebuilds)
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

# Build
COPY src ./src
RUN mvn -q -DskipTests clean package

# ---------- Run stage ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Optional: Run as non-root user
RUN useradd -m appuser
USER appuser

# Copy the jar (adjust if your jar name is different)
COPY --from=build /app/target/*.jar app.jar

# Render sets PORT env var; Spring must use it
ENV PORT=8080
EXPOSE 8080

# Good JVM defaults for containers
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# Start
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
