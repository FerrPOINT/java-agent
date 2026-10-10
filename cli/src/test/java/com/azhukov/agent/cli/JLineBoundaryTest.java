package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises JLine input and CLI completion without a system terminal or user history. */
@Timeout(5)
class JLineBoundaryTest {

    @TempDir
    Path dir;

    private SlashCompleter completer;

    @BeforeEach
    void configureCompleter() {
        CliState state = new CliState();
        SlashCommandRegistry registry = new SlashCommandRegistry(
            state, new SessionStore(new ObjectMapper(), dir.resolve("sessions.json")),
            new DestructiveCommandConfirmation(), List.of(new UtilityCommands(state)));
        completer = new SlashCompleter(registry);
    }

    @Test
    void streamReaderReadsUtf8LinesThenReportsEof() throws Exception {
        try (Terminal terminal = streamTerminal("/help\ncafé\n")) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).completer(completer).build();
            reader.setOpt(LineReader.Option.BRACKETED_PASTE);

            assertThat(reader.readLine("agent> ")).isEqualTo("/help");
            assertThat(reader.readLine("agent> ")).isEqualTo("café");
            assertThatThrownBy(() -> reader.readLine("agent> ")).isInstanceOf(EndOfFileException.class);
        }
    }

    @Test
    void realParserAndCompleterProvideSlashCommandsAndFileReferences() throws Exception {
        try (Terminal terminal = streamTerminal("")) {
            DefaultParser parser = new DefaultParser();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).parser(parser).completer(completer).build();

            assertThat(complete(reader, parser, "/he")).extracting(Candidate::value).containsExactly("/help");
            assertThat(complete(reader, parser, "@fi")).extracting(Candidate::value).containsExactly("@file:");
            assertThat(complete(reader, parser, "/unknown-command")).isEmpty();
        }
    }

    @Test
    void realFileCompleterFindsFileInTemporaryDirectory() throws Exception {
        Files.writeString(dir.resolve("completion.txt"), "fixture", StandardCharsets.UTF_8);
        Path relativeDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().relativize(dir.toAbsolutePath());
        String prefix = "./" + relativeDir.toString().replace('\\', '/') + "/";

        try (Terminal terminal = streamTerminal("")) {
            DefaultParser parser = new DefaultParser();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).parser(parser).completer(completer).build();
            reader.setOpt(LineReader.Option.USE_FORWARD_SLASH);

            List<Candidate> candidates = complete(reader, parser, "\"" + prefix + "comp");

            assertThat(candidates).extracting(Candidate::value).contains(prefix + "completion.txt");
            assertThat(candidates).anyMatch(candidate ->
                candidate.value().equals(prefix + "completion.txt") && candidate.complete());
            assertThat(Files.readString(dir.resolve("completion.txt"))).isEqualTo("fixture");
        }
    }

    private List<Candidate> complete(LineReader reader, DefaultParser parser, String input) {
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(reader, parser.parse(input, input.length(), Parser.ParseContext.COMPLETE), candidates);
        return candidates;
    }

    private Terminal streamTerminal(String input) throws IOException {
        return new DumbTerminal("fixture", Terminal.TYPE_DUMB,
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            new ByteArrayOutputStream(), StandardCharsets.UTF_8);
    }
}
