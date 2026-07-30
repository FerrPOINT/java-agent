package com.azhukov.agent.security;

import com.azhukov.agent.core.model.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MessageSanitizer {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
    private static final Pattern SURROGATE = Pattern.compile("[\ud800-\udfff]");
    private static final int MAX_MESSAGE_LENGTH = 1_000_000;

    private final SecretRedactor redactor;


    public List<Message> sanitize(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        for (Message m : messages) {
            result.add(sanitize(m));
        }
        return result;
    }

    public Message sanitize(Message message) {
        if (message == null) return null;
        String content = message.content();
        content = content == null ? "" : content;
        content = content.replaceAll(CONTROL_CHARS.pattern(), "");
        content = content.replaceAll(SURROGATE.pattern(), "");
        content = java.text.Normalizer.normalize(content, java.text.Normalizer.Form.NFC);
        if (content.length() > MAX_MESSAGE_LENGTH) {
            content = content.substring(0, MAX_MESSAGE_LENGTH) + "\n[truncated]";
        }
        content = redactor.redact(content);
        return Message.withContent(message, content);
    }
}