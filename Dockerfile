# --- build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# --- runtime ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 harness
WORKDIR /app
COPY --from=build /src/target/agent-harness-*.jar app.jar
USER harness
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# Liveness/readiness: /actuator/health (wire it to your orchestrator's probes)
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
