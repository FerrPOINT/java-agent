package com.azhukov.agent.tools;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;

import java.util.Set;

public interface ToolHandler {

    ToolResult execute(String arguments, Message lastAssistant, Session session);

    ObjectMapper TOOL_ARGS_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
        .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
        .setVisibility(new VisibilityChecker.Std(JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY));

    static <T> T parseJson(String arguments, Class<T> type) {
        try {
            return TOOL_ARGS_MAPPER.readValue(arguments, type);
        } catch (Exception e) {
            String repaired = escapeBackslashesInPathLikeValues(arguments);
            if (!repaired.equals(arguments)) {
                try {
                    return TOOL_ARGS_MAPPER.readValue(repaired, type);
                } catch (Exception repairedError) {
                    e.addSuppressed(repairedError);
                }
            }
            throw new IllegalArgumentException("Invalid tool arguments: " + e.getMessage(), e);
        }
    }

    Set<String> PATH_LIKE_FIELDS = Set.of(
        "path",
        "directory",
        "dir",
        "file",
        "filepath",
        "inputpath",
        "outputpath",
        "oldpath",
        "newpath",
        "workdir"
    );

    private static String escapeBackslashesInPathLikeValues(String arguments) {
        if (arguments == null || arguments.indexOf('\\') < 0) {
            return arguments;
        }
        StringBuilder out = new StringBuilder(arguments.length());
        int index = 0;
        while (index < arguments.length()) {
            char ch = arguments.charAt(index);
            if (ch != '"') {
                out.append(ch);
                index++;
                continue;
            }

            int keyEnd = findStringEnd(arguments, index + 1);
            if (keyEnd < 0) {
                return arguments;
            }
            String keyContent = arguments.substring(index + 1, keyEnd);
            int afterKey = skipWhitespace(arguments, keyEnd + 1);
            if (afterKey >= arguments.length() || arguments.charAt(afterKey) != ':') {
                out.append(arguments, index, keyEnd + 1);
                index = keyEnd + 1;
                continue;
            }

            out.append(arguments, index, keyEnd + 1);
            out.append(arguments, keyEnd + 1, afterKey + 1);
            int valueStart = skipWhitespace(arguments, afterKey + 1);
            out.append(arguments, afterKey + 1, valueStart);
            if (valueStart >= arguments.length() || arguments.charAt(valueStart) != '"') {
                index = valueStart;
                continue;
            }

            int valueEnd = findStringEnd(arguments, valueStart + 1);
            if (valueEnd < 0) {
                return arguments;
            }
            out.append('"');
            String valueContent = arguments.substring(valueStart + 1, valueEnd);
            if (isPathLikeField(keyContent)) {
                out.append(valueContent.replace("\\", "\\\\"));
            } else {
                out.append(valueContent);
            }
            out.append('"');
            index = valueEnd + 1;
        }
        return out.toString();
    }

    private static boolean isPathLikeField(String key) {
        String normalized = key.replace("_", "")
            .replace("-", "")
            .toLowerCase(java.util.Locale.ROOT);
        return PATH_LIKE_FIELDS.contains(normalized);
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int findStringEnd(String text, int start) {
        int backslashes = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\') {
                backslashes++;
                continue;
            }
            if (ch == '"' && backslashes % 2 == 0) {
                return i;
            }
            backslashes = 0;
        }
        return -1;
    }
}
