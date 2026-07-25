package com.azhukov.agent.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class UserInputSanitizer {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
    private static final Pattern SURROGATE = Pattern.compile("[\ud800-\udfff]");
    private static final int MAX_INPUT_LENGTH = 200_000;

    public String sanitize(String input) {
        if (input == null) return "";
        String out = input;
        out = out.replaceAll(CONTROL_CHARS.pattern(), "");
        out = out.replaceAll(SURROGATE.pattern(), "");
        out = java.text.Normalizer.normalize(out, java.text.Normalizer.Form.NFC);
        if (out.length() > MAX_INPUT_LENGTH) {
            out = out.substring(0, MAX_INPUT_LENGTH) + "\n[input truncated]";
        }
        return out;
    }
}
