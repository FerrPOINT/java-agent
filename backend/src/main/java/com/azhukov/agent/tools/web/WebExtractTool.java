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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.IDN;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;

@AgentTool(
    name = "web_extract",
    description = "Extract content from web page or PDF URLs. Returns clean content in markdown/text (no LLM summarization — fast). Pages within the char budget (default 15000) return whole; larger pages return a head+tail window with a footer telling you the full text's saved file path and the read_file call to page through the omitted middle. Inline images appear as [IMAGE: alt] placeholders; real image URLs are kept as links. If a URL fails or times out, use the browser tool instead.",
    toolset = "web"
)
@Component
public class WebExtractTool implements ToolHandler {

    private static final int MAX_SAFE_REDIRECTS = 10;
    private static final int MAX_PDF_BYTES = 25 * 1024 * 1024;
    private static final int MAX_STORED_TEXT_CHARS = 2_000_000;
    private static final Pattern MARKDOWN_BASE64_IMAGE = Pattern.compile(
        "!\\[(?<alt>[^\\]]*)\\]\\(\\s*data:image/[^;]+;base64,[A-Za-z0-9+/=\\s]+\\)"
    );
    private static final Pattern PARENTHESIZED_BASE64_IMAGE = Pattern.compile(
        "\\(\\s*data:image/[^;]+;base64,[A-Za-z0-9+/=\\s]+\\)"
    );
    private static final Pattern BARE_BASE64_IMAGE = Pattern.compile(
        "data:image/[^;]+;base64,[A-Za-z0-9+/=]+"
    );
    private static final Pattern SCHEME_AUTHORITY_WHITESPACE = Pattern.compile(
        "^([A-Za-z][A-Za-z0-9+.-]*://)\\s+"
    );
    private static final Pattern SECRET_PREFIX_PATTERN = Pattern.compile(
        "(?i)(sk-[a-z0-9]{20,}|gh[pousr]_[a-z0-9_]{20,}|github_pat_[a-z0-9_]{20,}|"
            + "xox[baprs]-[a-z0-9-]+|AIza[a-z0-9_-]{35}|pplx-[a-z0-9]+|fal_[a-z0-9]+|"
            + "eyJ[a-z0-9_-]+\\.eyJ[a-z0-9_-]+\\.[a-z0-9_-]*)"
    );
    private static final String PATH_SAFE_CHARS = "/%:@!$&'()*+,;=";
    private static final String QUERY_SAFE_CHARS = "/%:@!$&'()*+,;=?";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final Set<String> SENSITIVE_QUERY_PARAM_NAMES = Set.of(
        "access_token",
        "api_key",
        "apikey",
        "auth_token",
        "authorization",
        "awsaccesskeyid",
        "client_secret",
        "credential",
        "credentials",
        "jwt",
        "password",
        "passwd",
        "secret",
        "session_id",
        "signature",
        "token",
        "x_amz_security_token",
        "x_amz_signature",
        "x-amz-security-token",
        "x-amz-signature"
    );

    private final AgentProperties agentProperties;
    private int timeoutSeconds;
    private int maxChars;
    private final UrlSafety urlSafety;
    private final Redactor redactor;
    private final WebsitePolicy websitePolicy;

    @Autowired
    public WebExtractTool(
        AgentProperties agentProperties,
        UrlSafety urlSafety,
        Redactor redactor,
        WebsitePolicy websitePolicy
    ) {
        this.agentProperties = agentProperties;
        this.urlSafety = urlSafety;
        this.redactor = redactor;
        this.websitePolicy = websitePolicy;
    }

    WebExtractTool(AgentProperties agentProperties, UrlSafety urlSafety, Redactor redactor) {
        this(agentProperties, urlSafety, redactor, new WebsitePolicy(agentProperties));
    }

    @PostConstruct
    void init() {
        timeoutSeconds = agentProperties.getWeb().getExtractTimeoutSeconds();
        maxChars = agentProperties.getWeb().getExtractMaxChars();
    }
    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ExtractArgs args;
        try {
            args = ToolHandler.parseJson(arguments, ExtractArgs.class);
        } catch (Exception e) {
            return jsonFailureResponse("Error extracting content: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (args.urls() == null || args.urls().isEmpty()) {
            return jsonFailureResponse("Content was inaccessible or not found");
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
            UrlItem suppliedUrl = extractUrlItem(args.urls().get(index), index);
            String url = suppliedUrl.url();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", url);
            result.put("title", "");
            result.put("content", "");
            result.put("error", null);

            String sensitiveQueryParam = sensitiveQueryParamName(url);
            String websiteBlock = suppliedUrl.error() == null ? websitePolicy.checkAccess(url) : null;
            if (suppliedUrl.error() != null) {
                result.put("error", suppliedUrl.error());
            } else if (containsSecretPrefix(url)) {
                return jsonFailureResponse("Blocked: URL contains what appears to be an API key or token. Secrets must not be sent in URLs.");
            } else if (sensitiveQueryParam != null) {
                return jsonFailureResponse("Blocked: URL contains a credential-like query parameter (" + sensitiveQueryParam + "). Web extract backends are third-party readers; remove the sensitive query parameter or use a local browser session when this access is explicitly required.");
            } else if (websiteBlock != null) {
                result.put("error", websiteBlock);
                result.put("blocked_by_policy", true);
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
                        String clean = convertBase64ImagesToLinks(page.content());
                        result.put("content", truncatePageContent(clean, url, effectiveMaxChars));
                    }
                } catch (IOException e) {
                    result.put("error", "Failed to extract: " + e.getMessage());
                    if (e instanceof RedirectBlockedException) {
                        result.put("blocked_by_policy", true);
                    }
                }
            }
            results.add(result);
        }

        try {
            String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("results", results));
            return ToolResult.ok(convertBase64ImagesToLinks(redact(json)));
        } catch (Exception e) {
            return jsonFailureResponse("Failed to serialize extraction results: " + e.getMessage());
        }
    }

    private ToolResult jsonFailureResponse(String error) {
        String safeError = redact(error);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", safeError);
        try {
            return new ToolResult(false, new ObjectMapper().writeValueAsString(response), safeError);
        } catch (Exception e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"Web extract failed\"}", "Web extract failed");
        }
    }

    private String redact(String output) {
        if (redactor == null) {
            return output;
        }
        String redacted = redactor.redact(output);
        return redacted == null ? output : redacted;
    }

    private UrlItem extractUrlItem(Object item, int index) {
        Object value = item;
        if (value instanceof Map<?, ?> map) {
            Object urlValue = map.get("url");
            if (!(urlValue instanceof String) || ((String) urlValue).isBlank()) {
                urlValue = map.get("href");
            }
            value = urlValue;
        }

        if (value instanceof String str) {
            String url = normalizeUrlForRequest(str);
            if (!url.isBlank()) {
                return new UrlItem(url, null);
            }
        }
        return new UrlItem("", "Invalid URL item at index " + index
            + ": expected a URL string or an object with a string 'url' or 'href' field");
    }

    private String normalizeUrlForRequest(String rawUrl) {
        if (rawUrl == null) {
            return "";
        }
        String raw = SCHEME_AUTHORITY_WHITESPACE.matcher(rawUrl.trim()).replaceFirst("$1");
        if (raw.isBlank()) {
            return raw;
        }

        int schemeEnd = raw.indexOf("://");
        if (schemeEnd <= 0) {
            return raw;
        }
        String scheme = raw.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return raw;
        }

        String remainder = raw.substring(schemeEnd + 3);
        int authorityEnd = firstIndexOfAny(remainder, '/', '?', '#');
        String authority = authorityEnd >= 0 ? remainder.substring(0, authorityEnd) : remainder;
        String suffix = authorityEnd >= 0 ? remainder.substring(authorityEnd) : "";
        if (authority.isBlank()) {
            return raw;
        }

        String normalizedAuthority = normalizeAuthority(authority);
        String normalizedSuffix = normalizeUrlSuffix(suffix);
        return scheme + "://" + normalizedAuthority + normalizedSuffix;
    }

    private String normalizeAuthority(String authority) {
        int at = authority.lastIndexOf('@');
        String userInfo = at >= 0 ? authority.substring(0, at + 1) : "";
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        if (hostPort.startsWith("[")) {
            return userInfo + hostPort;
        }

        int colon = hostPort.lastIndexOf(':');
        boolean hasSinglePortSeparator = colon > 0 && hostPort.indexOf(':') == colon;
        String host = hasSinglePortSeparator ? hostPort.substring(0, colon) : hostPort;
        String port = hasSinglePortSeparator ? hostPort.substring(colon) : "";
        if (host.isBlank()) {
            return authority;
        }
        try {
            host = IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            return authority;
        }
        return userInfo + host + port;
    }

    private String normalizeUrlSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return "";
        }
        int queryStart = suffix.indexOf('?');
        int fragmentStart = suffix.indexOf('#');
        int pathEnd = suffix.length();
        if (queryStart >= 0) {
            pathEnd = queryStart;
        } else if (fragmentStart >= 0) {
            pathEnd = fragmentStart;
        }

        StringBuilder out = new StringBuilder();
        out.append(percentEncode(suffix.substring(0, pathEnd), PATH_SAFE_CHARS));
        if (queryStart >= 0) {
            int queryEnd = fragmentStart >= 0 && fragmentStart > queryStart ? fragmentStart : suffix.length();
            out.append('?').append(percentEncode(suffix.substring(queryStart + 1, queryEnd), QUERY_SAFE_CHARS));
        }
        if (fragmentStart >= 0) {
            out.append('#').append(percentEncode(suffix.substring(fragmentStart + 1), QUERY_SAFE_CHARS));
        }
        return out.toString();
    }

    private String percentEncode(String value, String safeChars) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            char ch = value.charAt(i);
            if (ch == '%' && i + 2 < value.length()
                && isHexDigit(value.charAt(i + 1))
                && isHexDigit(value.charAt(i + 2))) {
                out.append(value, i, i + 3);
                i += 3;
                continue;
            }
            int codePoint = value.codePointAt(i);
            if (isAsciiSafe(codePoint, safeChars)) {
                out.appendCodePoint(codePoint);
            } else {
                byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                for (byte b : encoded) {
                    out.append('%');
                    out.append(HEX[(b >> 4) & 0xF]);
                    out.append(HEX[b & 0xF]);
                }
            }
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    private boolean isAsciiSafe(int codePoint, String safeChars) {
        return codePoint >= 'A' && codePoint <= 'Z'
            || codePoint >= 'a' && codePoint <= 'z'
            || codePoint >= '0' && codePoint <= '9'
            || codePoint == '-' || codePoint == '.' || codePoint == '_' || codePoint == '~'
            || codePoint < 128 && safeChars.indexOf((char) codePoint) >= 0;
    }

    private boolean isHexDigit(char ch) {
        return ch >= '0' && ch <= '9'
            || ch >= 'a' && ch <= 'f'
            || ch >= 'A' && ch <= 'F';
    }

    private int firstIndexOfAny(String value, char... chars) {
        int best = -1;
        for (char ch : chars) {
            int index = value.indexOf(ch);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private boolean containsSecretPrefix(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return SECRET_PREFIX_PATTERN.matcher(url).find()
            || SECRET_PREFIX_PATTERN.matcher(urlDecode(url)).find();
    }

    private String sensitiveQueryParamName(String url) {
        if (url == null || !url.contains("?")) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String query = uri.getRawQuery();
        if (scheme == null
            || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
            || query == null
            || query.isBlank()) {
            return null;
        }

        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals == pair.length() - 1) {
                continue;
            }
            String key = urlDecode(pair.substring(0, equals));
            String value = pair.substring(equals + 1);
            if (!value.isBlank() && SENSITIVE_QUERY_PARAM_NAMES.contains(key.toLowerCase(Locale.ROOT))) {
                return key;
            }
        }
        return null;
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
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

    private String truncatePageContent(String content, String url, int charLimit) {
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

        List<String> footerLines = new ArrayList<>();
        footerLines.add("");
        footerLines.add("──────── [TRUNCATED] ────────");
        footerLines.add("Showing " + formatCount(head.length()) + " chars (head) + "
            + formatCount(tail.length()) + " chars (tail) of "
            + formatCount(content.length()) + " total clean characters.");

        String storedPath = storeFullText(url, content);
        if (storedPath != null) {
            int middleStartLine = countNewlines(head) + 2;
            footerLines.add("Full text saved to: " + storedPath);
            footerLines.add("To read the omitted middle: read_file path=\"" + storedPath
                + "\" offset=" + middleStartLine
                + " limit=200  (the file is the complete page; raise/lower offset to page through it).");
        } else {
            footerLines.add("Full text could not be stored; re-run web_extract on a more specific URL or use browser_navigate for the complete page.");
        }
        footerLines.add("─────────────────────────────");

        return head
            + "\n\n[... middle omitted — see footer ...]\n\n"
            + tail
            + "\n"
            + String.join("\n", footerLines);
    }

    private String convertBase64ImagesToLinks(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        Matcher matcher = MARKDOWN_BASE64_IMAGE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String alt = matcher.group("alt") == null ? "" : matcher.group("alt").trim();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(alt.isBlank() ? "[IMAGE]" : "[IMAGE: " + alt + "]"));
        }
        matcher.appendTail(sb);

        String withoutParenthesized = PARENTHESIZED_BASE64_IMAGE.matcher(sb.toString()).replaceAll("[IMAGE]");
        return BARE_BASE64_IMAGE.matcher(withoutParenthesized).replaceAll("[IMAGE]");
    }

    private String storeFullText(String url, String content) {
        try {
            Path cacheDir = resolveWebCacheDir();
            Files.createDirectories(cacheDir);

            String host = hostSlug(url);
            String digest = sha256(url);
            if (digest.length() > 10) {
                digest = digest.substring(0, 10);
            }
            Path path = cacheDir.resolve(host + "-" + digest + ".md").toAbsolutePath().normalize();
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }

            String stored = content;
            if (stored.length() > MAX_STORED_TEXT_CHARS) {
                stored = stored.substring(0, MAX_STORED_TEXT_CHARS)
                    + "\n\n[... stored copy truncated at " + formatCount(MAX_STORED_TEXT_CHARS)
                    + " chars of " + formatCount(content.length())
                    + "; re-extract a more specific URL for the rest ...]";
            }

            Path temp = Files.createTempFile(cacheDir, ".web-extract-", ".tmp");
            try {
                Files.writeString(temp, stored, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                moveReplacing(temp, path);
            } finally {
                Files.deleteIfExists(temp);
            }
            return path.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private Path resolveWebCacheDir() {
        String configured = agentProperties.getWeb().getExtractCacheDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String hermesHome = System.getenv("HERMES_HOME");
        if (hermesHome == null || hermesHome.isBlank()) {
            hermesHome = Path.of(System.getProperty("user.home", "/root"), ".hermes").toString();
        }
        return Path.of(hermesHome).resolve("cache").resolve("web").toAbsolutePath().normalize();
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String hostSlug(String url) {
        String host = "page";
        try {
            URI uri = URI.create(url);
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                host = uri.getHost().replace(":", "_");
            }
        } catch (IllegalArgumentException e) {
            // Keep default slug.
        }

        String slug = host.replaceAll("[^A-Za-z0-9._-]", "-");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "page" : slug;
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private String formatCount(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private record UrlItem(String url, String error) {}

    private record PageContent(String title, String content) {}

    private static class RedirectBlockedException extends IOException {
        RedirectBlockedException(String reason) {
            super("Redirect blocked: " + reason);
        }
    }

    protected String extract(String url) throws IOException {
        Connection.Response response = executeWithSafeRedirects(url);
        String contentType = response.contentType();
        byte[] body = response.bodyAsBytes();
        if (isPdfResponse(url, contentType, body)) {
            return extractPdf(url, body);
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

    private boolean isPdfResponse(String url, String contentType, byte[] body) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("application/pdf")) {
            return true;
        }
        if (body != null && body.length >= 5
            && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F' && body[4] == '-') {
            return true;
        }
        return url != null && url.toLowerCase(Locale.ROOT).contains(".pdf");
    }

    private String extractPdf(String url, byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            throw new IOException("PDF response body is empty");
        }
        if (body.length > MAX_PDF_BYTES) {
            throw new IOException("PDF response is too large (" + formatCount(body.length)
                + " bytes; limit " + formatCount(MAX_PDF_BYTES) + ")");
        }
        try (PDDocument document = Loader.loadPDF(body)) {
            String text = new PDFTextStripper().getText(document);
            text = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
            String title = "";
            if (document.getDocumentInformation() != null
                && document.getDocumentInformation().getTitle() != null) {
                title = document.getDocumentInformation().getTitle().trim();
            }
            if (title.isBlank()) {
                title = pdfTitleFromUrl(url);
            }
            if (text.isBlank()) {
                text = "[PDF has no extractable text]";
            }
            return title.isBlank() ? text : "# " + title + "\n\n" + text;
        } catch (IOException e) {
            throw new IOException("Failed to extract PDF text: " + e.getMessage(), e);
        }
    }

    private String pdfTitleFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return "";
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            return urlDecode(name).replaceFirst("(?i)\\.pdf$", "").trim();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private Connection.Response executeWithSafeRedirects(String url) throws IOException {
        URI current = URI.create(url);
        for (int redirectCount = 0; redirectCount <= MAX_SAFE_REDIRECTS; redirectCount++) {
            Connection connection = Jsoup.connect(current.toString())
                .userAgent("Mozilla/5.0 (compatible; JavaAgent/1.0)")
                .timeout(timeoutSeconds * 1000)
                .followRedirects(false);
            connection.maxBodySize(MAX_PDF_BYTES);

            Connection.Response response = connection.execute();
            if (!isRedirectStatus(response.statusCode())) {
                return response;
            }

            String location = response.header("Location");
            if (location == null || location.isBlank()) {
                throw new IOException("Redirect response missing Location header");
            }

            URI next;
            try {
                next = current.resolve(location);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid redirect target: " + location, e);
            }
            String nextUrl = next.toString();
            String blockReason = redirectBlockReason(nextUrl);
            if (blockReason != null) {
                throw new RedirectBlockedException(blockReason);
            }
            current = next;
        }
        throw new IOException("Too many redirects");
    }

    private boolean isRedirectStatus(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
    }

    private String redirectBlockReason(String url) {
        if (containsSecretPrefix(url)) {
            return "URL contains what appears to be an API key or token";
        }
        String sensitiveQueryParam = sensitiveQueryParamName(url);
        if (sensitiveQueryParam != null) {
            return "URL contains a credential-like query parameter (" + sensitiveQueryParam + ")";
        }
        String websiteBlock = websitePolicy.checkAccess(url);
        if (websiteBlock != null) {
            return websiteBlock;
        }
        if (!urlSafety.isUrlAllowed(url)) {
            return "URL blocked by safety policy";
        }
        return null;
    }

    // M12: Changed from comma-separated String to JSON array support; accepts search-result objects too.
    public record ExtractArgs(
        @ToolParam(description = "List of URL strings or search-result objects with url/href fields to extract content from (max 5 URLs per call)") List<Object> urls,
        @ToolParam(description = "Optional per-page character budget. Pages larger than this return a head+tail window with a footer telling you the full text's saved file path. Default 15000.", required = false) Integer char_limit
    ) {}
}
