package com.azhukov.agent.bot.keyboard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks pending exec-approval and slash-confirm requests by their integer ID.
 * <p>
 * Mirrors the original project's {@code _approval_state} and {@code _slash_confirm_state} dicts.
 * When an inline approval/confirm message is sent, a unique ID is assigned and
 * the session key is stored. When the user clicks a button, the ID is looked up
 * and consumed (popped) — subsequent clicks on the same message get a
 * "already resolved" answer.
 */
@Component
@Slf4j
public class ApprovalStateStore {

 private final AtomicInteger nextId = new AtomicInteger(1);
 private final Map<Integer, String> execApprovalState = new ConcurrentHashMap<>();
 private final Map<String, String> slashConfirmState = new ConcurrentHashMap<>();

 // ── Exec approval (ea:choice:id) ──

 /**
 * Register a pending exec approval and return the integer ID to embed in callback_data.
 *
 * @param sessionKey the session key to resolve when the user clicks a button
 * @return unique integer ID for this approval
 */
 public int registerExecApproval(String sessionKey) {
 int id = nextId.getAndIncrement();
 execApprovalState.put(id, sessionKey);
 return id;
 }

 /**
 * Pop (consume) a pending exec approval by ID.
 *
 * @param approvalId the ID from the callback_data
 * @return the session key, or {@code null} if already resolved or unknown
 */
 public String popExecApproval(int approvalId) {
 return execApprovalState.remove(approvalId);
 }

 // ── Slash confirm (sc:choice:confirmId) ──

 /**
 * Register a pending slash-confirm prompt.
 *
 * @param confirmId the string ID for this confirm prompt
 * @param sessionKey the session key to resolve when the user clicks a button
 */
 public void registerSlashConfirm(String confirmId, String sessionKey) {
 slashConfirmState.put(confirmId, sessionKey);
 }

 /**
 * Pop (consume) a pending slash-confirm by ID.
 *
 * @param confirmId the string ID from the callback_data
 * @return the session key, or {@code null} if already resolved or unknown
 */
 public String popSlashConfirm(String confirmId) {
 return slashConfirmState.remove(confirmId);
 }
}