package com.azhukov.agent.cli;

import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Session;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCliRunnerErrorTest {

    @Test
    void runLoopHandlesReaderException() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        LineReader reader = new FailingLineReader(new RuntimeException("read error"));

        List<String> output = new ArrayList<>();
        AgentCliRunner runner = new AgentCliRunner(runtime);
        runner.runLoop(reader, Session.create("u", "noop", ""), output::add);

        assertThat(output).anyMatch(s -> s.contains("Error"));
        assertThat(output).anyMatch(s -> s.contains("read error"));
    }

    @Test
    void runLoopHandlesTurnException() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.runTurn(any(Session.class), any())).thenThrow(new RuntimeException("boom"));
        LineReader reader = new FixedLineReader("hello", "exit");

        List<String> output = new ArrayList<>();
        AgentCliRunner runner = new AgentCliRunner(runtime);
        runner.runLoop(reader, Session.create("u", "noop", ""), output::add);

        assertThat(output).anyMatch(s -> s.contains("boom"));
    }

    static class FailingLineReader implements LineReader {
        private final RuntimeException exception;
        private boolean thrown = false;

        FailingLineReader(RuntimeException exception) {
            this.exception = exception;
        }

        private String read() {
            if (!thrown) {
                thrown = true;
                throw exception;
            }
            return null;
        }

        @Override public String readLine() { return read(); }
        @Override public String readLine(Character mask) { return read(); }
        @Override public String readLine(String prompt) { return read(); }
        @Override public String readLine(String prompt, Character mask) { return read(); }
        @Override public String readLine(String prompt, Character mask, String buffer) { return read(); }
        @Override public String readLine(String prompt, String rightPrompt, Character mask, String buffer) { return read(); }
        @Override public String readLine(String prompt, String rightPrompt, MaskingCallback maskingCallback, String buffer) { return read(); }
        @Override public void printAbove(String s) { }
        @Override public void printAbove(AttributedString s) { }
        @Override public boolean isReading() { return false; }
        @Override public LineReader variable(String name, Object value) { return this; }
        @Override public LineReader option(Option option, boolean value) { return this; }
        @Override public void callWidget(String name) { }
        @Override public Map<String, Object> getVariables() { return Map.of(); }
        @Override public Object getVariable(String name) { return null; }
        @Override public void setVariable(String name, Object value) { }
        @Override public boolean isSet(Option option) { return false; }
        @Override public void setOpt(Option option) { }
        @Override public void unsetOpt(Option option) { }
        @Override public Terminal getTerminal() { return null; }
        @Override public Map<String, Widget> getWidgets() { return Map.of(); }
        @Override public Map<String, Widget> getBuiltinWidgets() { return Map.of(); }
        @Override public Buffer getBuffer() { return null; }
        @Override public String getAppName() { return "test"; }
        @Override public void runMacro(String macro) { }
        @Override public org.jline.terminal.MouseEvent readMouseEvent() { return null; }
        @Override public History getHistory() { return null; }
        @Override public Parser getParser() { return null; }
        @Override public Highlighter getHighlighter() { return null; }
        @Override public Expander getExpander() { return null; }
        @Override public Map<String, org.jline.keymap.KeyMap<Binding>> getKeyMaps() { return Map.of(); }
        @Override public Map<String, org.jline.keymap.KeyMap<Binding>> defaultKeyMaps() { return Map.of(); }
        @Override public String getKeyMap() { return ""; }
        @Override public boolean setKeyMap(String name) { return false; }
        @Override public org.jline.keymap.KeyMap<Binding> getKeys() { return null; }
        @Override public ParsedLine getParsedLine() { return null; }
        @Override public String getSearchTerm() { return ""; }
        @Override public LineReader.RegionType getRegionActive() { return null; }
        @Override public int getRegionMark() { return 0; }
        @Override public void addCommandsInBuffer(java.util.Collection<String> commands) { }
        @Override public void editAndAddInBuffer(java.nio.file.Path file) { }
        @Override public String getLastBinding() { return ""; }
        @Override public String getTailTip() { return ""; }
        @Override public void setTailTip(String tailTip) { }
        @Override public void setAutosuggestion(SuggestionType type) { }
        @Override public SuggestionType getAutosuggestion() { return null; }
        @Override public void zeroOut() { }
    }

    static class FixedLineReader implements LineReader {
        private String nextLine() { return lines.hasNext() ? lines.next() : null; }

        private final java.util.Iterator<String> lines;

        FixedLineReader(String... lines) {
            this.lines = java.util.List.of(lines).iterator();
        }

        @Override public String readLine() { return nextLine(); }
        @Override public String readLine(Character mask) { return lines.hasNext() ? lines.next() : null; }
        @Override public String readLine(String prompt) { return nextLine(); }
        @Override public String readLine(String prompt, Character mask) { return nextLine(); }
        @Override public String readLine(String prompt, Character mask, String buffer) { return nextLine(); }
        @Override public String readLine(String prompt, String rightPrompt, Character mask, String buffer) { return nextLine(); }
        @Override public String readLine(String prompt, String rightPrompt, MaskingCallback maskingCallback, String buffer) { return nextLine(); }
        @Override public void printAbove(String s) { }
        @Override public void printAbove(AttributedString s) { }
        @Override public boolean isReading() { return false; }
        @Override public LineReader variable(String name, Object value) { return this; }
        @Override public LineReader option(Option option, boolean value) { return this; }
        @Override public void callWidget(String name) { }
        @Override public Map<String, Object> getVariables() { return Map.of(); }
        @Override public Object getVariable(String name) { return null; }
        @Override public void setVariable(String name, Object value) { }
        @Override public boolean isSet(Option option) { return false; }
        @Override public void setOpt(Option option) { }
        @Override public void unsetOpt(Option option) { }
        @Override public Terminal getTerminal() { return null; }
        @Override public Map<String, Widget> getWidgets() { return Map.of(); }
        @Override public Map<String, Widget> getBuiltinWidgets() { return Map.of(); }
        @Override public Buffer getBuffer() { return null; }
        @Override public String getAppName() { return "test"; }
        @Override public void runMacro(String macro) { }
        @Override public org.jline.terminal.MouseEvent readMouseEvent() { return null; }
        @Override public History getHistory() { return null; }
        @Override public Parser getParser() { return null; }
        @Override public Highlighter getHighlighter() { return null; }
        @Override public Expander getExpander() { return null; }
        @Override public Map<String, org.jline.keymap.KeyMap<Binding>> getKeyMaps() { return Map.of(); }
        @Override public Map<String, org.jline.keymap.KeyMap<Binding>> defaultKeyMaps() { return Map.of(); }
        @Override public String getKeyMap() { return ""; }
        @Override public boolean setKeyMap(String name) { return false; }
        @Override public org.jline.keymap.KeyMap<Binding> getKeys() { return null; }
        @Override public ParsedLine getParsedLine() { return null; }
        @Override public String getSearchTerm() { return ""; }
        @Override public LineReader.RegionType getRegionActive() { return null; }
        @Override public int getRegionMark() { return 0; }
        @Override public void addCommandsInBuffer(java.util.Collection<String> commands) { }
        @Override public void editAndAddInBuffer(java.nio.file.Path file) { }
        @Override public String getLastBinding() { return ""; }
        @Override public String getTailTip() { return ""; }
        @Override public void setTailTip(String tailTip) { }
        @Override public void setAutosuggestion(SuggestionType type) { }
        @Override public SuggestionType getAutosuggestion() { return null; }
        @Override public void zeroOut() { }
    }
}
