# mock-provider image (ADR-FND-03-002). Build context: the directory holding mock-provider.jar
# (mock-provider/build/libs after ./gradlew :mock-provider:bootJar), or Testcontainers' ImageFromDockerfile.
# eclipse-temurin:25-jre-noble, pinned by digest (resolved 2026-11-03); bump the tag and the digest together.
FROM eclipse-temurin@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce

RUN groupadd --system mock && useradd --system --gid mock --no-create-home mock

COPY mock-provider.jar /app/mock-provider.jar

USER mock
# A published port reaches the container's own interface, not its loopback; the jar alone binds 127.0.0.1.
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/mock-provider.jar"]
