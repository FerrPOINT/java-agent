package com.azhukov.agent.api;

import com.azhukov.agent.service.tts.SpokenTextNormalizer;
import com.azhukov.agent.service.tts.TtsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AudioSpeakStreamWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUFFER_ATTRIBUTE = AudioSpeakStreamWebSocketHandler.class.getName() + ".text";
    private static final int DEFAULT_MAX_TEXT_CHARS = 4_000;
    private static final TextMessage START_MESSAGE = jsonMessage(Map.of(
        "type", "start",
        "sample_rate", 24_000,
        "channels", 1,
        "mime_type", "audio/mpeg",
        "encoding", "mp3"));
    private static final TextMessage FALLBACK_MESSAGE = new TextMessage("{\"type\":\"fallback\"}");

    private final TtsService ttsService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!session.isOpen()) {
            return;
        }
        if (ttsService == null || !ttsService.isAvailable()) {
            session.sendMessage(FALLBACK_MESSAGE);
            session.close(CloseStatus.NORMAL);
            return;
        }
        session.getAttributes().put(BUFFER_ATTRIBUTE, new StringBuilder());
        session.sendMessage(START_MESSAGE);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!session.isOpen()) {
            return;
        }

        JsonNode frame;
        try {
            frame = MAPPER.readTree(message.getPayload());
        } catch (Exception e) {
            sendError(session, "Invalid JSON in speak-stream frame", CloseStatus.BAD_DATA);
            return;
        }
        if (frame == null || !frame.isObject()) {
            sendError(session, "speak-stream frame must be a JSON object", CloseStatus.BAD_DATA);
            return;
        }
        if (frame.path("stop").asBoolean(false)) {
            session.close(CloseStatus.NORMAL);
            return;
        }

        JsonNode textNode = frame.get("text");
        if (textNode != null && !textNode.isNull()) {
            buffer(session).append(textNode.asText());
        }

        if (!frame.path("done").asBoolean(false)) {
            return;
        }

        String text = buffer(session).toString();
        String voice = textValue(frame.get("voice"));
        String cleaned = SpokenTextNormalizer.normalize(text);
        if (cleaned.isBlank()) {
            session.sendMessage(jsonMessage(Map.of("type", "end")));
            session.close(CloseStatus.NORMAL);
            return;
        }

        try {
            for (String piece : splitTextForSpeakStream(cleaned, DEFAULT_MAX_TEXT_CHARS)) {
                byte[] audio = ttsService.synthesize(piece, voice);
                if (audio == null || audio.length == 0) {
                    throw new IllegalStateException("Speech synthesis returned empty audio");
                }
                if (!session.isOpen()) {
                    return;
                }
                session.sendMessage(new BinaryMessage(audio));
            }
            if (session.isOpen()) {
                session.sendMessage(jsonMessage(Map.of("type", "end")));
                session.close(CloseStatus.NORMAL);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            sendError(session, e.getMessage(), CloseStatus.NORMAL);
        } catch (RuntimeException e) {
            sendError(session, "Speech synthesis failed: " + errorMessage(e), CloseStatus.SERVER_ERROR);
        }
    }

    static List<String> splitTextForSpeakStream(String text, int maxChars) {
        int cap = maxChars > 0 ? maxChars : DEFAULT_MAX_TEXT_CHARS;
        String normalized = String.join(" ", text.split("\\s+")).trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.length() <= cap) {
            return List.of(normalized);
        }

        String[] sentences = normalized.split("(?<=[.!?;:,])\\s+");
        List<String> expanded = new ArrayList<>();
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            while (trimmed.length() > cap) {
                expanded.add(trimmed.substring(0, cap));
                trimmed = trimmed.substring(cap);
            }
            if (!trimmed.isEmpty()) {
                expanded.add(trimmed);
            }
        }

        List<String> chunks = new ArrayList<>();
        String current = "";
        for (String sentence : expanded) {
            String candidate = current.isEmpty() ? sentence : current + " " + sentence;
            if (!current.isEmpty() && candidate.length() > cap) {
                chunks.add(current);
                current = sentence;
            } else {
                current = candidate;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static StringBuilder buffer(WebSocketSession session) {
        Object existing = session.getAttributes().get(BUFFER_ATTRIBUTE);
        if (existing instanceof StringBuilder builder) {
            return builder;
        }
        StringBuilder builder = new StringBuilder();
        session.getAttributes().put(BUFFER_ATTRIBUTE, builder);
        return builder;
    }

    private static void sendError(WebSocketSession session, String message, CloseStatus status) throws Exception {
        if (!session.isOpen()) {
            return;
        }
        String error = message == null || message.isBlank() ? "Speech synthesis failed" : message;
        session.sendMessage(jsonMessage(Map.of("type", "error", "error", error)));
        if (session.isOpen()) {
            session.close(status);
        }
    }

    private static TextMessage jsonMessage(Map<String, Object> payload) {
        try {
            return new TextMessage(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            return new TextMessage("{\"type\":\"error\",\"error\":\"Failed to encode websocket frame\"}");
        }
    }

    private static String errorMessage(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank()
            ? e.getClass().getSimpleName()
            : e.getMessage();
    }
}
