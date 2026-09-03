package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.security.FileSafety;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@AgentTool(
    name = "read_file",
    description = "Read a text file with line numbers and pagination. Use this instead of cat/head/tail in terminal. Output format: 'LINE_NUM|CONTENT'. Suggests similar filenames if not found. Use offset and limit for large files. Reads exceeding ~100K characters are truncated on a line boundary and return a next_offset; continue with offset to read the rest. Jupyter notebooks (.ipynb), Word documents (.docx), Excel workbooks (.xlsx), PowerPoint decks (.pptx), and PDF text layers are auto-extracted to readable text. NOTE: Cannot read images or other binary files — use vision_analyze for images.",
    toolset = "file"
)
public class ReadFileTool implements ToolHandler {

    private static final int MAX_READ_CHARS = 100_000;
    private static final int MAX_NOTEBOOK_OUTPUT_CHARS = 20_000;
    private static final int MAX_DOCUMENT_BYTES = 50 * 1024 * 1024;
    private static final int MAX_XLSX_ROWS_PER_SHEET = 5000;
    private static final int MAX_XLSX_COLS = 256;
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final ObjectMapper JSON = SharedObjectMapper.get();
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".tiff", ".tif",
        ".mp4", ".mov", ".avi", ".mkv", ".webm", ".wmv", ".flv", ".m4v", ".mpeg", ".mpg",
        ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a", ".wma", ".aiff", ".opus",
        ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar", ".xz", ".z", ".tgz", ".iso",
        ".exe", ".dll", ".so", ".dylib", ".bin", ".o", ".a", ".obj", ".lib",
        ".app", ".msi", ".deb", ".rpm",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods", ".odp",
        ".ttf", ".otf", ".woff", ".woff2", ".eot",
        ".pyc", ".pyo", ".class", ".jar", ".war", ".ear", ".node", ".wasm", ".rlib",
        ".sqlite", ".sqlite3", ".db", ".mdb", ".idx",
        ".psd", ".ai", ".eps", ".sketch", ".fig", ".xd", ".blend", ".3ds", ".max",
        ".swf", ".fla",
        ".lockb", ".dat", ".data"
    );

    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
        "/dev/zero", "/dev/random", "/dev/urandom",
        "/dev/null", "/dev/full", "/dev/tcp",
        "/dev/stdin", "/dev/tty", "/dev/console",
        "/dev/stdout", "/dev/stderr",
        "/dev/fd/0", "/dev/fd/1", "/dev/fd/2"
    );

    private static final Set<String> BLOCKED_PROC_SUFFIXES = Set.of(
        "/fd/0", "/fd/1", "/fd/2",
        "/environ", "/cmdline", "/maps", "/smaps", "/smaps_rollup",
        "/numa_maps", "/mem", "/auxv", "/pagemap"
    );

    private final AgentProperties properties;
    private final FileSafety fileSafety;

    public ReadFileTool(AgentProperties properties) {
        this(properties, FileToolSafety.defaultSafety(properties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReadFileTool(AgentProperties properties, FileSafety fileSafety) {
        this.properties = properties;
        this.fileSafety = fileSafety;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ReadFileArgs args;
        try {
            args = ToolHandler.parseJson(arguments, ReadFileArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonFail(e.getMessage());
        }
        String rawPath = args.path();
        if (rawPath == null || rawPath.isBlank()) {
            return jsonFail("path is required");
        }
        Path path = FileToolSafety.resolvePath(rawPath, session);

        // Device path blocking — check before anything else
        if (isBlockedDevicePath(rawPath)) {
            return jsonFail("Device file blocked: " + rawPath);
        }

        ToolResult safetyCheck = FileToolSafety.ensureReadable(properties, fileSafety, path, rawPath);
        if (safetyCheck != null) {
            return jsonFail(safetyCheck.error());
        }

        if (!Files.exists(path)) {
            return jsonFail("File not found: " + rawPath);
        }
        if (!Files.isRegularFile(path)) {
            return jsonFail("Not a file: " + rawPath);
        }

        try {
            // h55: Detect UTF-16 BOM and transcode to UTF-8 before processing.
            byte[] rawBytes = Files.readAllBytes(path);
            String content;
            boolean extractedDocument = false;
            try {
                content = extractStructuredDocument(path, rawBytes);
                extractedDocument = content != null;
            } catch (DocumentExtractionException e) {
                return jsonFail("Cannot read '" + rawPath + "' (" + extension(path)
                    + "): document extraction failed - " + e.getMessage()
                    + ". Use terminal utilities to inspect or convert the file.");
            }
            if (content == null) {
                if (isBinaryFile(path, rawBytes)) {
                    return jsonFail("Cannot read binary file: " + rawPath + ". Use vision_analyze for images.");
                }
                content = decodeText(rawBytes);
            }

            List<String> lines = content.lines().toList();
            int offset = Math.max(1, args.offset());
            int start = offset - 1;
            int limit = args.limit() > 0 ? args.limit() : 2000;
            int end = Math.min(lines.size(), start + limit);

            StringBuilder sb = new StringBuilder();
            if (start < lines.size()) {
                for (int i = start; i < end; i++) {
                    sb.append(i + 1).append("|").append(lines.get(i)).append("\n");
                }
            }

            // Remove trailing newline
            String result = sb.toString();
            if (result.endsWith("\n")) {
                result = result.substring(0, result.length() - 1);
            }

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("content", result);
            resultMap.put("total_lines", lines.size());
            resultMap.put("file_size", rawBytes.length);
            resultMap.put("truncated", end < lines.size());
            if (extractedDocument) {
                resultMap.put("extracted_document", true);
            }

            // p10: Truncation UX — when output is truncated by limit, show remaining lines count.
            if (end < lines.size()) {
                int shown = end - start;
                int remaining = lines.size() - end;
                resultMap.put("hint", "Use offset=" + (end + 1) + " to continue reading "
                    + "(showing " + offset + "-" + (start + shown) + " of " + lines.size()
                    + " lines, " + remaining + " remaining)");
            }
            if (rawBytes.length == 0) {
                resultMap.put("hint", "File is empty (0 bytes).");
            } else if (offset > lines.size() && !lines.isEmpty()) {
                resultMap.put("hint", "Note: offset " + offset + " is beyond the end of the file ("
                    + lines.size() + " lines total). Retry with offset <= " + lines.size() + ".");
            }

            // Char cap truncation — Hermes parity: return next_offset so the
            // model can continue reading from where it left off.
            if (result.length() > MAX_READ_CHARS) {
                // Find the last complete line boundary within MAX_READ_CHARS
                int cutAt = MAX_READ_CHARS;
                // Walk back to the last newline so we don't split a line
                int lastNewline = result.lastIndexOf('\n', cutAt - 1);
                if (lastNewline > MAX_READ_CHARS / 2) {
                    cutAt = lastNewline;
                }
                String truncated = result.substring(0, cutAt);
                // Count how many lines were included to compute next_offset
                int linesShown = countLines(truncated);
                int nextOffset = offset + linesShown;
                int shownEnd = offset + linesShown - 1;
                resultMap.put("content", truncated);
                resultMap.put("truncated", true);
                resultMap.put("truncated_by", "bytes");
                resultMap.put("next_offset", nextOffset);
                resultMap.put("hint", "Output truncated at the " + formatCount(MAX_READ_CHARS)
                    + "-char read budget after " + linesShown + " line(s) "
                    + "(showing lines " + offset + "-" + shownEnd + " of " + lines.size()
                    + "). Use offset=" + nextOffset + " to continue reading.");
            }
            return ToolResult.ok(toJson(resultMap));
        } catch (IOException e) {
            return jsonFail("Failed to read file: " + e.getMessage());
        }
    }

    private static ToolResult jsonFail(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        String message = error == null ? "File operation failed" : error;
        result.put("success", false);
        result.put("error", message);
        return new ToolResult(false, toJson(result), message);
    }

    private static String toJson(Map<String, Object> result) {
        try {
            return JSON.writeValueAsString(result);
        } catch (IOException e) {
            return String.valueOf(result.getOrDefault("content", ""));
        }
    }

    /** Count the number of newline-separated lines in a string. */
    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count + 1;
    }

    private static String decodeText(byte[] rawBytes) {
        Utf16Encoding utf16 = detectUtf16Encoding(rawBytes);
        if (utf16 != null) {
            int offset = hasUtf16Bom(rawBytes) ? 2 : 0;
            return new String(rawBytes, offset, rawBytes.length - offset, utf16.charset());
        }
        if (hasUtf8Bom(rawBytes)) {
            return new String(rawBytes, 3, rawBytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(rawBytes, StandardCharsets.UTF_8);
    }

    private static String extractNotebookText(JsonNode notebook, String notebookName) {
        if (notebook == null || !notebook.isObject()) {
            return null;
        }
        JsonNode cells = notebook.path("cells");
        if (!cells.isArray()) {
            cells = legacyNotebookCells(notebook);
        }
        if (!cells.isArray() || cells.isEmpty()) {
            return null;
        }

        int markdownCount = 0;
        int codeCount = 0;
        int rawCount = 0;
        StringBuilder out = new StringBuilder();
        for (JsonNode cell : cells) {
            if (!cell.isObject()) {
                continue;
            }
            String type = cell.path("cell_type").asText("");
            switch (type) {
                case "markdown" -> {
                    markdownCount++;
                    appendNotebookBlock(out, "Markdown cell " + markdownCount, sourceText(cell.path("source")));
                }
                case "code" -> {
                    codeCount++;
                    appendNotebookBlock(out, "Code cell " + codeCount, sourceText(cell.path("source")));
                    String outputs = notebookOutputs(cell, codeCount, notebookName);
                    if (!outputs.isBlank()) {
                        appendNotebookBlock(out, "Output (cell " + codeCount + ")", outputs);
                    }
                }
                case "raw" -> {
                    rawCount++;
                    appendNotebookBlock(out, "Raw cell " + rawCount, sourceText(cell.path("source")));
                }
                default -> {
                    // Ignore unknown notebook cell types.
                }
            }
        }
        if (out.isEmpty()) {
            return null;
        }
        return out.toString().stripTrailing() + "\n";
    }

    private static JsonNode legacyNotebookCells(JsonNode notebook) {
        JsonNode worksheets = notebook.path("worksheets");
        if (!worksheets.isArray()) {
            return JSON.createArrayNode();
        }
        com.fasterxml.jackson.databind.node.ArrayNode cells = JSON.createArrayNode();
        for (JsonNode worksheet : worksheets) {
            JsonNode worksheetCells = worksheet.path("cells");
            if (worksheetCells.isArray()) {
                worksheetCells.forEach(cells::add);
            }
        }
        return cells;
    }

    private static void appendNotebookBlock(StringBuilder out, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        out.append("# -- ").append(title).append(" --\n")
            .append(body.stripTrailing())
            .append("\n\n");
    }

    private static String notebookOutputs(JsonNode cell, int cellNumber, String notebookName) {
        JsonNode outputs = cell.path("outputs");
        if (!outputs.isArray()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (JsonNode output : outputs) {
            String rendered = notebookOutputText(output);
            if (!rendered.isBlank()) {
                if (!joined.isEmpty()) {
                    joined.append('\n');
                }
                joined.append(rendered.stripTrailing());
            }
        }
        if (joined.length() <= MAX_NOTEBOOK_OUTPUT_CHARS) {
            return joined.toString();
        }
        int omitted = joined.length() - MAX_NOTEBOOK_OUTPUT_CHARS;
        return joined.substring(0, MAX_NOTEBOOK_OUTPUT_CHARS)
            + "\n... [" + formatCount(omitted) + " output chars truncated from "
            + notebookName + " cell " + cellNumber + "]";
    }

    private static String notebookOutputText(JsonNode output) {
        if (!output.isObject()) {
            return "";
        }
        String type = output.path("output_type").asText("");
        return switch (type) {
            case "stream" -> cleanNotebookText(sourceText(output.path("text")));
            case "error", "pyerr" -> notebookErrorText(output);
            case "execute_result", "display_data", "pyout" -> notebookDataText(output);
            default -> "";
        };
    }

    private static String notebookErrorText(JsonNode output) {
        String header = ("Error: " + output.path("ename").asText("") + ": "
            + output.path("evalue").asText("")).replaceAll(":\\s*$", "");
        String traceback = cleanNotebookText(sourceText(output.path("traceback")));
        return traceback.isBlank() ? header : header + "\n" + traceback;
    }

    private static String notebookDataText(JsonNode output) {
        JsonNode data = output.path("data");
        if (!data.isObject()) {
            String text = sourceText(output.path("text"));
            return text.isBlank() ? "" : cleanNotebookText(text);
        }
        if (data.has("application/vnd.jupyter.widget-view+json")) {
            return "[interactive widget - omitted]";
        }
        for (String mime : List.of("text/plain", "text/markdown")) {
            String text = sourceText(data.path(mime));
            if (!text.isBlank()) {
                return cleanNotebookText(text);
            }
        }
        for (java.util.Map.Entry<String, JsonNode> field : data.properties()) {
            if (field.getKey().startsWith("image/")) {
                return "[" + field.getKey() + " output - omitted]";
            }
        }
        String html = sourceText(data.path("text/html"));
        if (!html.isBlank()) {
            return "[text/html output - " + formatCount(html.length()) + " chars, omitted]";
        }
        return "";
    }

    private static String sourceText(JsonNode source) {
        if (source == null || source.isMissingNode() || source.isNull()) {
            return "";
        }
        if (source.isTextual()) {
            return source.asText();
        }
        if (source.isArray()) {
            StringBuilder out = new StringBuilder();
            for (JsonNode item : source) {
                if (item.isTextual()) {
                    out.append(item.asText());
                }
            }
            return out.toString();
        }
        return "";
    }

    private static String cleanNotebookText(String text) {
        String cleaned = ANSI_ESCAPE.matcher(text == null ? "" : text).replaceAll("").replace("\r\n", "\n");
        StringBuilder out = new StringBuilder(cleaned.length());
        for (String line : cleaned.split("\n", -1)) {
            String[] frames = line.split("\r");
            out.append(frames.length == 0 ? "" : frames[frames.length - 1]).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String extractStructuredDocument(Path path, byte[] rawBytes) throws DocumentExtractionException {
        String ext = extension(path);
        return switch (ext) {
            case ".ipynb" -> extractNotebookOrNull(path, rawBytes);
            case ".docx" -> extractDocx(rawBytes);
            case ".xlsx" -> extractXlsx(rawBytes);
            case ".pptx" -> extractPptx(rawBytes);
            case ".pdf" -> extractPdf(rawBytes);
            default -> null;
        };
    }

    private static String extractPdf(byte[] rawBytes) throws DocumentExtractionException {
        ensureDocumentSize(rawBytes);
        try (PDDocument document = Loader.loadPDF(rawBytes)) {
            String text = new PDFTextStripper().getText(document);
            text = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
            return text.isBlank() ? "[PDF has no extractable text]\n" : text + "\n";
        } catch (IOException e) {
            throw new DocumentExtractionException("Failed to extract PDF text: " + e.getMessage(), e);
        }
    }

    private static String extractPptx(byte[] rawBytes) throws DocumentExtractionException {
        ensureDocumentSize(rawBytes);
        Map<String, byte[]> entries = zipEntries(rawBytes);
        TreeMap<Integer, String> slides = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.matches("ppt/slides/slide\\d+\\.xml")) {
                continue;
            }
            int slideNumber = slideNumber(name);
            org.w3c.dom.Document slide = parseXml(entry.getValue(), name);
            String text = pptxSlideText(slide);
            if (!text.isBlank()) {
                slides.put(slideNumber, text);
            }
        }
        if (slides.isEmpty()) {
            throw new DocumentExtractionException("PPTX contains no extractable text");
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, String> slide : slides.entrySet()) {
            out.append("# -- Slide ").append(slide.getKey()).append(" --\n")
                .append(slide.getValue()).append("\n\n");
        }
        return out.toString().stripTrailing() + "\n";
    }

    private static int slideNumber(String entryName) {
        String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
        String number = fileName.replaceFirst("^slide", "").replaceFirst("\\.xml$", "");
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String pptxSlideText(org.w3c.dom.Document slide) {
        NodeList textNodes = slide.getElementsByTagNameNS("*", "t");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < textNodes.getLength(); i++) {
            String text = textNodes.item(i).getTextContent();
            if (text != null && !text.isBlank()) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(text.strip());
            }
        }
        return out.toString();
    }

    private static String extractNotebookOrNull(Path path, byte[] rawBytes) {
        String text = decodeText(rawBytes);
        try {
            return extractNotebookText(JSON.readTree(text), path.getFileName().toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractDocx(byte[] rawBytes) throws DocumentExtractionException {
        ensureDocumentSize(rawBytes);
        Map<String, byte[]> entries = zipEntries(rawBytes);
        byte[] documentXml = entries.get("word/document.xml");
        if (documentXml == null) {
            throw new DocumentExtractionException("Missing word/document.xml");
        }
        org.w3c.dom.Document document = parseXml(documentXml, "word/document.xml");
        NodeList paragraphs = document.getElementsByTagNameNS("*", "p");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            StringBuilder paragraph = new StringBuilder();
            appendDocxParagraphText(paragraphs.item(i), paragraph);
            for (String line : paragraph.toString().split("\n", -1)) {
                out.append(line).append('\n');
            }
        }
        String text = out.toString().stripTrailing();
        if (text.isBlank()) {
            throw new DocumentExtractionException("DOCX contains no extractable text");
        }
        return text + "\n";
    }

    private static void appendDocxParagraphText(Node node, StringBuilder out) {
        String localName = node.getLocalName();
        if ("t".equals(localName)) {
            out.append(node.getTextContent());
            return;
        }
        if ("tab".equals(localName)) {
            out.append('\t');
            return;
        }
        if ("br".equals(localName) || "cr".equals(localName)) {
            out.append('\n');
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendDocxParagraphText(children.item(i), out);
        }
    }

    private static String extractXlsx(byte[] rawBytes) throws DocumentExtractionException {
        ensureDocumentSize(rawBytes);
        Map<String, byte[]> entries = zipEntries(rawBytes);
        List<String> sharedStrings = sharedStrings(entries);
        List<SheetInfo> sheets = workbookSheets(entries);
        Map<String, String> relationships = workbookRelationships(entries);
        StringBuilder out = new StringBuilder();
        for (SheetInfo sheet : sheets) {
            if ("hidden".equals(sheet.state()) || "veryHidden".equals(sheet.state())) {
                continue;
            }
            String part = sheetPart(relationships.get(sheet.relationshipId()));
            byte[] sheetXml = part == null ? null : entries.get(part);
            if (sheetXml == null) {
                continue;
            }
            List<List<String>> rows = sheetRows(sheetXml, sharedStrings);
            out.append("# -- Sheet: ").append(sheet.name()).append(" --\n");
            if (rows.isEmpty()) {
                out.append("(empty)\n\n");
            } else {
                for (List<String> row : rows) {
                    out.append(String.join("\t", row)).append('\n');
                }
                out.append('\n');
            }
        }
        String text = out.toString().stripTrailing();
        if (text.isBlank()) {
            throw new DocumentExtractionException("XLSX has no visible sheets with content");
        }
        return text + "\n";
    }

    private static List<String> sharedStrings(Map<String, byte[]> entries) throws DocumentExtractionException {
        byte[] sharedStringsXml = entries.get("xl/sharedStrings.xml");
        if (sharedStringsXml == null) {
            return List.of();
        }
        org.w3c.dom.Document document = parseXml(sharedStringsXml, "xl/sharedStrings.xml");
        NodeList items = document.getElementsByTagNameNS("*", "si");
        java.util.ArrayList<String> values = new java.util.ArrayList<>(items.getLength());
        for (int i = 0; i < items.getLength(); i++) {
            StringBuilder text = new StringBuilder();
            appendTextNodes(items.item(i), text);
            values.add(text.toString());
        }
        return values;
    }

    private static List<SheetInfo> workbookSheets(Map<String, byte[]> entries) throws DocumentExtractionException {
        byte[] workbookXml = entries.get("xl/workbook.xml");
        if (workbookXml == null) {
            throw new DocumentExtractionException("Missing xl/workbook.xml");
        }
        org.w3c.dom.Document document = parseXml(workbookXml, "xl/workbook.xml");
        NodeList sheetNodes = document.getElementsByTagNameNS("*", "sheet");
        java.util.ArrayList<SheetInfo> sheets = new java.util.ArrayList<>(sheetNodes.getLength());
        for (int i = 0; i < sheetNodes.getLength(); i++) {
            Node node = sheetNodes.item(i);
            sheets.add(new SheetInfo(
                attribute(node, "name", "Sheet"),
                attribute(node, "state", "visible"),
                attributeNs(node, REL_NS, "id", "")));
        }
        return sheets;
    }

    private static Map<String, String> workbookRelationships(Map<String, byte[]> entries)
        throws DocumentExtractionException {
        byte[] relsXml = entries.get("xl/_rels/workbook.xml.rels");
        if (relsXml == null) {
            return Map.of();
        }
        org.w3c.dom.Document document = parseXml(relsXml, "xl/_rels/workbook.xml.rels");
        NodeList relNodes = document.getElementsByTagNameNS("*", "Relationship");
        Map<String, String> relationships = new HashMap<>();
        for (int i = 0; i < relNodes.getLength(); i++) {
            Node node = relNodes.item(i);
            String id = attribute(node, "Id", "");
            String target = attribute(node, "Target", "");
            if (!id.isBlank()) {
                relationships.put(id, target);
            }
        }
        return relationships;
    }

    private static List<List<String>> sheetRows(byte[] sheetXml, List<String> sharedStrings)
        throws DocumentExtractionException {
        org.w3c.dom.Document document = parseXml(sheetXml, "worksheet");
        NodeList rowNodes = document.getElementsByTagNameNS("*", "row");
        java.util.ArrayList<List<String>> rows = new java.util.ArrayList<>();
        for (int rowIndex = 0; rowIndex < rowNodes.getLength() && rows.size() < MAX_XLSX_ROWS_PER_SHEET; rowIndex++) {
            Node rowNode = rowNodes.item(rowIndex);
            NodeList children = rowNode.getChildNodes();
            TreeMap<Integer, String> cells = new TreeMap<>();
            int maxColumn = -1;
            for (int i = 0; i < children.getLength(); i++) {
                Node cell = children.item(i);
                if (!"c".equals(cell.getLocalName())) {
                    continue;
                }
                int column = cell.hasAttributes() && cell.getAttributes().getNamedItem("r") != null
                    ? columnIndex(cell.getAttributes().getNamedItem("r").getNodeValue())
                    : maxColumn + 1;
                if (column >= MAX_XLSX_COLS) {
                    continue;
                }
                cells.put(column, cellValue(cell, sharedStrings));
                maxColumn = Math.max(maxColumn, column);
            }
            if (maxColumn >= 0) {
                java.util.ArrayList<String> row = new java.util.ArrayList<>(maxColumn + 1);
                for (int column = 0; column <= maxColumn; column++) {
                    row.add(cells.getOrDefault(column, ""));
                }
                rows.add(row);
            } else {
                rows.add(List.of());
            }
        }
        while (!rows.isEmpty() && rows.get(rows.size() - 1).stream().noneMatch(value -> !value.isBlank())) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    private static String cellValue(Node cell, List<String> sharedStrings) {
        String value = firstChildText(cell, "v");
        String type = attribute(cell, "t", "");
        return switch (type) {
            case "s" -> {
                try {
                    yield sharedStrings.get(Integer.parseInt(value));
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    yield "";
                }
            }
            case "inlineStr" -> {
                StringBuilder text = new StringBuilder();
                appendTextNodes(cell, text);
                yield text.toString();
            }
            case "b" -> "1".equals(value.strip()) || "true".equalsIgnoreCase(value.strip()) ? "TRUE" : "FALSE";
            case "e" -> value.isBlank() ? "#ERROR" : value;
            default -> value;
        };
    }

    private static String firstChildText(Node node, String localName) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (localName.equals(child.getLocalName())) {
                return child.getTextContent() == null ? "" : child.getTextContent();
            }
        }
        return "";
    }

    private static void appendTextNodes(Node node, StringBuilder out) {
        if ("t".equals(node.getLocalName())) {
            out.append(node.getTextContent() == null ? "" : node.getTextContent());
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendTextNodes(children.item(i), out);
        }
    }

    private static int columnIndex(String cellReference) {
        int value = 0;
        for (int i = 0; i < cellReference.length(); i++) {
            char c = cellReference.charAt(i);
            if (!Character.isLetter(c)) {
                break;
            }
            value = value * 26 + Character.toUpperCase(c) - 'A' + 1;
        }
        return Math.max(value - 1, 0);
    }

    private static String sheetPart(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String normalized = target.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.startsWith("xl/") ? normalized : "xl/" + normalized;
    }

    private static String attribute(Node node, String name, String fallback) {
        if (node == null || !node.hasAttributes() || node.getAttributes().getNamedItem(name) == null) {
            return fallback;
        }
        return node.getAttributes().getNamedItem(name).getNodeValue();
    }

    private static String attributeNs(Node node, String namespace, String name, String fallback) {
        if (node == null || !node.hasAttributes()) {
            return fallback;
        }
        Node attr = node.getAttributes().getNamedItemNS(namespace, name);
        return attr == null ? fallback : attr.getNodeValue();
    }

    private static org.w3c.dom.Document parseXml(byte[] bytes, String partName) throws DocumentExtractionException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new DocumentExtractionException("Malformed XML in " + partName + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, byte[]> zipEntries(byte[] rawBytes) throws DocumentExtractionException {
        Map<String, byte[]> entries = new HashMap<>();
        byte[] buffer = new byte[8192];
        int totalInflated = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(rawBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    totalInflated += read;
                    if (totalInflated > MAX_DOCUMENT_BYTES) {
                        throw new DocumentExtractionException("Document too large to convert (limit is "
                            + formatCount(MAX_DOCUMENT_BYTES) + " bytes)");
                    }
                    out.write(buffer, 0, read);
                }
                entries.put(entry.getName().replace('\\', '/'), out.toByteArray());
            }
        } catch (IOException e) {
            throw new DocumentExtractionException("Not a valid Office document: " + e.getMessage(), e);
        }
        return entries;
    }

    private static void ensureDocumentSize(byte[] rawBytes) throws DocumentExtractionException {
        if (rawBytes.length > MAX_DOCUMENT_BYTES) {
            throw new DocumentExtractionException("Document too large to convert ("
                + formatCount(rawBytes.length) + " bytes, limit is "
                + formatCount(MAX_DOCUMENT_BYTES) + " bytes)");
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private boolean isBinaryFile(Path path, byte[] rawBytes) {
        if (detectUtf16Encoding(rawBytes) != null) {
            return false;
        }
        if (hasBinaryExtension(path)) {
            return true;
        }
        return isLikelyBinaryBytes(rawBytes);
    }

    private boolean hasBinaryExtension(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dotIndex);
        return BINARY_EXTENSIONS.contains(ext);
    }

    private static boolean isLikelyBinaryBytes(byte[] rawBytes) {
        if (rawBytes.length == 0) {
            return false;
        }
        for (byte b : rawBytes) {
            if (b == 0) {
                return true;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes));
            return false;
        } catch (CharacterCodingException e) {
            return true;
        }
    }

    private static boolean hasUtf8Bom(byte[] rawBytes) {
        return rawBytes.length >= 3
            && (rawBytes[0] & 0xFF) == 0xEF
            && (rawBytes[1] & 0xFF) == 0xBB
            && (rawBytes[2] & 0xFF) == 0xBF;
    }

    private static boolean hasUtf16Bom(byte[] rawBytes) {
        return rawBytes.length >= 2
            && (((rawBytes[0] & 0xFF) == 0xFF && (rawBytes[1] & 0xFF) == 0xFE)
                || ((rawBytes[0] & 0xFF) == 0xFE && (rawBytes[1] & 0xFF) == 0xFF));
    }

    private static Utf16Encoding detectUtf16Encoding(byte[] rawBytes) {
        if (rawBytes.length >= 2) {
            int b0 = rawBytes[0] & 0xFF;
            int b1 = rawBytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                return Utf16Encoding.LE;
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return Utf16Encoding.BE;
            }
        }
        int sampleLength = Math.min(rawBytes.length, 200);
        int evenZeros = 0;
        int oddZeros = 0;
        for (int i = 0; i < sampleLength; i++) {
            if (rawBytes[i] == 0) {
                if (i % 2 == 0) {
                    evenZeros++;
                } else {
                    oddZeros++;
                }
            }
        }
        if (evenZeros == 0 && oddZeros >= 2) {
            return Utf16Encoding.LE;
        }
        if (oddZeros == 0 && evenZeros >= 2) {
            return Utf16Encoding.BE;
        }
        return null;
    }

    private static String formatCount(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private record SheetInfo(String name, String state, String relationshipId) {}

    private static class DocumentExtractionException extends Exception {

        DocumentExtractionException(String message) {
            super(message);
        }

        DocumentExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private enum Utf16Encoding {
        LE(StandardCharsets.UTF_16LE),
        BE(StandardCharsets.UTF_16BE);

        private final Charset charset;

        Utf16Encoding(Charset charset) {
            this.charset = charset;
        }

        Charset charset() {
            return charset;
        }
    }

    private boolean isBlockedDevicePath(String rawPath) {
        String raw = rawPath.replace('\\', '/');
        String normalized = Path.of(rawPath).toAbsolutePath().normalize().toString().replace('\\', '/');
        if (matchesBlockedProc(raw) || matchesBlockedProc(normalized)) {
            return true;
        }
        for (String device : BLOCKED_DEVICE_PATHS) {
            if (matchesBlockedDevice(raw, device) || matchesBlockedDevice(normalized, device)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesBlockedDevice(String path, String device) {
        return path.equals(device)
            || path.startsWith(device + "/")
            || path.endsWith(device)
            || path.contains(":/" + device.substring(1));
    }

    private boolean matchesBlockedProc(String path) {
        String normalized = path;
        int procIndex = normalized.indexOf("/proc/");
        if (procIndex > 0) {
            normalized = normalized.substring(procIndex);
        }
        if (!normalized.startsWith("/proc/")) {
            return false;
        }
        for (String suffix : BLOCKED_PROC_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public record ReadFileArgs(
        @ToolParam(description = "absolute or relative path to the file") String path,
        @ToolParam(description = "starting line number (1-based)", required = false) int offset,
        @ToolParam(description = "maximum number of lines to read", required = false) int limit
    ) {}
}
