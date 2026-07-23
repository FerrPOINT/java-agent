package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class DefaultContextEngine implements ContextEngine {

    @Override
    public List<Message> prepareContext(Session session, List<Message> messages) {
        return messages;
    }
}
