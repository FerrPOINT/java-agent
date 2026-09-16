package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.client.TelegramClient;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Wires {@link SessionRecoveryService} into the bot lifecycle (docs/34 gap 9).
 *
 * <p>Startup: recover resume-pending sessions and send each chat the one-time
 * continuation notice. Shutdown: mark active sessions resume-pending so the
 * next boot can complete the handshake. The notice is best-effort — Telegram
 * unavailability at boot must never crash the application.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionRecoveryLifecycle implements ApplicationRunner {

    private final SessionRecoveryService recoveryService;
    private final TelegramClient telegramClient;

    @Override
    public void run(ApplicationArguments args) {
        var recovered = recoveryService.recoverPendingSessions();
        for (SessionRecoveryService.RecoveredSession session : recovered) {
            try {
                long chatId = Long.parseLong(session.chatId());
                // DM-topics depth: route the notice into the persisted topic
                // the user was talking in (0 = plain send, no thread routing).
                Integer threadId = session.messageThreadId() > 0
                        ? (int) session.messageThreadId() : null;
                Optional<Long> sent = telegramClient.sendMessage(
                        chatId, SessionRecoveryService.recoveryNotice(),
                        null, null, threadId, false);
                if (sent.isEmpty()) {
                    log.warn("recovery notice not delivered to chat {} (send failed)", session.chatId());
                }
            } catch (NumberFormatException e) {
                log.warn("recovery notice skipped: non-numeric chat id {}", session.chatId());
            } catch (Exception e) {
                log.warn("recovery notice failed for chat {}: {}", session.chatId(), e.getMessage());
            }
        }
        if (!recovered.isEmpty()) {
            log.info("session recovery: notified {} chat(s) after restart", recovered.size());
        }
    }

    @PreDestroy
    public void onShutdown() {
        try {
            recoveryService.markInterruptedOnShutdown();
        } catch (Exception e) {
            log.warn("shutdown session marking failed: {}", e.getMessage());
        }
    }
}
