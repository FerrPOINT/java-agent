package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.security.UrlSafety;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.azhukov.agent.core.security.Redactor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;

@AgentTool(
    name = "web_extract",
    description = "Extract content from web page URLs. Returns clean page content in markdown/text (no LLM summarization — fast). Pages within the char budget (default 15000) return whole; larger pages return a head+tail window with a footer telling you the full text's saved file path and the read_file call to page through the omitted middle. Inline images appear as [IMAGE: alt] placeholders; real image URLs are kept as links. If a URL fails or times out, use the browser tool instead. NOTE: PDF URLs are NOT supported — the tool detects application/pdf and returns an error; download the file and use read_file instead.",
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

        // Use per-call char_limit if provided, otherwise fall back to config default.
        // Hermes parity: clamp to [2000, 500000] range.
        int effectiveMaxChars;
        if (args.char_limit() != null && args.char_limit() > 0) {
            effectiveMaxChars = Math.max(2000, Math.min(args.char_limit(), 500_000));
        } else {
            effectiveMaxChars = Math.max(2000, Math.min(maxChars, 500_000));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        // The public schema caps a call at five URLs. Process the first five even
        // when a malformed caller bypasses schema validation.
        int urlCount = Math.min(args.urls().size(), 5);
        for (int index = 0; index < urlCount; index++) {
            String suppliedUrl = args.urls().get(index);
            String url = suppliedUrl == null ? "" : suppliedUrl.trim();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", url);
            result.put("title", "");
            result.put("content", "");
            result.put("error", null);

            if (url.isBlank()) {
                result.put("error", "URL must not be blank");
            } else if (!urlSafety.isUrlAllowed(url)) {
                result.put("error", "URL blocked by safety policy");
                result.put("blocked_by_policy", true);
            } else {
                try {
                    String extracted = extract(url);
                    if (extracted.startsWith("PDF content detected.")) {
                        result.put("error", extracted);
                    } else {
                        PageContent page = toPageContent(extracted);
                        result.put("title", page.title());
                        result.put("content", truncatePageContent(page.content(), effectiveMaxChars));
                    }
                } catch (IOException e) {
                    result.put("error", "Failed to extract: " + e.getMessage());
                }
            }
            results.add(result);
        }

        try {
            return ToolResult.ok(redactor.redact(
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("results", results))));
        } catch (Exception e) {
            return ToolResult.fail("Failed to serialize extraction results: " + e.getMessage());
        }
    }

    private PageContent toPageContent(String extracted) {
        if (extracted == null || extracted.isBlank()) {
            return new PageContent("", "");
        }
        if (extracted.startsWith("# ")) {
            int titleEnd = extracted.indexOf('\n');
            if (titleEnd > 2) {
                String title = extracted.substring(2, titleEnd).trim();
                String content = extracted.substring(titleEnd).stripLeading();
                return new PageContent(title, content);
            }
        }
        return new PageContent("", extracted);
    }

    private String truncatePageContent(String content, int charLimit) {
        if (content.length() <= charLimit) {
            return content;
        }

        int headBudget = (int) (charLimit * 0.75);
        int tailBudget = charLimit - headBudget;
        String head = content.substring(0, headBudget);
        String tail = content.substring(content.length() - tailBudget);

        int headNl = head.lastIndexOf('\n');
        if (headNl > headBudget * 0.5) {
            head = head.substring(0, headNl);
        }
        int tailNl = tail.indexOf('\n');
        if (tailNl >= 0 && tailNl < tailBudget * 0.5) {
            tail = tail.substring(tailNl + 1);
        }

        return head
            + "\n\n[... middle omitted — use a more specific URL or browser tool for the full page ...]\n\n"
            + tail
            + "\n\n──────── [TRUNCATED] ────────\n"
            + "Showing " + head.length() + " chars (head) + " + tail.length() + " chars (tail)"
            + " of " + content.length() + " total characters.\n"
            + "─────────────────────────────";
    }

    private record PageContent(String title, String content) {}

    protected String extract(String url) throws IOException {
        Connection connection = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (compatible; JavaAgent/1.0)")
            .timeout(timeoutSeconds * 1000)
            .followRedirects(true);

        // M11: Check content-type before parsing — detect PDFs which jsoup can't handle
        Connection.Response response = connection.execute();
        String contentType = response.contentType();
        if (contentType != null && contentType.toLowerCase().contains("application/pdf")) {
            // No PDF parsing library available — return a helpful error
            return "PDF content detected. This tool cannot extract text from PDF files. "
                + "Use a file download tool or a dedicated PDF extraction tool instead.";
        }

        Document doc = response.parse();

        // Hermes parity: remove non-content elements before conversion
        for (Element el : doc.select("script, style, nav, header, footer, aside, form")) {
            el.remove();
        }

        String title = doc.title();
        // Hermes parity: return markdown, not flat text. flexmark preserves
        // headings, lists, links, code blocks, tables — doc.body().text() flattened
        // everything into a single text blob.
        String html = doc.body() != null ? doc.body().html() : "";
        String markdown;
        try {
            markdown = FlexmarkHtmlConverter.builder().build().convert(html);
            // Trim excessive blank lines (flexmark can produce many)
            markdown = markdown.replaceAll("\\n{3,}", "\n\n").trim();
        } catch (Exception e) {
            // Fallback to plain text if markdown conversion fails
            markdown = doc.body() != null ? doc.body().text() : "";
        }

        if (title.isBlank()) {
            return markdown;
        }
        return "# " + title + "\n\n" + markdown;
    }

    // M12: Changed from comma-separated String to List<String> for proper JSON array support
    public record ExtractArgs(
        @ToolParam(description = "List of URLs to extract content from (max 5 URLs per call)") List<String> urls,
        @ToolParam(description = "Optional per-page character budget. Pages larger than this return a head+tail window with a footer telling you the full text's saved file path. Default 15000.", required = false) Integer char_limit
    ) {}
}