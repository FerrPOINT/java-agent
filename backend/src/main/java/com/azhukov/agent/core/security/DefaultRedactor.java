package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class DefaultRedactor implements Redactor {

    private final AgentProperties properties;
    private final Pattern envVarPattern = Pattern.compile("\\b([A-Z_][A-Z0-9_]*)=(\\S+)");

    public DefaultRedactor(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String redact(String output) {
        if (!properties.getSecurity().isRedactEnabled() || output == null) {
            return output;
        }
        String result = output;
        List<String> patterns = properties.getSecurity().getSecretPatterns();
        if (patterns != null) {
            for (String regex : patterns) {
                try {
                    result = result.replaceAll(regex, "[REDACTED]");
                } catch (PatternSyntaxException e) {
                    // ignore invalid regex
                }
            }
        }
        result = redactEnvVars(result);
        return result;
    }

    @Override
    public String redactEnvVars(String output) {
        if (output == null) {
            return null;
        }
        List<String> sensitive = properties.getSecurity().getSensitiveEnvVarPatterns();
        if (sensitive == null || sensitive.isEmpty()) {
            return output;
        }
        String result = output;
        var matcher = envVarPattern.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            for (String pattern : sensitive) {
                if (matches(name, pattern)) {
                    matcher.appendReplacement(sb, name + "=[REDACTED]");
                    break;
                }
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean matches(String name, String pattern) {
        String lowerName = name.toLowerCase();
        String lowerPattern = pattern.toLowerCase();
        if (lowerPattern.startsWith("*") || lowerPattern.endsWith("*")) {
            String glob = lowerPattern.replace("*", "");
            return lowerName.contains(glob);
        }
        return lowerName.equals(lowerPattern);
    }
}
