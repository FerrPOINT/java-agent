package com.azhukov.agent.cli;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Registry of slash commands available in the CLI REPL.
 * <p>
 * Commands are keyed by their name (without the leading '/').
 * The REPL dispatches user input to {@link #execute(String, BackendClient, String)}.
 * <p>
 * Supports:
 * <ul>
 *   <li>Exact command matching</li>
 *   <li>Alias resolution (e.g. /q → /sessions, /reset → /new, /fork → /branch)</li>
 *   <li>Prefix matching: if exactly one command starts with the typed prefix, execute it</li>
 * </ul>
 * <p>
 * c8: command registration is split into cohesive {@link CommandGroup} classes
 * (SessionCommands, CronCommands, MemoryCommands, ModelCommands,
 * ApprovalCommands, AdminCommands, UtilityCommands). {@link #registerAll()}
 * delegates to each group. The shared {@link CliState}, {@link SessionStore},
 * and {@link DestructiveCommandConfirmation} are injected Spring beans (c16).
 */
@Component
@Slf4j
public class SlashCommandRegistry {

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final List<String> dynamicSkillNames = new ArrayList<>();

    private final DestructiveCommandConfirmation destructiveConfirmation;
    private final CliState cliState;
    private final SessionStore sessionStore;
    private final List<CommandGroup> commandGroups;

    /**
     * Spring injection constructor.
     */
    @Autowired
    public SlashCommandRegistry(CliState cliState,
                                SessionStore sessionStore,
                                DestructiveCommandConfirmation destructiveConfirmation,
                                List<CommandGroup> commandGroups) {
        this.cliState = cliState;
        this.sessionStore = sessionStore;
        this.destructiveConfirmation = destructiveConfirmation;
        this.commandGroups = commandGroups;
        registerAll();
    }

    /**
     * No-arg constructor for unit tests. Constructs default instances of the
     * shared state beans and all command groups. The order of groups matters
     * only for registration sequence, not for command lookup.
     */
    public SlashCommandRegistry() {
        this.cliState = new CliState();
        this.sessionStore = new SessionStore(SharedObjectMapper.get(), SessionStore.defaultStorePath());
        this.destructiveConfirmation = new DestructiveCommandConfirmation();
        this.commandGroups = defaultCommandGroups(this.cliState, this.sessionStore);
        registerAll();
    }

    private static List<CommandGroup> defaultCommandGroups(CliState cliState, SessionStore sessionStore) {
        return List.of(
            new SessionCommands(cliState, sessionStore),
            new CronCommands(),
            new MemoryCommands(),
            new ModelCommands(cliState),
            new ApprovalCommands(),
            new AdminCommands(cliState),
            new UtilityCommands(cliState),
            new LearnInitCommands(),
            new HeartbeatLoopCommands(),
            new SuggestionCommands()
        );
    }

    /**
     * Execute a slash command line (e.g. "/reset", "/undo 3").
     *
     * @param input     the full command line (starts with '/')
     * @param client    backend REST client
     * @param sessionId current session ID
     * @return output text, or null if the input is not a slash command
     */
    public String execute(String input, BackendClient client, String sessionId) {
        if (input == null || !input.startsWith("/")) {
            return null;
        }
        String trimmed = input.substring(1).strip();
        if (trimmed.isEmpty()) {
            return "Empty command. Type /help for available commands.";
        }

        // Split into command name and args
        String name;
        String args = "";
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            name = trimmed.substring(0, spaceIdx);
            args = trimmed.substring(spaceIdx + 1).strip();
        } else {
            name = trimmed;
        }

        // C7: Resolve command via exact → alias → prefix matching
        String resolvedName = resolveCommand(name);
        if (resolvedName == null) {
            return "Unknown command: /" + name + "\nType /help for available commands.";
        }

        SlashCommand cmd = commands.get(resolvedName);
        if (cmd == null) {
            return "Unknown command: /" + name + "\nType /help for available commands.";
        }
        try {
            return cmd.execute(args, client, sessionId);
        } catch (ExitCliException e) {
            // m27: graceful-exit signal must reach CliReplRunner (session save),
            // never be logged as a command failure.
            throw e;
        } catch (Exception e) {
            log.error("Command /{} failed: {}", resolvedName, e.getMessage(), e);
            return "Error executing /" + resolvedName + ": " + e.getMessage();
        }
    }

    /**
     * C7: Resolve a command name via exact match → alias → prefix matching.
     * <p>
     * If the name is an exact command, return it.
     * If it's an alias, resolve to the target command.
     * If exactly one command starts with the name (prefix match), return it.
     * If multiple commands match the prefix, return null (ambiguous).
     *
     * @param name the typed command name (without '/')
     * @return the resolved command name, or null if not found/ambiguous
     */
    public String resolveCommand(String name) {
        String normalized = normalizeCommandName(name);
        if (normalized == null) {
            return null;
        }

        // 1. Exact match
        if (commands.containsKey(normalized)) {
            return normalized;
        }
        // 2. Alias match
        String aliased = aliases.get(normalized);
        if (aliased != null && commands.containsKey(aliased)) {
            return aliased;
        }
        // 3. Prefix match
        List<String> matches = new ArrayList<>();
        for (String cmdName : commands.keySet()) {
            if (cmdName.startsWith(normalized)) {
                matches.add(cmdName);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        // No match or ambiguous
        return null;
    }

    /**
     * C7: Check if input resolves to a known command (exact, alias, or prefix).
     */
    public boolean isSlashCommand(String input) {
        if (input == null || !input.startsWith("/")) {
            return false;
        }
        String trimmed = input.substring(1).strip();
        int spaceIdx = trimmed.indexOf(' ');
        String name = spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
        return resolveCommand(name) != null;
    }

    /**
     * C7: Get all registered command names (for completion and help).
     */
    public List<String> getCommandNames() {
        return List.copyOf(commands.keySet());
    }

    /**
     * C7: Get all registered aliases (for completion).
     */
    public Map<String, String> getAliases() {
        return Map.copyOf(aliases);
    }

    /**
     * Get the description for a command by name.
     */
    public String getCommandDescription(String name) {
        String normalized = normalizeCommandName(name);
        return normalized == null ? "" : descriptions.getOrDefault(normalized, "");
    }

    /**
     * Get a sorted map of all command names to their descriptions.
     */
    public Map<String, String> getCommandDescriptions() {
        return new TreeMap<>(descriptions);
    }

    /**
     * C6: Register a dynamic skill command.
     */
    public void registerDynamicSkill(String skillName) {
        String normalized = normalizeCommandName(skillName);
        if (normalized == null || commands.containsKey(normalized) || aliases.containsKey(normalized)) {
            return; // Don't overwrite existing commands
        }
        String requestedSkillName = skillName.strip();
        dynamicSkillNames.add(normalized);
        register(normalized, "Skill: " + requestedSkillName, (args, client, sessionId) -> {
            String content = client.getSkillContent(requestedSkillName);
            if (content == null || content.isBlank()) {
                return "Skill '" + requestedSkillName + "' not found or empty.";
            }
            return content;
        });
    }

    /**
     * C6: Clear dynamic skill commands (for refresh).
     */
    public void clearDynamicSkills() {
        for (String skillName : dynamicSkillNames) {
            commands.remove(skillName);
            descriptions.remove(skillName);
        }
        dynamicSkillNames.clear();
    }

    /**
     * C6: Get list of dynamic skill names.
     */
    public List<String> getDynamicSkillNames() {
        return List.copyOf(dynamicSkillNames);
    }

    /**
     * c8: Register a command (package-visible for {@link CommandGroup} classes).
     */
    void register(String name, String description, SlashCommand command) {
        String normalized = normalizeCommandName(name);
        if (normalized == null) {
            return;
        }
        commands.put(normalized, command);
        descriptions.put(normalized, description);
    }

    /**
     * C7: Register an alias (package-visible for {@link CommandGroup} classes).
     */
    void registerAlias(String alias, String target) {
        String normalizedAlias = normalizeCommandName(alias);
        String normalizedTarget = normalizeCommandName(target);
        if (normalizedAlias == null || normalizedTarget == null) {
            return;
        }
        aliases.put(normalizedAlias, normalizedTarget);
    }

    /**
     * c8: Delegate command registration to each {@link CommandGroup}.
     */
    private void registerAll() {
        for (CommandGroup group : commandGroups) {
            group.registerAll(this);
        }
        log.info("SlashCommandRegistry initialized with {} commands, {} aliases",
            commands.size(), aliases.size());
    }

    /**
     * P1-3: Get the destructive command confirmation instance.
     */
    public DestructiveCommandConfirmation getDestructiveConfirmation() {
        return destructiveConfirmation;
    }

    /**
     * P1-4: Get shared CLI state.
     */
    public CliState getCliState() {
        return cliState;
    }

    /**
     * P1-5: Get session store.
     */
    public SessionStore getSessionStore() {
        return sessionStore;
    }

    private static String normalizeCommandName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).stripLeading();
        }
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
