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
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;

@AgentTool(
    name = "web_extract",
    description = "Extract readable text content from one or more web page URLs.",
    toolset = "web"
)
@Component
@RequiredArgsConstructor
public class WebExtractTool implements ToolHandler {

    private final AgentProperties agentProperties;
    private int timeoutSeconds;
    private int maxChars;
    private final UrlSafety urlSafety;
    private final Redactor redactor;



    @PostConstruct
    void init() {
        timeoutSeconds = agentProperties.getWeb().getExtractTimeoutSeconds();
        maxChars = agentProperties.getWeb().getExtractMaxChars();
    }
    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ExtractArgs args = ToolHandler.parseJson(arguments, ExtractArgs.class);
        if (args.urls() == null || args.urls().isEmpty()) {
            return ToolResult.fail("URLs are required");
        }

        StringBuilder sb = new StringBuilder();
        for (String url : args.urls()) {
            String trimmed = url.trim();
            if (trimmed.isBlank()) continue;
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
        Connection connection = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (compatible; JavaAgent/1.0)")
            .timeout(timeoutSeconds * 1000)
            .followRedirects(false);

        // M11: Check content-type before parsing — detect PDFs which jsoup can't handle
        Connection.Response response = connection.execute();
        String contentType = response.contentType();
        if (contentType != null && contentType.toLowerCase().contains("application/pdf")) {
            // No PDF parsing library available — return a helpful error
            return "PDF content detected. This tool cannot extract text from PDF files. "
                + "Use a file download tool or a dedicated PDF extraction tool instead.";
        }

        Document doc = response.parse();

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

    // M12: Changed from comma-separated String to List<String> for proper JSON array support
    public record ExtractArgs(
        @ToolParam(description = "List of URLs to extract content from") List<String> urls
    ) {}
}