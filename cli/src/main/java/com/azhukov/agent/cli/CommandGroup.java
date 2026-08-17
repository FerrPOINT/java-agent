package com.azhukov.agent.cli;

/**
 * A group of related slash commands that registers itself into a
 * {@link SlashCommandRegistry}.
 * <p>
 * c8: splits the 800-LOC {@code registerAll()} into cohesive command groups
 * (session, cron, memory, model, approval, admin, utility). Each group
 * receives the registry and calls {@link SlashCommandRegistry#register} /
 * {@link SlashCommandRegistry#registerAlias} for its own commands.
 */
public interface CommandGroup {

    /**
     * Register this group's commands and aliases into the given registry.
     *
     * @param registry the shared slash command registry
     */
    void registerAll(SlashCommandRegistry registry);
}