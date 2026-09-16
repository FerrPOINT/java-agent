-- V67 (WP-e / Hermes d9e88e19e2 parity): bind stored OAuth refresh tokens
-- to their issuer.
--
-- The authorization server discovered for an MCP server can change (a
-- protected-resource metadata edit, a server migration). A refresh token
-- minted by issuer A must never be replayed at issuer B: besides being
-- protocol-invalid, it silently leaks a credential to a different party.
--
-- token_issuer: the authorization-server identity the refresh token was
-- minted by (token endpoint origin, or RFC 9207 `iss` when present).
-- NULL = legacy rows minted before binding; treated as unbound but NOT
-- silently replayed — the refresh path re-binds them on the next success.

ALTER TABLE mcp_oauth_tokens
    ADD COLUMN IF NOT EXISTS token_issuer TEXT;
