package com.azhukov.agent.core.context;

import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultContextEngine implements ContextEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextEngine.class);
    private static final int MAX_HISTORY = 20;
    private static final int RECALL_LIMIT = 5;
    private static final int SKILL_LIMIT = 3;

    private final MemoryProvider memoryProvider;
    private final SkillManager skillManager;
    private final MessageRepository messageRepository;

    public DefaultContextEngine(MemoryProvider memoryProvider,
                                SkillManager skillManager,
                                MessageRepository messageRepository) {
        this.memoryProvider = memoryProvider;
        this.skillManager = skillManager;
        this.messageRepository = messageRepository;
    }

    @Override
    public List<Message> prepareContext(Session session, List<Message> messages) {
        List<Message> context = new ArrayList<>();

        StringBuilder systemExtra = new StringBuilder();
        appendSkills(systemExtra);
        appendMemoryRecall(session, systemExtra);

        // Compose system message first if present
        if (!messages.isEmpty() && messages.get(0).role() == Role.SYSTEM) {
            Message base = messages.get(0);
            String systemText = base.content();
            if (!systemExtra.isEmpty()) {
                systemText = systemText + "\n\n" + systemExtra;
            }
            context.add(Message.system(systemText));
        }

        // Then add recent history (excluding the current turn messages to avoid duplication)
        appendRecentHistory(session, context);

        // Add remaining incoming messages after system
        int start = (!messages.isEmpty() && messages.get(0).role() == Role.SYSTEM) ? 1 : 0;
        context.addAll(messages.subList(start, messages.size()));

        return context;
    }

    private void appendSkills(StringBuilder sb) {
        List<String> names = skillManager.listSkillNames();
        if (names.isEmpty()) return;

        int count = 0;
        sb.append("Available skills:\n");
        for (String name : names) {
            if (++count > SKILL_LIMIT) break;
            String content = skillManager.getSkill(name);
            if (content != null) {
                sb.append("- ").append(name).append(": ")
                  .append(content.length() > 400 ? content.substring(0, 400) + "..." : content)
                  .append("\n");
            }
        }
    }

    private void appendMemoryRecall(Session session, StringBuilder sb) {
        try {
            String lastUser = findLastUserMessage(session);
            if (lastUser == null || lastUser.isBlank()) return;
            List<String> facts = memoryProvider.recall(session.userId(), lastUser, RECALL_LIMIT);
            if (facts.isEmpty()) return;
            sb.append("Relevant memory:\n");
            for (String fact : facts) {
                sb.append("- ").append(fact).append("\n");
            }
        } catch (Exception e) {
            log.debug("Memory recall failed: {}", e.getMessage());
        }
    }

    private String findLastUserMessage(Session session) {
        List<MessageEntity> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.id());
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).getRole())) {
                return history.get(i).getContent();
            }
        }
        return null;
    }

    private void appendRecentHistory(Session session, List<Message> context) {
        try {
            List<MessageEntity> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.id());
            int start = Math.max(0, history.size() - MAX_HISTORY);
            for (MessageEntity e : history.subList(start, history.size())) {
                String role = e.getRole();
                String content = e.getContent() != null ? e.getContent() : "";
                context.add(switch (role) {
                    case "assistant" -> Message.assistant(content, e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                    case "tool" -> Message.toolResult(e.getToolCallId(), content, e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                    default -> Message.user(content);
                });
            }
        } catch (Exception e) {
            log.debug("History load failed: {}", e.getMessage());
        }
    }
}
