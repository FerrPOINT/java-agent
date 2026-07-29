#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.e2e.yml"
AGENT_URL="http://localhost:18090"
MAX_WAIT=180

function cleanup() {
    echo "[e2e] Cleaning up Docker Compose..."
    docker compose -f "${COMPOSE_FILE}" down -v || true
}
trap cleanup EXIT

function wait_for_readiness() {
    local url=$1
    local max=$2
    echo "[e2e] Waiting for backend readiness at ${url}/actuator/health/readiness..."
    for ((i = 0; i < max; i++)); do
        if curl -fsS "${url}/actuator/health/readiness" >/dev/null 2>&1; then
            echo "[e2e] Backend is ready after ${i}s"
            return 0
        fi
        sleep 1
    done
    return 1
}

echo "[e2e] Building and starting services..."
docker compose -f "${COMPOSE_FILE}" up -d --build

if ! wait_for_readiness "${AGENT_URL}" "${MAX_WAIT}"; then
    echo "[e2e] Backend failed to become ready"
    docker compose -f "${COMPOSE_FILE}" logs agent --tail 50
    exit 1
fi

SESSION_ID=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
echo "[e2e] Sending chat request with session ${SESSION_ID}..."
RESPONSE=$(curl -fsS -X POST "${AGENT_URL}/api/v1/agent/chat" \
    -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"${SESSION_ID}\",\"message\":\"hello docker e2e\"}")

echo "[e2e] Response: ${RESPONSE}"

if ! echo "${RESPONSE}" | grep -q 'NoOp response'; then
    echo "[e2e] Unexpected response content"
    exit 1
fi

ACTUAL_SESSION_ID=$(echo "${RESPONSE}" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')

echo "[e2e] Verifying context endpoint for session ${ACTUAL_SESSION_ID}..."
CONTEXT=$(curl -fsS "${AGENT_URL}/api/v1/agent/session/${ACTUAL_SESSION_ID}/context")
echo "[e2e] Context: ${CONTEXT}"

if ! echo "${CONTEXT}" | grep -q '"messageCount":3'; then
    echo "[e2e] Unexpected context message count"
    exit 1
fi

echo "[e2e] Docker Compose E2E test PASSED"
