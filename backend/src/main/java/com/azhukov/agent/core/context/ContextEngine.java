package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import java.util.List;

public interface ContextEngine {

    List<Message> prepareContext(Session session, List<Message> messages);
}
