package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.client.mcp.McpServerService;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.CheckpointManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpProcessTransportTest {
    private static org.apache.tomcat.util.descriptor.web.FilterDef filterDef() {
        var def = new org.apache.tomcat.util.descriptor.web.FilterDef();
        def.setFilterName("identity");
        def.setFilter(new jakarta.servlet.Filter() {
            @Override public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response,
                                           jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
                com.azhukov.agent.core.security.UserContext.set("test-owner", "user");
                try { chain.doFilter(request, response); }
                finally { com.azhukov.agent.core.security.UserContext.clear(); }
            }
        });
        def.setAsyncSupported("true");
        return def;
    }

    @Test
    void realSseClientsCannotControlEachOthersBackgroundProcesses(@TempDir Path dir) throws Exception {
        var props = new AgentProperties();
        props.getMcp().getServer().setEnabled(true);
        props.getMcp().getServer().setTransport("sse");
        props.getMcp().getServer().setSseEndpoint("/mcp/sse");
        props.getMcp().getServer().setMessageEndpoint("/mcp/message");
        var process = new ProcessTool();
        var redactor = mock(Redactor.class);
        when(redactor.redact(anyString())).thenAnswer(i -> i.getArgument(0));
        var terminal = new TerminalTool(process, props, redactor, mock(CheckpointManager.class), new InterruptToken());
        var registry = mock(ToolRegistry.class);
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of(), "additionalProperties", true);
        when(registry.getDefinitions()).thenReturn(List.of(new ToolDefinition("terminal", "terminal", schema), new ToolDefinition("process", "process", schema)));
        when(registry.execute(anyString(), anyString(), anyString(), any(), any())).thenAnswer(i ->
            ("terminal".equals(i.getArgument(0)) ? terminal : process).execute(i.getArgument(2), i.getArgument(3), i.getArgument(4)));
        var service = new McpServerService(props, new ObjectMapper(), registry);
        var server = new Tomcat();
        server.setBaseDir(dir.toString());
        server.setPort(0);
        server.getConnector();
        try {
            service.start();
            var registration = service.getServletRegistration();
            var context = server.addContext("", dir.toString());
            Tomcat.addServlet(context, "mcp", registration.getServlet()).setAsyncSupported(true);
            context.addServletMappingDecoded("/mcp/sse", "mcp");
            context.addServletMappingDecoded("/mcp/message", "mcp");
            context.addFilterDef(filterDef());
            var mapping = new org.apache.tomcat.util.descriptor.web.FilterMap();
            mapping.setFilterName("identity");
            mapping.addURLPattern("/*");
            context.addFilterMap(mapping);
            server.start();
            String url = "http://127.0.0.1:" + server.getConnector().getLocalPort();
            try (var a = McpClient.sync(HttpClientSseClientTransport.builder(url).sseEndpoint("/mcp/sse").build()).build();
                 var b = McpClient.sync(HttpClientSseClientTransport.builder(url).sseEndpoint("/mcp/sse").build()).build()) {
                a.initialize(); b.initialize();
                var started = a.callTool(new McpSchema.CallToolRequest("terminal", Map.of("command", "exec sleep 60", "background", true)));
                assertThat(started.isError()).isNotEqualTo(true);
                String content = ((McpSchema.TextContent) started.content().getFirst()).text();
                var matcher = java.util.regex.Pattern.compile("proc_[a-f0-9]+").matcher(content);
                assertThat(matcher.find()).isTrue();
                String id = matcher.group();
                var foreign = b.callTool(new McpSchema.CallToolRequest("process", Map.of("action", "kill", "session_id", id)));
                assertThat(((McpSchema.TextContent) foreign.content().getFirst()).text()).contains("not_found");
                var own = a.callTool(new McpSchema.CallToolRequest("process", Map.of("action", "poll", "session_id", id)));
                assertThat(((McpSchema.TextContent) own.content().getFirst()).text()).contains("running");
                var killed = a.callTool(new McpSchema.CallToolRequest("process", Map.of("action", "kill", "session_id", id)));
                assertThat(((McpSchema.TextContent) killed.content().getFirst()).text()).contains("Killed process");
            }
        } finally {
            process.cleanup();
            service.stop();
            server.stop();
            server.destroy();
        }
    }
}
