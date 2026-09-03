package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.terminal.TerminalTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReadFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Session session = Session.create("u", "p", "m");

    private ReadFileTool newTool() {
        AgentProperties props = new AgentProperties();
        props.getSecurity().setFileSafetyEnabled(false);
        return new ReadFileTool(props);
    }

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    private static String readContent(ToolResult result) throws Exception {
        return jsonContent(result).path("content").asText();
    }

    @Test
    void malformedToolArgumentsReturnStructuredError() throws Exception {
        ToolResult r = newTool().execute("{", null, session);

        assertThat(r.success()).isFalse();
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Invalid tool arguments");
        assertThat(r.error()).isEqualTo(json.path("error").asText());
    }

    // ── Normal file reading ────────────────────────────────────────────

    @Test
    void readsNormalTextFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("hello.txt"), "line1\nline2\nline3");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir.resolve("hello.txt")) + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("1|line1");
        assertThat(r.content()).contains("2|line2");
        assertThat(r.content()).contains("3|line3");
    }

    @Test
    void readsRelativePathFromSessionWorkdirLikeHermes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("hello.txt"), "session cwd");
        ReadFileTool tool = newTool();
        Session cwdSession = session.withMetadata(TerminalTool.META_WORKDIR, dir.toString());

        ToolResult r = tool.execute("{\"path\":\"hello.txt\",\"offset\":1,\"limit\":10}", null, cwdSession);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("1|session cwd");
    }

    @Test
    void expandsTildeFromUserHomeLikeHermes(@TempDir Path dir) throws Exception {
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", dir.toString());
            Files.writeString(dir.resolve("hello.txt"), "home file");
            ReadFileTool tool = newTool();

            ToolResult r = tool.execute("{\"path\":\"~/hello.txt\",\"offset\":1,\"limit\":10}", null, session);

            assertThat(r.success()).isTrue();
            assertThat(r.content()).contains("1|home file");
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void blocksSensitiveReadsWithDefaultFileSafety(@TempDir Path dir) throws Exception {
        Path envFile = dir.resolve(".env");
        Files.writeString(envFile, "TOKEN=secret");

        ReadFileTool tool = new ReadFileTool(new AgentProperties());
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(envFile) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Reading this path is not allowed");
    }

    @Test
    void blocksAllowedPathSymlinkEscapes(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            !System.getProperty("os.name").toLowerCase().contains("win"),
            "Symlink creation is privilege-dependent on Windows");

        Path allowed = dir.resolve("allowed");
        Path secret = dir.resolve("secret.txt");
        Files.createDirectories(allowed);
        Files.writeString(secret, "secret");
        Path link = allowed.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.abort("Cannot create symlink: " + e.getMessage());
        }

        AgentProperties props = new AgentProperties();
        props.getSecurity().setFileSafetyEnabled(true);
        props.getSecurity().setAllowedPaths(List.of(allowed.toString()));
        ReadFileTool tool = new ReadFileTool(props);

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(link) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Access denied");
    }

    @Test
    void readsFileWithOffsetAndLimit(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lines.txt"), "a\nb\nc\nd\ne");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir.resolve("lines.txt")) + "\",\"offset\":2,\"limit\":2}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("2|b");
        assertThat(r.content()).contains("3|c");
        assertThat(r.content()).doesNotContain("1|a");
        assertThat(r.content()).doesNotContain("4|d");
    }

    // ── Binary file detection ──────────────────────────────────────────

    @Test
    void rejectsBinaryFileByExtension(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("image.png"), "fake png content");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir.resolve("image.png")) + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Cannot read binary file");
        assertThat(r.error()).contains(".png");
    }

    @Test
    void rejectsAnotherBinaryExtension(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("archive.zip"), "fake zip");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir.resolve("archive.zip")) + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Cannot read binary file");
    }

    @Test
    void rejectsClassFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Main.class"), "fake class");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir.resolve("Main.class")) + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Cannot read binary file");
    }

    @Test
    void binaryDetectionIsCaseInsensitive(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("photo.JPG"), "fake jpg");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir.resolve("photo.JPG")) + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Cannot read binary file");
    }

    @Test
    void rejectsBinaryContentWithoutBinaryExtension(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("payload.txt");
        Files.write(file, new byte[] {'a', 0, 0, 'b', 'c'});

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Cannot read binary file");
    }

    @Test
    void rejectsInvalidUtf8WithoutBinaryExtension(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latin1.txt");
        Files.write(file, new byte[] {(byte) 0xC3, 0x28});

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Cannot read binary file");
    }

    @Test
    void readsSvgAsTextLikeHermes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("icon.svg");
        Files.writeString(file, "<svg><text>hello</text></svg>");

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("<svg>");
        assertThat(r.content()).contains("hello");
    }

    @Test
    void stripsUtf8BomFromFirstLineLikeHermes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bom.txt");
        byte[] text = "hello\nworld".getBytes(StandardCharsets.UTF_8);
        byte[] full = new byte[text.length + 3];
        full[0] = (byte) 0xEF;
        full[1] = (byte) 0xBB;
        full[2] = (byte) 0xBF;
        System.arraycopy(text, 0, full, 3, text.length);
        Files.write(file, full);

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("1|hello");
        assertThat(r.content()).doesNotContain("\uFEFF");
    }

    @Test
    void extractsNotebookCellsAsReadableText(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("analysis.ipynb");
        Files.writeString(file, """
            {
              "cells": [
                {"cell_type": "markdown", "source": ["# Heading\\n", "Notes"]},
                {"cell_type": "code", "source": "print('hi')\\n", "outputs": [
                  {"output_type": "stream", "text": ["hello\\n"]},
                  {"output_type": "display_data", "data": {"image/png": "AAAA"}}
                ]}
              ]
            }
            """);

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":20}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("1|# -- Markdown cell 1 --");
        assertThat(r.content()).contains("2|# Heading");
        assertThat(r.content()).contains("5|# -- Code cell 1 --");
        assertThat(r.content()).contains("6|print('hi')");
        assertThat(r.content()).contains("8|# -- Output (cell 1) --");
        assertThat(r.content()).contains("9|hello");
        assertThat(r.content()).contains("10|[image/png output - omitted]");
        assertThat(r.content()).doesNotContain("\"cells\"");
    }

    @Test
    void invalidNotebookFallsBackToRawJson(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.ipynb");
        Files.writeString(file, "{\"cells\":[]}");

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(readContent(r)).contains("1|{\"cells\":[]}");
    }

    @Test
    void extractsDocxTextBeforeBinaryGuard(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.docx");
        writeZip(file, Map.of("word/document.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>World</w:t></w:r></w:p>
                <w:p><w:r><w:t>Second</w:t></w:r><w:r><w:br/></w:r><w:r><w:t>Line</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """));

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(readContent(r)).contains("1|Hello\tWorld");
        assertThat(readContent(r)).contains("2|Second");
        assertThat(readContent(r)).contains("3|Line");
        assertThat(r.content()).doesNotContain("Cannot read binary file");
    }

    @Test
    void malformedDocxReturnsExtractionError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.docx");
        writeZip(file, Map.of("word/document.xml", "<w:document>"));

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("document extraction failed");
        assertThat(r.error()).contains(".docx");
    }

    @Test
    void extractsXlsxVisibleSheetsBeforeBinaryGuard(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("table.xlsx");
        writeZip(file, Map.of(
            "xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
                    <sheet name="Hidden" sheetId="2" state="hidden" r:id="rId2"/>
                  </sheets>
                </workbook>
                """,
            "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Target="worksheets/sheet2.xml"/>
                </Relationships>
                """,
            "xl/sharedStrings.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Name</t></si>
                  <si><t>Alice</t></si>
                </sst>
                """,
            "xl/worksheets/sheet1.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="s"><v>0</v></c>
                      <c r="B1" t="inlineStr"><is><t>Score</t></is></c>
                    </row>
                    <row r="2">
                      <c r="A2" t="s"><v>1</v></c>
                      <c r="B2"><v>42</v></c>
                    </row>
                  </sheetData>
                </worksheet>
                """,
            "xl/worksheets/sheet2.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row><c t="inlineStr"><is><t>Secret</t></is></c></row></sheetData>
                </worksheet>
                """
        ));

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("1|# -- Sheet: Sheet1 --");
        assertThat(readContent(r)).contains("2|Name\tScore");
        assertThat(readContent(r)).contains("3|Alice\t42");
        assertThat(r.content()).doesNotContain("Secret");
    }

    @Test
    void extractsPptxSlideTextBeforeBinaryGuard(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deck.pptx");
        writeZip(file, Map.of(
            "ppt/slides/slide2.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree><p:sp><p:txBody>
                    <a:p><a:r><a:t>Second slide</a:t></a:r></a:p>
                  </p:txBody></p:sp></p:spTree></p:cSld>
                </p:sld>
                """,
            "ppt/slides/slide1.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree><p:sp><p:txBody>
                    <a:p><a:r><a:t>Deck title</a:t></a:r></a:p>
                    <a:p><a:r><a:t>First bullet</a:t></a:r></a:p>
                  </p:txBody></p:sp></p:spTree></p:cSld>
                </p:sld>
                """
        ));

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("1|# -- Slide 1 --");
        assertThat(r.content()).contains("2|Deck title");
        assertThat(r.content()).contains("3|First bullet");
        assertThat(r.content()).contains("5|# -- Slide 2 --");
        assertThat(r.content()).contains("6|Second slide");
        assertThat(r.content()).doesNotContain("Cannot read binary file");
    }

    @Test
    void extractsPdfTextBeforeBinaryGuardLikeHermes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.pdf");
        Files.write(file, samplePdf("Hello from PDF"));

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":10}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("1|Hello from PDF");
        assertThat(r.content()).doesNotContain("Cannot read binary file");
    }

    // ── Device path blocking ──────────────────────────────────────────

    @Test
    void blocksDevZero() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/zero\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
        assertThat(r.error()).contains("/dev/zero");
    }

    @Test
    void blocksDevRandom() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/random\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    @Test
    void blocksDevUrandom() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/urandom\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    @Test
    void blocksDevNull() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/null\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    @Test
    void blocksDevStdin() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/stdin\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    @Test
    void blocksDevTty() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/tty\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    @Test
    void blocksProcEnviron() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/proc/self/environ\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    @Test
    void blocksProcMaps() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/proc/123/maps\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    @Test
    void blocksProcFdAliases() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/proc/self/fd/0\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    // ── Char cap truncation ────────────────────────────────────────────

    @Test
    void truncatesLargeFileAtCharCap(@TempDir Path dir) throws Exception {
        // Each line is ~20 chars ("12345|padding...\n"). Need > 100000 chars total.
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            content.append("padding-padding-padding-line-").append(i).append("\n");
        }
        Files.writeString(dir.resolve("big.txt"), content.toString());
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir.resolve("big.txt")) + "\",\"offset\":1,\"limit\":10000}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content().length()).isGreaterThan(100_000);
        assertThat(r.content()).contains("Use offset=");
        assertThat(r.content()).contains("to continue reading.");
        // The truncation marker should be near the end
        int markerIndex = r.content().indexOf("Use offset=");
        assertThat(markerIndex).isGreaterThan(99_000);
    }

    @Test
    void charCapNextOffsetContinuesAfterLastDisplayedLine(@TempDir Path dir) throws Exception {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 5000; i++) {
            content.append("line-").append(i).append("-").append("x".repeat(80)).append("\n");
        }
        Path file = dir.resolve("big.txt");
        Files.writeString(file, content.toString());

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":1,\"limit\":5000}",
            null, session);

        assertThat(r.success()).isTrue();
        JsonNode json = jsonContent(r);
        String[] lines = json.path("content").asText().split("\\R");
        String lastDisplayed = lines[lines.length - 1];
        int lastLineNumber = Integer.parseInt(lastDisplayed.substring(0, lastDisplayed.indexOf('|')));
        int nextOffset = json.path("next_offset").asInt();

        assertThat(nextOffset).isEqualTo(lastLineNumber + 1);
    }

    @Test
    void doesNotTruncateSmallFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("small.txt"), "hello world\nshort file");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir.resolve("small.txt")) + "\",\"offset\":1,\"limit\":100}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).doesNotContain("[... file truncated");
    }

    @Test
    void emptyFileNamesDeadEndLikeHermes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.txt");
        Files.writeString(file, "");
        ReadFileTool tool = newTool();

        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(file) + "\"}", null, session);

        assertThat(r.success()).isTrue();
        JsonNode json = jsonContent(r);
        assertThat(json.path("content").asText()).isEmpty();
        assertThat(json.path("hint").asText()).contains("File is empty");
    }

    @Test
    void offsetPastEofNamesRecoveryLikeHermes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lines.txt");
        Files.writeString(file, "a\nb\nc\n");
        ReadFileTool tool = newTool();

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"offset\":900,\"limit\":50}",
            null, session);

        assertThat(r.success()).isTrue();
        JsonNode json = jsonContent(r);
        assertThat(json.path("content").asText()).isEmpty();
        assertThat(json.path("hint").asText()).contains("beyond the end");
        assertThat(json.path("hint").asText()).contains("3 lines total");
    }

    // ── Nonexistent / not-a-file ───────────────────────────────────────

    @Test
    void fileNotFoundReturnsError() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/tmp/nonexistent_file_12345.txt\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("File not found");
    }

    @Test
    void directoryReturnsNotAFileError(@TempDir Path dir) {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir) + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Not a file");
    }

    @Test
    void missingPathReturnsError() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("path is required");
    }

    private static void writeZip(Path path, Map<String, String> entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static byte[] samplePdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(text);
                contentStream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
