FROM eclipse-temurin:17-jdk-alpine AS build
ARG MODULE=reservation-service
WORKDIR /app
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY reservation-service reservation-service
RUN chmod +x gradlew && ./gradlew :${MODULE}:bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine
ARG MODULE=reservation-service
WORKDIR /app
COPY --from=build /app/${MODULE}/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
