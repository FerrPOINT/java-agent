#!/usr/bin/env bash
# Build and run the dev agent in containers with no source-tree bind mount.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose --project-directory "$ROOT" --env-file "$ROOT/.env" -f "$ROOT/docker-compose.dev.yml")
NETWORK="java-agent-runtime"
DB_CONTAINER="java-agent-pg"

require_container() {
    if ! docker inspect "$1" >/dev/null 2>&1; then
        echo "Required container '$1' does not exist; refusing to create a replacement database." >&2
        exit 1
    fi
}

require_container "$DB_CONTAINER"
docker network create "$NETWORK" >/dev/null 2>&1 || true
docker network connect --alias db "$NETWORK" "$DB_CONTAINER" >/dev/null 2>&1 || true

# Ensure named runtime volumes are owned by the unprivileged application user.
for volume in java_agent_state java_agent_workspace java_agent_tmp; do
    docker run --rm -v "${ROOT##*/}_${volume}:/volume" alpine:3.22 chown -R 10001:10001 /volume
done

# The host services have unrestricted /opt/dev/java-agent access. Stop them
# before the container claims their localhost ports and Telegram polling token.
systemctl stop java-agent-bot.service java-agent-backend.service

"${COMPOSE[@]}" up -d --build --remove-orphans

for _ in $(seq 1 30); do
    if curl -fsS --max-time 5 http://127.0.0.1:8090/actuator/health/readiness >/dev/null; then
        break
    fi
    sleep 2
done
curl -fsS --max-time 30 http://127.0.0.1:8090/actuator/health/readiness >/dev/null

backend_id="$("${COMPOSE[@]}" ps -q backend)"
bot_id="$("${COMPOSE[@]}" ps -q bot)"
for id in "$backend_id" "$bot_id"; do
    if [ -z "$id" ]; then
        echo "Container failed to start." >&2
        "${COMPOSE[@]}" ps
        exit 1
    fi
done

for _ in $(seq 1 30); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$bot_id")" = "healthy" ] && break
    sleep 2
done
if [ "$(docker inspect -f '{{.State.Health.Status}}' "$bot_id")" != "healthy" ]; then
    echo "Telegram bot did not become healthy." >&2
    "${COMPOSE[@]}" logs --tail=100 bot
    exit 1
fi
curl -fsS --max-time 60 http://127.0.0.1:8090/actuator/health >/dev/null
docker exec "$bot_id" curl -fsS http://localhost:8091/bot/health >/dev/null

# A base image may contain an empty /root, but the host's agent state and source
# tree must never be present or accessible from either runtime container.
for id in "$backend_id" "$bot_id"; do
    for forbidden in /opt/dev/java-agent /root/.hermes /root/.java-agent; do
        if docker exec "$id" test -e "$forbidden"; then
            echo "Isolation check failed: $id can access $forbidden" >&2
            exit 1
        fi
    done
done

echo "Container deployment is healthy and source-isolated."
"${COMPOSE[@]}" ps
