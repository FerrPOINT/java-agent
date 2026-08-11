package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Handles callback_query {@link UpdateEvent}s from inline keyboard presses.
 * Parses callbackData in the format "command:value", routes to the
 * appropriate action, and answers the callback query via TelegramClient.
 * <p>
 * B4: Before executing any callback action, checks
 * {@link AuthorizationService#isAuthorized(long, String, long)}. Unauthorized
 * users receive a "Not authorized" answer and the attempt is logged at WARN.
 * <p>
 * Supported callbacks:
 * <ul>
 *   <li>{@code mp:<model>} — set model override for the session</li>
 *   <li>{@code mpp:<page>} — show model keyboard page N</li>
 *   <li>{@code pp:<slug>} — show models for the selected provider</li>
 *   <li>{@code pp:back} — go back to provider list</li>
 *   <li>{@code ea:<choice>:<id>} — exec approval: once, session, always, deny</li>
 *   <li>{@code sc:<choice>:<confirmId>} — slash-confirm: once, always, cancel</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallbackQueryHandler {

    private final TelegramClient telegramClient;
    private final ProviderKeyboardBuilder providerKeyboardBuilder;
    private final ModelKeyboardBuilder modelKeyboardBuilder;
    private final InlineKeyboardBuilder inlineKeyboardBuilder;
    private final BotSessionStore sessionStore;
    private final BotProperties properties;
    private final AuthorizationService authorizationService;
    private final AgentBackendClient backendClient;
    private final ApprovalStateStore approvalStateStore;

    /**
     * Handles a callback_query UpdateEvent.
     *
     * @param event the callback_query UpdateEvent
     * @return a response string, or null if not a callback query
     */
    public String handle(UpdateEvent event) {
        if (event == null || event.type() != UpdateEvent.Type.CALLBACK_QUERY) {
            return null;
        }

        String callbackQueryId = event.callbackQueryId();
        String callbackData = event.callbackData();
        long chatId = event.chatId();

        if (callbackData == null || callbackData.isBlank()) {
            answer(callbackQueryId, "Unknown action", false);
            return null;
        }

        // B4: Authorization check — verify the user who clicked the button is authorized
        if (!authorizationService.isAuthorized(event.userId(), event.username(), chatId)) {
            log.warn("Unauthorized callback attempt: userId={}, username={}, chatId={}, data={}",
                event.userId(), event.username(), chatId, callbackData);
            answer(callbackQueryId, "Not authorized", true);
            return null;
        }

        log.debug("Handling callback: id={}, data={}, chatId={}", callbackQueryId, callbackData, chatId);

        String command;
        String value;
        int colonIdx = callbackData.indexOf(':');
        if (colonIdx >= 0) {
            command = callbackData.substring(0, colonIdx);
            value = callbackData.substring(colonIdx + 1);
        } else {
            command = callbackData;
            value = "";
        }

        String response = route(command, value, chatId, event.userId(), event.messageId());

        String answerText = response != null ? response : "OK";
        answer(callbackQueryId, answerText, false);

        return response;
    }

    private String route(String command, String value, long chatId, long userId, long messageId) {
        if (command == null || command.isBlank()) {
            return "Unknown command";
        }

        return switch (command) {
            case "mp" -> handleModelSelect(value, chatId, userId);
            case "mpp" -> handleModelPage(value, chatId);
            case "pp" -> handleProviderSelect(value, chatId);
            case "ea" -> handleExecApproval(value, chatId, messageId);
            case "sc" -> handleSlashConfirm(value, chatId);
            default -> "Unknown action: " + command;
        };
    }

    /**
     * mp:<model> — set the model override for the user's session.
     */
    private String handleModelSelect(String model, long chatId, long userId) {
        if (model == null || model.isBlank()) {
            return "No model specified";
        }
        BotSessionEntity session = sessionStore.resolveOrCreate(String.valueOf(userId), String.valueOf(chatId), "");
        if (session == null || session.getId() == null) {
            return "No active session";
        }
        sessionStore.setModelOverride(session.getId(), model);
        return "Model set to: " + model;
    }

    /**
     * mpp:<page> — show model keyboard for the given page.
     */
    private String handleModelPage(String pageStr, long chatId) {
        int page;
        try {
            page = Integer.parseInt(pageStr);
        } catch (NumberFormatException e) {
            page = 0;
        }
        List<String> models = properties.getAvailableModels();
        if (models == null || models.isEmpty()) {
            return "No models configured";
        }
        var rows = modelKeyboardBuilder.build(models, page);
        String markup = inlineKeyboardBuilder.build(rows);
        telegramClient.sendMessage(chatId, "Select a model (page " + (page + 1) + "):", null, null, markup);
        return null; // message already sent
    }

    /**
     * pp:<slug> — show models for the selected provider, or go back to provider list.
     * P2-19: Filters models by provider slug prefix (e.g. "openai:gpt-4" → provider "openai").
     * Falls back to showing all models if no models match the provider slug.
     */
    private String handleProviderSelect(String slug, long chatId) {
        if ("back".equals(slug)) {
            // Show provider list
            Map<String, String> providers = providerKeyboardBuilder.defaultProviders();
            var rows = providerKeyboardBuilder.buildProviders(providers);
            String markup = inlineKeyboardBuilder.build(rows);
            telegramClient.sendMessage(chatId, "Select a provider:", null, null, markup);
            return null;
        }

        // P2-19: Filter models by provider slug.
        // Models may be prefixed with "provider:" (e.g. "openai:gpt-4") or
        // matched by provider-specific name patterns.
        List<String> allModels = properties.getAvailableModels();
        if (allModels == null || allModels.isEmpty()) {
            return "No models configured for provider: " + slug;
        }

        List<String> filtered = filterModelsByProvider(allModels, slug);
        if (filtered.isEmpty()) {
            // No models match the provider filter — show all as fallback
            filtered = allModels;
        }

        var rows = providerKeyboardBuilder.buildModels(filtered);
        String markup = inlineKeyboardBuilder.build(rows);
        telegramClient.sendMessage(chatId, "Models for " + slug + ":", null, null, markup);
        return null;
    }

    /**
     * P2-19: Filter models by provider slug.
     * Matches models that are either prefixed with "slug:" or match known
     * provider-specific naming patterns.
     */
    private List<String> filterModelsByProvider(List<String> models, String slug) {
        String prefix = slug + ":";
        List<String> filtered = models.stream()
            .filter(m -> m.toLowerCase().startsWith(prefix.toLowerCase()))
            .map(m -> m.substring(prefix.length()))  // strip the prefix for display
            .toList();
        if (!filtered.isEmpty()) {
            return filtered;
        }
        // Fallback: match by provider-specific name patterns
        return switch (slug.toLowerCase()) {
            case "openai" -> models.stream()
                .filter(m -> m.toLowerCase().startsWith("gpt-") || m.toLowerCase().startsWith("o1") || m.toLowerCase().startsWith("o3") || m.toLowerCase().startsWith("o4"))
                .toList();
            case "anthropic" -> models.stream()
                .filter(m -> m.toLowerCase().startsWith("claude-"))
                .toList();
            case "google" -> models.stream()
                .filter(m -> m.toLowerCase().startsWith("gemini-") || m.toLowerCase().startsWith("gemma"))
                .toList();
            case "meta" -> models.stream()
                .filter(m -> m.toLowerCase().startsWith("llama-") || m.toLowerCase().contains("meta"))
                .toList();
            case "mistral" -> models.stream()
                .filter(m -> m.toLowerCase().startsWith("mistral-") || m.toLowerCase().startsWith("mixtral-"))
                .toList();
            case "moonshot" -> models.stream()
                .filter(m -> m.toLowerCase().startsWith("kimi-") || m.toLowerCase().contains("moonshot"))
                .toList();
            default -> List.of();
        };
    }

    private void answer(String callbackQueryId, String text, boolean showAlert) {
        if (callbackQueryId == null || callbackQueryId.isBlank()) {
            log.warn("Cannot answer callback query: missing callbackQueryId");
            return;
        }
        telegramClient.answerCallbackQuery(callbackQueryId, text, showAlert);
    }

    // ─── P1-12: Exec approval callbacks (ea:choice:id) ─────────────

    /**
     * ea:choice:id — resolve a pending exec-approval request.
     * <p>
     * The value is parsed as {@code choice:id} where:
     * <ul>
     *   <li>{@code choice} — once, session, always, deny</li>
     *   <li>{@code id} — integer approval ID from ApprovalStateStore</li>
     * </ul>
     * The session key is popped from the store (one-shot resolution).
     * The original message is edited to show the decision and remove buttons.
     */
    String handleExecApproval(String value, long chatId, long messageId) {
        if (value == null || value.isBlank()) {
            return "Invalid approval data";
        }
        // value = "choice:id" — split on the first colon
        int colonIdx = value.indexOf(':');
        if (colonIdx < 0) {
            return "Invalid approval data";
        }
        String choice = value.substring(0, colonIdx);
        String idStr = value.substring(colonIdx + 1);

        int approvalId;
        try {
            approvalId = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return "Invalid approval ID";
        }

        String sessionKey = approvalStateStore.popExecApproval(approvalId);
        if (sessionKey == null) {
            return "This approval has already been resolved.";
        }

        // Map choice to human-readable label
        Map<String, String> labelMap = Map.of(
            "once", "✅ Approved once",
            "session", "✅ Approved for session",
            "always", "✅ Approved permanently",
            "deny", "❌ Denied"
        );
        String label = labelMap.getOrDefault(choice, "Resolved");

        // Resolve the approval via backend
        String result = backendClient.resolveApproval(sessionKey, choice);
        log.info("Exec approval resolved: sessionKey={}, choice={}, result={}", sessionKey, choice, result);

        // Remove the inline keyboard from the original message
        if (messageId > 0) {
            telegramClient.editMessageReplyMarkup(chatId, messageId, null);
        }

        return label;
    }

    // ─── P1-13: Slash-confirm callbacks (sc:choice:confirmId) ──────

    /**
     * sc:choice:confirmId — resolve a pending slash-confirm prompt.
     * <p>
     * The value is parsed as {@code choice:confirmId} where:
     * <ul>
     *   <li>{@code choice} — once, always, cancel</li>
     *   <li>{@code confirmId} — string confirm ID from ApprovalStateStore</li>
     * </ul>
     * The session key is popped from the store (one-shot resolution).
     */
    String handleSlashConfirm(String value, long chatId) {
        if (value == null || value.isBlank()) {
            return "Invalid confirm data";
        }
        // value = "choice:confirmId" — split on the first colon
        int colonIdx = value.indexOf(':');
        if (colonIdx < 0) {
            return "Invalid confirm data";
        }
        String choice = value.substring(0, colonIdx);
        String confirmId = value.substring(colonIdx + 1);

        if (confirmId.isBlank()) {
            return "Invalid confirm ID";
        }

        String sessionKey = approvalStateStore.popSlashConfirm(confirmId);
        if (sessionKey == null) {
            return "This prompt has already been resolved.";
        }

        // Map choice to human-readable label
        Map<String, String> labelMap = Map.of(
            "once", "✅ Approved once",
            "always", "🔒 Always approve",
            "cancel", "❌ Cancelled"
        );
        String label = labelMap.getOrDefault(choice, "Resolved");

        // Resolve the confirm via backend
        String result = backendClient.resolveSlashConfirm(sessionKey, confirmId, choice);
        log.info("Slash-confirm resolved: sessionKey={}, confirmId={}, choice={}, result={}",
            sessionKey, confirmId, choice, result);

        return label;
    }
}