# ADR-006: API Key Authentication

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-08-12 |
| **Deciders** | Project lead |
| **Tags** | security, authentication, api |

## Context

The backend REST API was previously open — any client on the network could call any endpoint. In production deployments behind a reverse proxy, this is a significant risk. We needed a lightweight authentication mechanism that:

1. Works without a user management system (no login, no sessions).
2. Is stateless (no server-side session store).
3. Works with both the Telegram bot and CLI clients.
4. Doesn't require OAuth2 or JWT infrastructure.

Options considered:

1. **No auth** — simplest, but unacceptable for any networked deployment.
2. **OAuth2 / JWT** — robust, but overkill for a single-tenant agent with a few clients.
3. **API key in header** — simple, stateless, widely used for service-to-service auth.

## Decision

Implement API key authentication via `ApiKeyAuthFilter` (Spring Security filter). The key is configured via `agent.security.api-key` in `application.yml` and sent as an `X-API-Key` header. Public endpoints (health, actuator, Telegram webhook, swagger-ui) are exempt.

## Consequences

**Positive:**
- Stateless, no session management overhead.
- One key per deployment; clients (bot, CLI) pass the key in the `X-API-Key` header.
- Easy to rotate — change the config value and restart.
- No external dependencies (no OAuth provider, no JWT library).

**Negative:**
- Single shared key — no per-client identity or revocation.
- Key is stored in config; must be managed via secrets management.
- No fine-grained authorization (all-or-nothing access).
- HTTPS is required to prevent key interception in transit.