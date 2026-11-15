FROM gradle:8.8-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon

# multi-arch (amd64 + arm64): the -alpine tag has no arm64 image, which broke builds on Apple Silicon
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
