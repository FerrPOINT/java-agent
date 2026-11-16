package com.azhukov.agent.api;

import com.azhukov.agent.core.model.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/** Request tool names select canonical server definitions; they never define executable capabilities. */
final class OpenAiToolSelection {
    private OpenAiToolSelection() {}

    static List<ToolDefinition> select(List<ToolDefinition> canonical, List<String> requested) {
        if (requested == null) return canonical;
        var enabled = canonical.stream().map(ToolDefinition::name).collect(Collectors.toSet());
        var selected = new LinkedHashSet<String>();
        for (String value : requested) {
            if (value == null || value.isBlank() || !enabled.contains(value.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Requested tool is not enabled in the server registry");
            }
            selected.add(value.trim());
        }
        return canonical.stream().filter(tool -> selected.contains(tool.name())).toList();
    }
}
