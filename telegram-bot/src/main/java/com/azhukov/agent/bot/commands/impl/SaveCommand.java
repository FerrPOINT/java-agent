package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Hermes parity (/save): export the current session and send it as a
 * document. Usage: /save [md|json] [filename].
 *
 * <p>md renders a readable transcript (role-prefixed messages in order);
 * json dumps the raw history payload from the backend.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SaveCommand implements CommandHandler {

    private static final String USAGE =
        "Usage: /save [md|json] [filename]";

    private final AgentBackendClient backendClient;
    private final TelegramClient telegramClient;

    @Override
    public String name() {
        return "save";
    }

    @Override
    public String description() {
        return "Export this session as a document (md or json)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        java.util.UUID backend = session.getBackendSessionId();
        if (backend == null) {
            return "Nothing to save yet — send a message first.";
        }
        String argsText = event.commandArgs() == null ? "" : event.commandArgs().trim();
        String[] parts = argsText.isEmpty() ? new String[0] : argsText.split("\\s+");
        String fmt = parts.length > 0 ? parts[0].toLowerCase() : "md";   // default md (Hermes /save)
        if (!fmt.equals("md") && !fmt.equals("json")) {
            return USAGE;
        }
        JsonNode history = backendClient.suggestionGet(
            "/api/v1/agent/session/" + backend + "/history");
        if (history == null || !history.isArray() || history.isEmpty()) {
            return "Nothing to save — the conversation is empty.";
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        String filename;
        byte[] payload;
        if (fmt.equals("json")) {
            payload = history.toString().getBytes(StandardCharsets.UTF_8);
            filename = parts.length > 1 ? parts[1] : "session-" + stamp + ".json";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("# Session export — ").append(backend).append("\n\n");
            for (JsonNode m : history) {
                String role = m.path("role").asText("?");
                String content = m.path("content").asText("");
                sb.append("## ").append(role).append("\n\n")
                  .append(content.isBlank() ? "(empty)" : content).append("\n\n");
            }
            payload = sb.toString().getBytes(StandardCharsets.UTF_8);
            filename = parts.length > 1 ? parts[1] : "session-" + stamp + ".md";
        }

        var sent = telegramClient.sendDocument(event.chatId(), payload, filename,
            "Session export " + backend, null);
        if (sent.isPresent()) {
            return null; // document sent — no extra text
        }
        return "Failed to send the document.";
    }
}
