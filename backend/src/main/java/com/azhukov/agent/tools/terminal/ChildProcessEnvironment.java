package com.azhukov.agent.tools.terminal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a minimal environment for agent-started shell processes. */
final class ChildProcessEnvironment {
    private static final List<String> SAFE_PARENT_PREFIXES = List.of(
        "PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_", "TERM", "TMPDIR", "TMP", "TEMP",
        "SHELL", "XDG_", "JAVA_HOME"
    );
    private static final List<String> SECRET_SUBSTRINGS = List.of(
        "KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "PASSWD", "AUTH",
        "DSN", "WEBHOOK", "CREDS", "BEARER", "APIKEY"
    );
    private static final List<String> UNSAFE_SESSION_VARIABLES = List.of(
        "PATH", "HOME", "SHELL", "BASH_ENV", "ENV", "PROMPT_COMMAND", "CDPATH",
        "LD_PRELOAD", "LD_LIBRARY_PATH", "PYTHONHOME", "PYTHONPATH",
        "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM", "GIT_ASKPASS", "SSH_ASKPASS"
    );

    private ChildProcessEnvironment() {
    }

    static void prepare(Map<String, String> environment, Map<String, String> sessionEnvironment) {
        Map<String, String> inherited = new LinkedHashMap<>(environment);
        environment.clear();
        inherited.forEach((name, value) -> {
            if (isSafeParentVariable(name)) {
                environment.put(name, value);
            }
        });
        if (sessionEnvironment != null) {
            sessionEnvironment.forEach((name, value) -> {
                if (isSafeSessionVariable(name)) {
                    environment.put(name, value);
                }
            });
        }
    }

    static boolean isSafeSessionVariable(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return !containsSecretMarker(upper) && !UNSAFE_SESSION_VARIABLES.contains(upper);
    }

    private static boolean isSafeParentVariable(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (containsSecretMarker(upper)) {
            return false;
        }
        return SAFE_PARENT_PREFIXES.stream().anyMatch(upper::startsWith);
    }

    private static boolean containsSecretMarker(String name) {
        return SECRET_SUBSTRINGS.stream().anyMatch(name::contains);
    }
}
