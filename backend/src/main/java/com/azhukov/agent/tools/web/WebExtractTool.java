package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.core.security.Redactor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@AgentTool(
    name = "web_extract",
    description = "Extract readable text content from one or more web page URLs.",
    toolset = "web"
)
@Component
public class WebExtractTool implements ToolHandler {

    private final int timeoutSeconds;
    private final int maxChars;
    private final UrlSafety urlSafety;
    private final Redactor redactor;

    public WebExtractTool(AgentProperties agentProperties, UrlSafety urlSafety, Redactor redactor) {
        this.timeoutSeconds = agentProperties.getWeb().getExtractTimeoutSeconds();
        this.maxChars = agentProperties.getWeb().getExtractMaxChars();
        this.urlSafety = urlSafety;
        this.redactor = redactor;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ExtractArgs args = ToolHandler.parseJson(arguments, ExtractArgs.class);
        if (args.urls() == null || args.urls().isBlank()) {
            return ToolResult.fail("URLs are required");
        }

        List<String> urls = Arrays.asList(args.urls().split("\\s*,\\s*"));
        StringBuilder sb = new StringBuilder();
        for (String url : urls) {
            String trimmed = url.trim();
            sb.append("--- URL: ").append(trimmed).append(" ---\n");
            if (!urlSafety.isUrlAllowed(trimmed)) {
                sb.append("URL blocked by safety policy\n\n");
                continue;
            }
            try {
                sb.append(extract(trimmed)).append("\n\n");
            } catch (IOException e) {
                sb.append("Failed to extract: ").append(e.getMessage()).append("\n\n");
            }
        }

        String text = sb.toString();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "\n[truncated]";
        }
        return ToolResult.ok(redactor.redact(text));
    }

    protected String extract(String url) throws IOException {
        Document doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (compatible; JavaAgent/1.0)")
            .timeout(timeoutSeconds * 1000)
            .get();

        for (Element el : doc.select("script, style, nav, header, footer, aside, form")) {
            el.remove();
        }

        String title = doc.title();
        String body = doc.body() != null ? doc.body().text() : "";
        if (title.isBlank()) {
            return body;
        }
        return "Title: " + title + "\n" + body;
    }

    public record ExtractArgs(
        @ToolParam(description = "URL or comma-separated URLs to extract") String urls
    ) {}
}
