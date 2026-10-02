FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app && mkdir -p /app/data && chown -R app:app /app
WORKDIR /app
COPY --from=build /app/target/seat-reservation-1.0.0.jar app.jar
USER app
ENV PORT=8080 JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 CMD wget -qO- http://localhost:${PORT}/health/ready || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
