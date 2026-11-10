package com.azhukov.agent.shared.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Reads the agent chat stream. HTTP owns the idle read timeout. */
public final class ChatEventStream {
    private ChatEventStream() {}

    public static void read(InputStream body, ObjectMapper mapper, Consumer<JsonNode> onEvent)
            throws IOException {
        if (body == null) {
            throw new IllegalStateException("Backend stream returned no body");
        }
        try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Backend stream cancelled");
                }
                String line = reader.readLine();
                if (line == null) {
                    throw new EOFException("Backend stream closed before the done event");
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    return;
                }
                JsonNode event;
                try {
                    event = mapper.readTree(data);
                } catch (JsonProcessingException ignored) {
                    // Ignore malformed records, but still require an explicit terminal event.
                    continue;
                }
                if (event == null || !event.isObject()) {
                    continue;
                }
                String type = event.path("type").asText();
                if ("error".equalsIgnoreCase(type)) {
                    throw new IOException(event.path("error").asText(
                        event.path("message").asText("Unknown stream error")));
                }
                onEvent.accept(event);
                if ("done".equalsIgnoreCase(type)) {
                    return;
                }
            }
        }
    }
}
