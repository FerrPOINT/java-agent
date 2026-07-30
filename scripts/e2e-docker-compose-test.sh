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

# --- Test 1: Basic chat ---
SESSION_ID=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
echo "[e2e] Test 1: Chat request with session ${SESSION_ID}..."
RESPONSE=$(curl -fsS -X POST "${AGENT_URL}/api/v1/agent/chat" \
    -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"${SESSION_ID}\",\"message\":\"hello docker e2e\"}")

echo "[e2e] Response: ${RESPONSE}"

if ! echo "${RESPONSE}" | grep -q 'NoOp response'; then
    echo "[e2e] FAIL: Unexpected response content"
    exit 1
fi

ACTUAL_SESSION_ID=$(echo "${RESPONSE}" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')
echo "[e2e] Test 1: PASSED"

# --- Test 2: Context endpoint ---
echo "[e2e] Test 2: Context endpoint for session ${ACTUAL_SESSION_ID}..."
CONTEXT=$(curl -fsS "${AGENT_URL}/api/v1/agent/session/${ACTUAL_SESSION_ID}/context")
echo "[e2e] Context: ${CONTEXT}"

if ! echo "${CONTEXT}" | grep -q '"messageCount":3'; then
    echo "[e2e] FAIL: Unexpected context message count"
    exit 1
fi
echo "[e2e] Test 2: PASSED"

# --- Test 3: Streaming ---
STREAM_SESSION=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
echo "[e2e] Test 3: Streaming chat with session ${STREAM_SESSION}..."
STREAM_OUTPUT=$(curl -fsS -N -X POST "${AGENT_URL}/api/v1/agent/chat/stream" \
    -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"${STREAM_SESSION}\",\"message\":\"stream test\"}" \
    --max-time 30 2>&1 || true)

if ! echo "${STREAM_OUTPUT}" | grep -q 'data:'; then
    echo "[e2e] FAIL: No SSE data in streaming response"
    exit 1
fi
echo "[e2e] Test 3: PASSED"

# --- Test 4: OpenAI-compatible endpoint ---
echo "[e2e] Test 4: OpenAI-compatible /v1/chat/completions..."
OAI_RESPONSE=$(curl -fsS -X POST "${AGENT_URL}/v1/chat/completions" \
    -H 'Content-Type: application/json' \
    -d '{"model":"noop","messages":[{"role":"user","content":"openai compat test"}]}' \
    --max-time 10)

if ! echo "${OAI_RESPONSE}" | grep -q '"choices"'; then
    echo "[e2e] FAIL: No choices in OpenAI response"
    exit 1
fi
echo "[e2e] Test 4: PASSED"

# --- Test 5: Session listing ---
echo "[e2e] Test 5: Session listing..."
SESSIONS=$(curl -fsS "${AGENT_URL}/api/v1/agent/sessions")
if ! echo "${SESSIONS}" | grep -q '\['; then
    echo "[e2e] FAIL: No session list returned"
    exit 1
fi
echo "[e2e] Test 5: PASSED"

# --- Test 6: Health endpoints ---
echo "[e2e] Test 6: Health endpoints..."
HEALTH=$(curl -fsS "${AGENT_URL}/actuator/health")
if ! echo "${HEALTH}" | grep -q '"status":"UP"'; then
    echo "[e2e] FAIL: Health not UP"
    exit 1
fi

READINESS=$(curl -fsS "${AGENT_URL}/actuator/health/readiness")
if ! echo "${READINESS}" | grep -q '"status":"UP"'; then
    echo "[e2e] FAIL: Readiness not UP"
    exit 1
fi
echo "[e2e] Test 6: PASSED"

echo ""
echo "[e2e] All 6 tests PASSED"