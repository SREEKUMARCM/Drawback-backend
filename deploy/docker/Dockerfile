# syntax=docker/dockerfile:1
# Build any service:  docker build -f deploy/docker/Dockerfile --build-arg MODULE=review-service -t drawback/review-service .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl ${MODULE} -am -DskipTests package \
 && java -Djarmode=tools -jar ${MODULE}/target/${MODULE}-*.jar extract --layers --destination /layers

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
# layered jar => dependency layers are cached between builds; only application classes change per commit
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER app
# Container-aware heap, exit on OOM so the orchestrator restarts a clean process
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC"
EXPOSE 8080
ENTRYPOINT ["sh","-c","exec java org.springframework.boot.loader.launch.JarLauncher"]
