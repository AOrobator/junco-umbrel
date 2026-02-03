FROM eclipse-temurin:22-jdk AS build
ENV HOME=/root

WORKDIR /workspace
COPY adapter/ adapter/
COPY vendor/sparrow/ vendor/sparrow/
RUN chmod +x /workspace/vendor/sparrow/gradlew

WORKDIR /workspace/adapter
RUN /workspace/vendor/sparrow/gradlew --no-daemon clean installDist

FROM eclipse-temurin:22-jre
WORKDIR /app
COPY --from=build /workspace/adapter/build/install/junco-adapter /app

ENV JUNCO_PORT=8081
ENV JUNCO_DATA=/data
ENV JUNCO_NETWORK=mainnet

EXPOSE 8081
ENTRYPOINT ["/app/bin/junco-adapter"]
