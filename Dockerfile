# syntax=docker/dockerfile:1
# plmux: beat-link (submodule) + Javalin サーバー + Vue GUI を 1 イメージにまとめる。
# ビルド前に `git submodule update --init` が必要。

# ---- Web GUI ----
FROM docker.io/library/node:22-bookworm-slim AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- Server (crate-digger / beat-link もソースからビルド) ----
FROM docker.io/library/eclipse-temurin:21-jdk AS server
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY vendor/ vendor/
COPY server/ server/
COPY --from=web /src/web/dist web/dist
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests package -pl server -am \
 && cp server/target/plmux-server.jar /plmux-server.jar

# ---- Runtime ----
# lib-carabiner 同梱の Carabiner バイナリは glibc + libstdc++ を必要とするため Ubuntu ベースの JRE を使う。
FROM docker.io/library/eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends libstdc++6 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home /data plmux \
 && mkdir -p /data && chown plmux /data
COPY --from=server /plmux-server.jar /app/plmux-server.jar
USER plmux
ENV PLMUX_DATA_DIR=/data \
    PLMUX_HTTP_PORT=8080 \
    JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -XX:+UseZGC -XX:MaxRAMPercentage=75"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/plmux-server.jar"]
