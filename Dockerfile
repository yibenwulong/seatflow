FROM eclipse-temurin:17.0.20.1_1-jdk-noble AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:17.0.20.1_1-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system seatflow && useradd --system --gid seatflow seatflow
WORKDIR /app
COPY --from=build /workspace/target/seatflow-0.1.0-SNAPSHOT.jar app.jar
USER seatflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
