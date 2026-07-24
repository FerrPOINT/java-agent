package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;

import java.util.List;

public interface ContextCompressor {

    List<Message> compress(List<Message> messages, int targetChars);

    boolean isLocked(String sessionId, int generation);
}
