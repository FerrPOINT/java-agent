package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;

@Component
@Slf4j
@RequiredArgsConstructor
public class ManagedToolGateway {

    private final AgentProperties properties;
    private final ConcurrentHashMap<String, Predicate<String>> registeredChecks = new ConcurrentHashMap<>();


    public boolean isEnabled(String toolName) {
        if (!properties.getTools().isManagedGatewayEnabled()) {
            return true;
        }
        Predicate<String> check = registeredChecks.get(toolName);
        if (check != null) {
            return check.test(toolName);
        }
        return true;
    }

    public void registerTool(String toolName, Predicate<String> checkFn) {
        registeredChecks.put(toolName, checkFn);
    }
}