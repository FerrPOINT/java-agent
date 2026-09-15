package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.client.TelegramClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WP-b (Hermes run-turn recovery parity): sessions whose streaming turn was
 * interrupted by a bot restart carry resume_pending=true (set by
 * StreamingOrchestrator before the first backend call, cleared on normal
 * completion). Rows still flagged when the process comes back up lost their
 * turn to the restart — the user gets an honest notice instead of silence.
 *
 * The backend transcript is NOT rolled back: the next message resumes the
 * same session, exactly like Hermes' resume-pending flow.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterruptedSessionNotifier {

    private final BotSessionRepository sessionRepository;
    private final TelegramClient telegramClient;

    @EventListener(ApplicationReadyEvent.class)
    public void notifyInterruptedSessions() {
        List<BotSessionEntity> interrupted;
        try {
            interrupted = sessionRepository.findByResumePendingTrueAndActiveTrue();
        } catch (Exception e) {
            log.warn("Could not query interrupted sessions on startup: {}", e.getMessage());
            return;
        }
        if (interrupted.isEmpty()) {
            log.debug("No resume-pending sessions after restart");
            return;
        }
        log.info("Recovery: {} session(s) had a turn interrupted by the restart", interrupted.size());
        for (BotSessionEntity session : interrupted) {
            long chatId;
            try {
                chatId = Long.parseLong(session.getChatId());
            } catch (NumberFormatException e) {
                log.warn("Recovery: session {} has unparseable chatId '{}', skipping notice",
                    session.getId(), session.getChatId());
                continue;
            }
            boolean notified = false;
            try {
                telegramClient.sendMessage(chatId,
                    "⚠️ Бот был перезапущен во время обработки твоего последнего сообщения — "
                    + "ответ не был доставлен. Контекст сессии сохранён: отправь сообщение ещё раз, "
                    + "и я продолжу с того же места.",
                    null, null,
                    session.getThreadId() == null ? null : session.getThreadId().intValue(),
                    false);
                notified = true;
            } catch (Exception sendEx) {
                log.warn("Recovery: could not deliver interruption notice to chat {}: {}",
                    chatId, sendEx.getMessage());
            }
            // Clear the flag either way — a persistently undeliverable chat must not
            // re-notify on every restart forever.
            session.setResumePending(false);
            try {
                sessionRepository.save(session);
            } catch (Exception saveEx) {
                log.warn("Recovery: could not clear resume_pending for session {}: {}",
                    session.getId(), saveEx.getMessage());
            }
            log.info("Recovery: session {} chat {} notified={}", session.getId(), chatId, notified);
        }
    }
}
