package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
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
import java.util.List;
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

        // Use per-call char_limit if provided, otherwise fall back to config default
        int effectiveMaxChars = args.char_limit() != null && args.char_limit() > 0
            ? args.char_limit() : maxChars;

        StringBuilder sb = new StringBuilder();
        for (String url : args.urls()) {
            String trimmed = url.trim();
            if (trimmed.isBlank()) continue;
            if (args.urls().size() > 5) break; // Hermes parity: max 5 URLs per call
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
        if (text.length() > effectiveMaxChars) {
            // Hermes parity: head+tail truncation (75% head / 25% tail)
            // instead of cutting only the head. Snap to line boundaries.
            int headBudget = (int) (effectiveMaxChars * 0.75);
            int tailBudget = effectiveMaxChars - headBudget;

            String head = text.substring(0, headBudget);
            String tail = text.substring(text.length() - tailBudget);

            // Snap head back to last newline
            int headNl = head.lastIndexOf('\n');
            if (headNl > headBudget * 0.5) {
                head = head.substring(0, headNl);
            }
            // Snap tail forward to next newline
            int tailNl = tail.indexOf('\n');
            if (tailNl >= 0 && tailNl < tailBudget * 0.5) {
                tail = tail.substring(tailNl + 1);
            }

            text = head
                + "\n\n[... middle omitted — use a more specific URL or browser tool for the full page ...]\n\n"
                + tail
                + "\n\n──────── [TRUNCATED] ────────\n"
                + "Showing " + head.length() + " chars (head) + " + tail.length() + " chars (tail)"
                + " of " + text.length() + " total characters.\n"
                + "─────────────────────────────";
        }
        return ToolResult.ok(redactor.redact(text));
    }

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