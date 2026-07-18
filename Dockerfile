# Stage 1: Build the frontend from the current working tree.
FROM oven/bun:1 AS frontend-builder
WORKDIR /app/server/web
COPY server/web/package.json server/web/bun.lock ./
RUN bun install --frozen-lockfile
COPY server/web/ ./
RUN bun run build

# Stage 2: Build the JVM server.
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
COPY --from=frontend-builder /app/server/src/main/resources/web /app/server/src/main/resources/web
RUN apk add --no-cache python3 curl
RUN chmod +x gradlew
RUN ./gradlew :server:installDist --no-daemon
RUN python3 bundle_plugins.py

# Stage 3: Runtime stage
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache ffmpeg intel-media-driver libva-intel-driver
WORKDIR /app
COPY --from=builder /app/server/build/install/server /app/server
COPY --from=builder /app/bundled-plugins /app/bundled-plugins

# Setup environment
ENV CS_PORT=2106
ENV CS_DATA_DIR=/data
EXPOSE 2106

VOLUME ["/data"]

ENTRYPOINT ["/app/server/bin/server"]
