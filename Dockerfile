FROM clojure:temurin-21-tools-deps@sha256:db77923e67984d00cbf55a4e44cfacdefed5a8fcf1499469086ba1b569f9d937 AS build

WORKDIR /workspace
COPY deps.edn build.clj ./
RUN clojure -P -T:build
COPY src ./src
RUN clojure -T:build uber

FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13

RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/animated-graph-cloud.jar ./animated-graph-cloud.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/animated-graph-cloud.jar"]
CMD ["clojure.main", "-m", "agg.api.main"]
