package com.azhukov.agent.api;

import java.util.Locale;
import java.util.Set;

final class OpenAiRequestBooleans {

    private static final Set<String> TRUE_REQUEST_BOOL_STRINGS = Set.of("1", "true", "yes", "on");
    private static final Set<String> FALSE_REQUEST_BOOL_STRINGS = Set.of("0", "false", "no", "off");

    private OpenAiRequestBooleans() {
    }

    static boolean coerce(Object value, boolean defaultValue) {
        Boolean coerced = coerceOptional(value);
        return coerced != null ? coerced : defaultValue;
    }

    static Boolean coerceOptional(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if (TRUE_REQUEST_BOOL_STRINGS.contains(normalized)) {
                return true;
            }
            if (FALSE_REQUEST_BOOL_STRINGS.contains(normalized)) {
                return false;
            }
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        return null;
    }
}
