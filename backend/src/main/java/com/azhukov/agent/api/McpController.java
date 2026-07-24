package com.azhukov.agent.api;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class McpController {

    private final McpLifecycleManager mcpLifecycleManager;

    public McpController(McpLifecycleManager mcpLifecycleManager) {
        this.mcpLifecycleManager = mcpLifecycleManager;
    }

    @GetMapping("/mcp/servers")
    public List<McpLifecycleManager.McpServerInfo> listServers() {
        return mcpLifecycleManager.listServers();
    }
}
