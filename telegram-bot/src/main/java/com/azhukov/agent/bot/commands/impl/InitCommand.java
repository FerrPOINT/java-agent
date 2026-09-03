package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BusySessionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hermes parity (/init, hermes_cli/init_command.py build_init_prompt):
 * generate or update a project's AGENTS.md instructions file from a repo
 * scan. The guidance prompt is injected as a NORMAL user turn — the agent
 * inspects the project with read-only tools and writes the file. When an
 * AGENTS.md already exists, its content is embedded in the prompt and the
 * merge discipline (preserve user content, surgical edits) applies.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InitCommand implements CommandHandler {
    private final BusySessionHandler busyHandler;

    @Override
    public String name() { return "init"; }

    @Override
    public String description() { return "Generate or update AGENTS.md for the current project"; }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String extra = event.commandArgs() == null ? "" : event.commandArgs().strip();
        String cwd = System.getProperty("user.dir", "/opt/dev/java-agent");
        Path agentsFile = Path.of(cwd, "AGENTS.md");
        String existing = null;
        try {
            if (Files.exists(agentsFile)) {
                existing = Files.readString(agentsFile);
            }
        } catch (Exception e) {
            log.debug("Could not read existing AGENTS.md: {}", e.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[/init] The user wants you to ")
          .append(existing != null ? "UPDATE the existing AGENTS.md" : "generate an AGENTS.md")
          .append(" project-instructions file for the project at: ").append(cwd).append("\n\n")
          .append("AGENTS.md is the instruction file coding agents load as project context ")
          .append("every session. It should teach an agent how to work in THIS repo: what ")
          .append("the project is, how to set up, the exact build/test/lint commands, the ")
          .append("conventions the code actually follows, and the pitfalls that waste time.\n\n")
          .append("Do this:\n")
          .append("1. Inspect the project with read-only tools (read_file, search_files) — ")
          .append("start with manifests and toolchain files (build.gradle, pom.xml, ")
          .append("package.json, pyproject.toml, Makefile, CI configs), then the directory ")
          .append("layout, README/docs, and test configuration. Learn the real commands, ")
          .append("don't guess them.\n")
          .append("2. Write the file to ").append(cwd).append("/AGENTS.md with write_file");
        if (existing != null) {
            sb.append(" — but this is an UPDATE, so follow the merge discipline below.");
        } else {
            sb.append(".");
        }
        sb.append("\n")
          .append("3. Confirm the exact path you wrote and summarize in one or two lines ")
          .append("what the file covers.\n\n")
          .append("QUALITY BAR: target under 100 lines; sections the agent needs in order: ")
          .append("project overview (2-3 lines), setup/install commands, build/test/lint ")
          .append("commands, code conventions actually followed, common pitfalls, ")
          .append("repo-specific rules. No filler, no restating general knowledge — only ")
          .append("what is specific to THIS repo.\n");

        if (existing != null) {
            sb.append("\nMERGE DISCIPLINE — an AGENTS.md already exists (content below). ")
              .append("Do NOT overwrite from scratch. Preserve the user's wording, sections, ")
              .append("and rules; merge in only what is missing or verifiably stale. Prefer ")
              .append("minimal surgical edits over rewrites.\n\n")
              .append("CURRENT AGENTS.md CONTENT:\n<<<EXISTING_AGENTS_MD\n")
              .append(existing)
              .append("\nEXISTING_AGENTS_MD\n");
        }
        if (!extra.isBlank()) {
            sb.append("\nUSER NOTES — honor these while authoring (they override the ")
              .append("defaults above where they conflict):\n").append(extra);
        }

        UpdateEvent initEvent = new UpdateEvent(
            event.updateId(), UpdateEvent.Type.TEXT, event.chatId(), event.userId(),
            event.username(), event.firstName(), event.languageCode(),
            sb.toString(), null, null, null, null, null, null,
            false, null, null, event.messageId(), null, 0, event.forwardedFrom());
        busyHandler.queueMessage(event.chatId(), initEvent);
        return existing != null
            ? "🔧 /init queued — the agent will update the existing AGENTS.md."
            : "🔧 /init queued — the agent will scan the project and write AGENTS.md.";
    }
}
