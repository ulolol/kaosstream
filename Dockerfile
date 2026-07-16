# Stage 1: Build stage
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN apk add --no-cache python3 curl
RUN chmod +x gradlew
RUN ./gradlew :server:installDist --no-daemon
RUN python3 bundle_plugins.py

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/server/build/install/server /app/server
COPY --from=builder /app/bundled-plugins /app/bundled-plugins

# Setup environment
ENV CS_PORT=2106
ENV CS_DATA_DIR=/data
EXPOSE 2106

VOLUME ["/data"]

ENTRYPOINT ["/app/server/bin/server"]
