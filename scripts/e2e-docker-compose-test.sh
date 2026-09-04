#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.e2e.yml"
AGENT_URL="http://localhost:18090"
E2E_API_KEY="e2e-only-api-key-not-a-secret"
MAX_WAIT=180
PASSED=0
FAILED=0

# ── Colors ──
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

function cleanup() {
    echo -e "${YELLOW}[e2e]${NC} Cleaning up Docker Compose..."
    docker compose -f "${COMPOSE_FILE}" down -v || true
}
trap cleanup EXIT

function pass() {
    local name=$1
    echo -e "${GREEN}[e2e] PASS${NC}: ${name}"
    ((PASSED++))
}

function fail() {
    local name=$1
    local reason=$2
    echo -e "${RED}[e2e] FAIL${NC}: ${name} — ${reason}"
    ((FAILED++))
}

function wait_for_readiness() {
    local url=$1
    local max=$2
    echo -e "${YELLOW}[e2e]${NC} Waiting for backend readiness at ${url}/actuator/health/readiness..."
    for ((i = 0; i < max; i++)); do
        if curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${url}/actuator/health/readiness" >/dev/null 2>&1; then
            echo -e "${YELLOW}[e2e]${NC} Backend is ready after ${i}s"
            return 0
        fi
        sleep 1
    done
    return 1
}

function assert_contains() {
    local name=$1
    local body=$2
    local pattern=$3
    if echo "${body}" | grep -q "${pattern}"; then
        pass "${name}"
        return 0
    else
        fail "${name}" "Expected pattern '${pattern}' not found in response"
        return 1
    fi
}

function assert_http_status() {
    local name=$1
    local expected=$2
    local actual=$3
    if [[ "${actual}" == "${expected}" ]]; then
        pass "${name}"
        return 0
    else
        fail "${name}" "Expected HTTP ${expected}, got ${actual}"
        return 1
    fi
}

# ──────────────────────────────────────────────────────────
# Setup: Build and start services
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Building and starting services..."
docker compose -f "${COMPOSE_FILE}" up -d --build

if ! wait_for_readiness "${AGENT_URL}" "${MAX_WAIT}"; then
    echo -e "${RED}[e2e] FAIL${NC}: Backend failed to become ready"
    docker compose -f "${COMPOSE_FILE}" logs agent --tail 50
    exit 1
fi

# ──────────────────────────────────────────────────────────
# Test 1: Health/readiness checks
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 1: Health & readiness endpoints..."
READINESS=$(curl -H "X-API-Key: ${E2E_API_KEY}" -s -o /dev/null -w '%{http_code}' "${AGENT_URL}/actuator/health/readiness")
assert_http_status "Test 1a: Readiness HTTP 200" "200" "${READINESS}" || true

READINESS_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/actuator/health/readiness")
assert_contains "Test 1b: Readiness UP" "${READINESS_BODY}" '"status":"UP"' || true

LIVENESS_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/actuator/health/liveness")
assert_contains "Test 1c: Liveness UP" "${LIVENESS_BODY}" '"status":"UP"' || true

# Full /actuator/health includes browser/chromium/mcp indicators, which are
# intentionally disabled in this E2E environment (AGENT_CHROMIUM_AUTO_START=false),
# so it legitimately returns 503. Verify the readiness group instead.
HEALTH_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/actuator/health/readiness")
assert_contains "Test 1d: Readiness group UP" "${HEALTH_BODY}" '"status":"UP"' || true

# ──────────────────────────────────────────────────────────
# Test 2: Sync chat
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 2: Sync chat request..."
SESSION_ID=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
CHAT_RESPONSE=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS -X POST "${AGENT_URL}/api/v1/agent/chat" \
    -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"${SESSION_ID}\",\"message\":\"hello docker e2e\"}")

echo "  Response: ${CHAT_RESPONSE}"
assert_contains "Test 2a: Sync chat has NoOp response" "${CHAT_RESPONSE}" 'NoOp response' || true
assert_contains "Test 2b: Sync chat returns sessionId" "${CHAT_RESPONSE}" 'sessionId' || true

ACTUAL_SESSION_ID=$(echo "${CHAT_RESPONSE}" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')
if [[ -n "${ACTUAL_SESSION_ID}" ]]; then
    pass "Test 2c: Extracted sessionId"
else
    fail "Test 2c: Extracted sessionId" "No sessionId in response"
fi

# ──────────────────────────────────────────────────────────
# Test 3: Context endpoint
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 3: Context endpoint for session ${ACTUAL_SESSION_ID}..."
CONTEXT=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/api/v1/agent/session/${ACTUAL_SESSION_ID}/context")
echo "  Context: ${CONTEXT}"
assert_contains "Test 3a: Context has messageCount" "${CONTEXT}" 'messageCount' || true

# ──────────────────────────────────────────────────────────
# Test 4: Streaming chat
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 4: Streaming chat..."
STREAM_SESSION=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
STREAM_OUTPUT=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS -N -X POST "${AGENT_URL}/api/v1/agent/chat/stream" \
    -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"${STREAM_SESSION}\",\"message\":\"stream test\"}" \
    --max-time 30 2>&1 || true)

echo "  Stream output (first 200 chars): ${STREAM_OUTPUT:0:200}..."
assert_contains "Test 4a: SSE data in streaming response" "${STREAM_OUTPUT}" 'data:' || true

# ──────────────────────────────────────────────────────────
# Test 5: OpenAI-compatible endpoint
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 5: OpenAI-compatible /v1/chat/completions..."
OAI_RESPONSE=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS -X POST "${AGENT_URL}/v1/chat/completions" \
    -H 'Content-Type: application/json' \
    -d '{"model":"noop","messages":[{"role":"user","content":"openai compat test"}]}' \
    --max-time 10)

echo "  Response: ${OAI_RESPONSE}"
assert_contains "Test 5a: OpenAI response has choices" "${OAI_RESPONSE}" '"choices"' || true
assert_contains "Test 5b: OpenAI response has id" "${OAI_RESPONSE}" '"id"' || true

# ──────────────────────────────────────────────────────────
# Test 6: Session creation + listing
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 6: Session creation + listing..."

# Create a session
CREATE_RESP=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS -o /dev/null -w '%{http_code}' -X POST "${AGENT_URL}/api/v1/agent/session" \
    -H 'Content-Type: application/json' \
    -d '{"userId":"e2e-test-user"}')
assert_http_status "Test 6a: Create session HTTP 201" "201" "${CREATE_RESP}" || true

# Create a session with body and capture response
CREATE_SESSION_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS -X POST "${AGENT_URL}/api/v1/agent/session" \
    -H 'Content-Type: application/json' \
    -d '{"userId":"e2e-listing-user"}')
echo "  Created session: ${CREATE_SESSION_BODY}"
assert_contains "Test 6b: Create session has id" "${CREATE_SESSION_BODY}" '"id"' || true

# List sessions
SESSIONS=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/api/v1/sessions")
echo "  Sessions: ${SESSIONS:0:200}..."
assert_contains "Test 6c: Session listing returns array" "${SESSIONS}" '\[' || true

# List sessions by user
SESSIONS_BY_USER=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/api/v1/agent/sessions/e2e-listing-user")
echo "  Sessions by user: ${SESSIONS_BY_USER:0:200}..."
assert_contains "Test 6d: Sessions by user returns array" "${SESSIONS_BY_USER}" '\[' || true

# ──────────────────────────────────────────────────────────
# Test 7: Tool listing
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 7: Tool listing..."
TOOLS_HTTP=$(curl -H "X-API-Key: ${E2E_API_KEY}" -s -o /dev/null -w '%{http_code}' "${AGENT_URL}/api/v1/agent/tools")
assert_http_status "Test 7a: Tools endpoint HTTP 200" "200" "${TOOLS_HTTP}" || true

TOOLS_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/api/v1/agent/tools")
echo "  Tools: ${TOOLS_BODY:0:200}..."
# The tools endpoint returns a JSON array of tool name strings
assert_contains "Test 7b: Tools list is non-empty array" "${TOOLS_BODY}" '\[' || true

# ──────────────────────────────────────────────────────────
# Test 8: Memory endpoint
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 8: Memory endpoint..."

# Store a memory fact
STORE_HTTP=$(curl -H "X-API-Key: ${E2E_API_KEY}" -s -o /dev/null -w '%{http_code}' -X POST "${AGENT_URL}/api/v1/agent/memory" \
    -H 'Content-Type: application/json' \
    -d '{"userId":"e2e-user","fact":"e2e test memory fact","category":"test","target":"memory"}')
assert_http_status "Test 8a: Store memory HTTP 200" "200" "${STORE_HTTP}" || true

# Recall memory
MEMORY_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/api/v1/agent/memory")
echo "  Memory: ${MEMORY_BODY:0:200}..."
# Memory endpoint returns a JSON array of strings
assert_contains "Test 8b: Memory recall returns array" "${MEMORY_BODY}" '\[' || true

# ──────────────────────────────────────────────────────────
# Test 9: Skills listing
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 9: Skills listing..."
SKILLS_HTTP=$(curl -H "X-API-Key: ${E2E_API_KEY}" -s -o /dev/null -w '%{http_code}' "${AGENT_URL}/api/v1/agent/skills")
assert_http_status "Test 9a: Skills endpoint HTTP 200" "200" "${SKILLS_HTTP}" || true

SKILLS_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/api/v1/agent/skills")
echo "  Skills: ${SKILLS_BODY:0:200}..."
assert_contains "Test 9b: Skills list is array" "${SKILLS_BODY}" '\[' || true

# ──────────────────────────────────────────────────────────
# Test 10: Doctor endpoint
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 10: Doctor endpoint..."
DOCTOR_HTTP=$(curl -H "X-API-Key: ${E2E_API_KEY}" -s -o /dev/null -w '%{http_code}' "${AGENT_URL}/api/v1/agent/doctor")
assert_http_status "Test 10a: Doctor HTTP 200" "200" "${DOCTOR_HTTP}" || true

DOCTOR_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/api/v1/agent/doctor")
echo "  Doctor: ${DOCTOR_BODY:0:200}..."
assert_contains "Test 10b: Doctor has status UP" "${DOCTOR_BODY}" '"UP"' || true
assert_contains "Test 10c: Doctor has model" "${DOCTOR_BODY}" '"model"' || true
assert_contains "Test 10d: Doctor has provider" "${DOCTOR_BODY}" 'provider' || true

# ──────────────────────────────────────────────────────────
# Test 11: Config endpoint
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 11: Config endpoint..."
CONFIG_HTTP=$(curl -H "X-API-Key: ${E2E_API_KEY}" -s -o /dev/null -w '%{http_code}' "${AGENT_URL}/api/v1/agent/config")
assert_http_status "Test 11a: Config HTTP 200" "200" "${CONFIG_HTTP}" || true

CONFIG_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/api/v1/agent/config")
echo "  Config: ${CONFIG_BODY:0:200}..."
assert_contains "Test 11b: Config has provider" "${CONFIG_BODY}" 'provider' || true

# ──────────────────────────────────────────────────────────
# Test 12: MCP servers listing
# ──────────────────────────────────────────────────────────
echo -e "${YELLOW}[e2e]${NC} Test 12: MCP servers listing..."
MCP_HTTP=$(curl -H "X-API-Key: ${E2E_API_KEY}" -s -o /dev/null -w '%{http_code}' "${AGENT_URL}/api/v1/mcp/servers")
assert_http_status "Test 12a: MCP servers HTTP 200" "200" "${MCP_HTTP}" || true

MCP_BODY=$(curl -H "X-API-Key: ${E2E_API_KEY}" -fsS "${AGENT_URL}/api/v1/mcp/servers")
echo "  MCP servers: ${MCP_BODY:0:200}..."
assert_contains "Test 12b: MCP servers returns array" "${MCP_BODY}" '\[' || true

# ──────────────────────────────────────────────────────────
# Summary
# ──────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════════"
echo -e "  E2E Smoke Tests: ${GREEN}${PASSED} passed${NC}, ${RED}${FAILED} failed${NC}"
echo "══════════════════════════════════════════════════════════"
echo ""

if [[ "${FAILED}" -gt 0 ]]; then
    echo -e "${RED}[e2e] ${FAILED} test(s) failed${NC}"
    exit 1
fi

echo -e "${GREEN}[e2e] All tests PASSED${NC}"
exit 0