# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first
COPY pom.xml .
RUN mvn -DskipTests dependency:go-offline

# Copy project (safer than only src)
COPY . .

# Build jar
RUN mvn -DskipTests clean package

# ---------- Run stage ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as non-root
RUN useradd -m appuser
USER appuser

# Copy the jar
COPY --from=build /app/target/*.jar app.jar

# Render injects PORT; default 8080 locally
ENV PORT=8080
EXPOSE 8080

# JVM defaults for containers
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# Start (bind Spring to Render's PORT)
CMD ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT} -jar app.jar"]
