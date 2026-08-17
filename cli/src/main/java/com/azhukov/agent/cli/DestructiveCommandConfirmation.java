package com.azhukov.agent.cli;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * P1-3: Destructive command confirmation state machine.
 * <p>
 * Before executing /new, /reset, /rollback, /undo, /exit --delete, /clear,
 * the REPL prompts the user with Y/N/cancel.
 * <p>
 * Mirrors the original CLI's {@code _confirm_destructive_slash} with a simpler
 * three-option state machine: YES (once), ALWAYS (skip future confirmations),
 * CANCEL (abort).
 * <p>
 * Inline-skip tokens: {@code now}, {@code --yes}, {@code -y} bypass the
 * confirmation (same as the original project).
 * <p>
 * c16: Registered as a Spring {@code @Component} so it can be injected into
 * the registry and command group classes.
 */
@Component
public class DestructiveCommandConfirmation {

 private static final Set<String> DESTRUCTIVE_COMMANDS = Set.of(
 "new", "reset", "rollback", "undo", "clear"
 );

 private static final Set<String> SKIP_TOKENS = Set.of("now", "--yes", "-y");

 private volatile boolean confirmRequired = true;

 public enum ConfirmResult { YES, ALWAYS, CANCEL, NOT_DESTRUCTIVE }

 /**
 * Check if a command name is destructive.
 */
 public static boolean isDestructive(String commandName) {
 return DESTRUCTIVE_COMMANDS.contains(commandName);
 }

 /**
 * Check if a full command line is destructive (handles /exit --delete).
 */
 public static boolean isDestructiveLine(String fullLine) {
 if (fullLine == null || !fullLine.startsWith("/")) return false;
 String trimmed = fullLine.substring(1).strip();
 int spaceIdx = trimmed.indexOf(' ');
 String name = spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
 if (DESTRUCTIVE_COMMANDS.contains(name)) return true;
 // /exit --delete is destructive
 if ("exit".equals(name) && trimmed.contains("--delete")) return true;
 return false;
 }

 /**
 * Split inline-skip tokens from a command line.
 * Returns the cleaned args (skip tokens removed).
 */
 public static String stripSkipTokens(String args) {
 if (args == null || args.isBlank()) return "";
 String[] tokens = args.strip().split("\\s+");
 StringBuilder kept = new StringBuilder();
 for (String tok : tokens) {
 if (!SKIP_TOKENS.contains(tok.toLowerCase())) {
 if (kept.length() > 0) kept.append(" ");
 kept.append(tok);
 }
 }
 return kept.toString();
 }

 /**
 * Check if args contain a skip token.
 */
 public static boolean hasSkipToken(String args) {
 if (args == null || args.isBlank()) return false;
 for (String tok : args.strip().split("\\s+")) {
 if (SKIP_TOKENS.contains(tok.toLowerCase())) return true;
 }
 return false;
 }

 /**
 * Get the command name from a full command line.
 */
 public static String getCommandName(String fullLine) {
 if (fullLine == null || !fullLine.startsWith("/")) return "";
 String trimmed = fullLine.substring(1).strip();
 int spaceIdx = trimmed.indexOf(' ');
 return spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
 }

 /**
 * Get the args from a full command line (skip tokens removed).
 */
 public static String getCleanArgs(String fullLine) {
 if (fullLine == null || !fullLine.startsWith("/")) return "";
 String trimmed = fullLine.substring(1).strip();
 int spaceIdx = trimmed.indexOf(' ');
 if (spaceIdx < 0) return "";
 String args = trimmed.substring(spaceIdx + 1).strip();
 return stripSkipTokens(args);
 }

 /**
 * Determine the confirmation result for a given command line.
 * <p>
 * This is the decision logic — the actual prompt is done by the caller.
 *
 * @param fullLine the full command line (e.g. "/new", "/reset now")
 * @return YES/ALWAYS/CANCEL/NOT_DESTRUCTIVE
 */
 public ConfirmResult evaluate(String fullLine) {
 if (!isDestructiveLine(fullLine)) {
 return ConfirmResult.NOT_DESTRUCTIVE;
 }

 // Inline-skip: /reset now, /new --yes, /undo -y
 String trimmed = fullLine.substring(1).strip();
 int spaceIdx = trimmed.indexOf(' ');
 String args = spaceIdx > 0 ? trimmed.substring(spaceIdx + 1).strip() : "";
 if (hasSkipToken(args)) {
 return ConfirmResult.YES;
 }

 // Gate check — if confirmation disabled, auto-approve
 if (!confirmRequired) {
 return ConfirmResult.YES;
 }

 // The caller needs to prompt the user — return YES as default
 // when the caller can't prompt (non-interactive mode)
 return ConfirmResult.YES;
 }

 /**
 * Evaluate and prompt for confirmation. Returns the user's choice.
 *
 * @param fullLine the full command line
 * @param promptFn function to show prompt and get user response
 * @return YES/ALWAYS/CANCEL/NOT_DESTRUCTIVE
 */
 public ConfirmResult evaluateWithPrompt(String fullLine,
 java.util.function.Function<String, String> promptFn) {
 if (!isDestructiveLine(fullLine)) {
 return ConfirmResult.NOT_DESTRUCTIVE;
 }

 String cmdName = getCommandName(fullLine);
 String args = fullLine.substring(1).strip();
 int spaceIdx = args.indexOf(' ');
 if (spaceIdx > 0) {
 args = args.substring(spaceIdx + 1);
 } else {
 args = "";
 }

 // Inline-skip
 if (hasSkipToken(args)) {
 return ConfirmResult.YES;
 }

 // Gate check
 if (!confirmRequired) {
 return ConfirmResult.YES;
 }

 // Prompt the user
 String prompt = "⚠️ Confirm /" + cmdName + "\n" +
 "This will modify session state.\n" +
 "Choose: [Y]es / [A]lways / [C]ancel: ";
 String response = promptFn.apply(prompt);
 if (response == null) return ConfirmResult.CANCEL;

 String r = response.strip().toLowerCase();
 return switch (r) {
 case "y", "yes" -> ConfirmResult.YES;
 case "a", "always" -> {
 confirmRequired = false;
 yield ConfirmResult.ALWAYS;
 }
 default -> ConfirmResult.CANCEL;
 };
 }

 public boolean isConfirmRequired() { return confirmRequired; }
 public void setConfirmRequired(boolean confirmRequired) { this.confirmRequired = confirmRequired; }
}