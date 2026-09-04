# ADR-004: MCP Java SDK 2.0 for Tool Protocol

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-05-15 |
| **Deciders** | Project lead |
| **Tags** | mcp, tool-integration, protocol |

## Context

The agent has a rich internal tool system (`@AgentTool` beans discovered by `SpringToolRegistry`), but needed to:

1. **Integrate external tools** via the Model Context Protocol (MCP) — the emerging standard for LLM tool interoperability.
2. **Expose its own tools** to external MCP clients (MCP server mode).
3. Support both **stdio** and **SSE** transports for MCP communication.
4. Handle **OAuth authentication** for remote MCP servers.

Options:

- **Build MCP protocol from scratch**: JSON-RPC over stdio/SSE — significant effort, fragile, no community support.
- **MCP Java SDK 1.x**: Early version, limited features, unstable API.
- **MCP Java SDK 2.0**: Mature API, OAuth support, both transports, actively maintained by Anthropic community.

## Decision

Adopt **MCP Java SDK 2.0.0** for all MCP-related functionality.

### Dual role

1. **MCP Client mode** (`agent.mcp.enabled=true`): Connects to external MCP servers (stdio or SSE), discovers their tools, and registers them dynamically in the `ToolRegistry`.

2. **MCP Server mode** (`agent.mcp.server.enabled=true`): Exposes the agent's own tools to external MCP clients via stdio or SSE.

### Configuration

```yaml
agent:
  mcp:
    enabled: false          # client mode
    servers:
      - name: "filesystem"
        transport: "stdio"
        command: "npx"
        args: ["-y", "@modelcontextprotocol/server-filesystem"]
        timeout-seconds: 30
    server:
      enabled: false        # server mode
      transport: "stdio"    # or "sse"
      sse-endpoint: "/mcp/sse"
      message-endpoint: "/mcp/message"
      name: "java-agent"
      version: "1.0.0"
```

### OAuth support

For remote MCP servers requiring OAuth:

```yaml
agent:
  mcp:
    servers:
      - name: "remote-server"
        transport: "sse"
        base-url: "https://example.com/mcp"
        oauth-token-url: "https://example.com/oauth/token"
        oauth-client-id: "..."
        oauth-client-secret: "..."
        oauth-scopes: "read write"
```

OAuth tokens are persisted in `mcp_oauth_tokens` table (migration V10).

## Consequences

**Positive:**

- Standard protocol — interoperability with any MCP-compatible tool server.
- Dynamic tool registration — external MCP tools appear alongside internal `@AgentTool` beans in the `ToolRegistry`.
- Dual mode (client + server) makes the agent both a tool consumer and provider.
- OAuth token management with auto-refresh via `McpOAuthManager`.

**Negative:**

- MCP SDK adds a dependency — API changes may require adapter updates.
- stdio transport spawns external processes — lifecycle management needed (`McpLifecycleManager`).
- SSE transport for server mode requires HTTP endpoint configuration.
- Tool naming conflicts possible between internal and MCP tools — resolved by prefixing.

**Mitigations:**

- `McpLifecycleManager` manages process lifecycle (start/stop/restart).
- `McpServerAutoConfiguration` conditionally creates beans only when `agent.mcp.enabled=true`.
- `McpServerService` handles the MCP server protocol with `JacksonMcpJsonMapper` for serialization.
- `McpOAuthManager` handles token persistence and refresh with `McpOAuthEntity`.

## REST Integration

MCP server endpoints are exposed via `McpController` (4 endpoints):

- `GET /api/v1/mcp/sse` — SSE endpoint for MCP clients
- `POST /api/v1/mcp/message` — Message endpoint for MCP clients
- `POST /api/v1/mcp/reload` — Reload MCP server configuration
- `GET /api/v1/mcp/servers` — List configured MCP servers

## References

- `client/mcp/McpServerService.java` — MCP client/server implementation
- `client/mcp/McpLifecycleManager.java` — process lifecycle
- `client/mcp/McpOAuthManager.java` — OAuth token management
- `client/mcp/McpServerAutoConfiguration.java` — conditional bean creation
- `api/McpController.java` — REST endpoints
- `persistence/entity/McpOAuthEntity.java` — OAuth token persistence
- [MCP specification](https://modelcontextprotocol.io/)
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
