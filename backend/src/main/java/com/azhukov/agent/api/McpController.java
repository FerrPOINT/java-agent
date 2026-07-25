package com.azhukov.agent.api;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class McpController {

    private final McpLifecycleManager mcpLifecycleManager;
    private final AgentProperties properties;

    public McpController(McpLifecycleManager mcpLifecycleManager, AgentProperties properties) {
        this.mcpLifecycleManager = mcpLifecycleManager;
        this.properties = properties;
    }

    @GetMapping("/mcp/servers")
    public List<McpLifecycleManager.McpServerInfo> listServers() {
        return mcpLifecycleManager.listServers();
    }

    @PostMapping("/mcp/connect")
    public String connect(@RequestBody Map<String, String> request) {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName(request.getOrDefault("name", "test"));
        server.setTransport(request.getOrDefault("transport", "stdio"));
        server.setCommand(request.get("command"));
        server.getArgs().clear();
        mcpLifecycleManager.connect(server);
        return "OK";
    }
}
