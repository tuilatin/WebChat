# Build fat jar in a multi-stage image so the final container is small.
FROM maven:3.9.9-eclipse-temurin-22 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:22-jre-jammy
WORKDIR /app
COPY --from=build /workspace/target/ChatAppRealTime-1.0-SNAPSHOT.jar app.jar
RUN mkdir -p uploads
VOLUME /app/uploads
EXPOSE 4567
ENTRYPOINT ["java", "-jar", "app.jar"]
