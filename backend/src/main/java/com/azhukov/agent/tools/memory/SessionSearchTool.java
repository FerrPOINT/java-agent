package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import org.springframework.data.domain.PageRequest;

import static com.azhukov.agent.tools.ToolHandler.parseJson;

import java.time.format.DateTimeFormatter;
import java.util.List;

@AgentTool(name = "session_search", description = "Search the current session's message history by keyword/phrase and return matching excerpts.", toolset = "memory")
public class SessionSearchTool implements ToolHandler {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_INSTANT;

    private final MessageRepository messageRepository;

    public SessionSearchTool(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SessionSearchArgs args = parseJson(arguments, SessionSearchArgs.class);
        String query = args.query() != null ? args.query().toLowerCase() : "";
        int limit = Math.min(args.limit() != null ? args.limit() : 10, 50);

        List<MessageEntity> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.id());
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (MessageEntity e : history) {
            String content = e.getContent() != null ? e.getContent() : "";
            if (content.toLowerCase().contains(query)) {
                sb.append("[").append(e.getRole()).append(" ").append(FMT.format(e.getCreatedAt())).append("] ")
                  .append(content.length() > 300 ? content.substring(0, 300) + "..." : content)
                  .append("\n\n");
                if (++count >= limit) break;
            }
        }
        if (count == 0) {
            return ToolResult.ok("No matching messages found.");
        }
        return ToolResult.ok("Found " + count + " message(s):\n\n" + sb);
    }

    static class SessionSearchArgs {
        @ToolParam(description = "Keyword or phrase to search for", required = true)
        private String query;
        @ToolParam(description = "Maximum results to return", required = false, type = "integer")
        private Integer limit;

        public String query() { return query; }
        public Integer limit() { return limit; }
    }
}
