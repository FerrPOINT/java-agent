package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.McpReadResourceRequest;
import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class McpController {

    private final McpLifecycleManager mcpLifecycleManager;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    @GetMapping("/mcp/servers")
    public List<McpLifecycleManager.McpServerInfo> listServers() {
        return mcpLifecycleManager.listServers();
    }

    @GetMapping("/mcp/servers/{name}/tools")
    public List<McpLifecycleManager.DiscoveredTool> listServerTools(@org.springframework.web.bind.annotation.PathVariable String name) {
        return mcpLifecycleManager.listDiscoveredTools().stream()
            .filter(t -> t.serverName().equals(name))
            .toList();
    }

    @PostMapping("/mcp/servers/{name}/tools/{toolName}")
    public Map<String, Object> invokeTool(@org.springframework.web.bind.annotation.PathVariable String name,
                                          @org.springframework.web.bind.annotation.PathVariable String toolName,
                                          @RequestBody Map<String, Object> request) throws java.io.IOException {
        var result = mcpLifecycleManager.executeTool(name, toolName, objectMapper.writeValueAsString(request));
        return Map.of("content", result.content().stream().map(Object::toString).toList());
    }

    @PostMapping("/mcp/servers/{name}/resources")
    public Map<String, String> readResource(@org.springframework.web.bind.annotation.PathVariable String name,
                                            @Valid @RequestBody McpReadResourceRequest request) {
        String uri = request.uri();
        String content = mcpLifecycleManager.readResource(name, uri);
        return Map.of("uri", uri, "content", content);
    }
}
