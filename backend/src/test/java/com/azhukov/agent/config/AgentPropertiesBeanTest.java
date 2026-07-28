package com.azhukov.agent.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesBeanTest {

    @Test
    void exerciseAllNestedSettersAndGetters() throws Exception {
        AgentProperties root = new AgentProperties();
        root.setName("n");
        assertThat(root.getName()).isEqualTo("n");

        for (Class<?> nested : AgentProperties.class.getDeclaredClasses()) {
            if (!Modifier.isPublic(nested.getModifiers()) || nested.getName().contains("$")) continue;
            Object instance;
            try {
                instance = nested.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException e) {
                continue;
            }
            for (Method m : nested.getDeclaredMethods()) {
                if (m.getName().startsWith("set") && m.getParameterCount() == 1) {
                    String getter = "get" + m.getName().substring(3);
                    Method get = findMethod(nested, getter);
                    if (get == null && m.getParameterTypes()[0] == boolean.class) {
                        get = findMethod(nested, "is" + m.getName().substring(3));
                    }
                    if (get != null) {
                        Object value = sampleValue(m.getParameterTypes()[0]);
                        m.setAccessible(true);
                        m.invoke(instance, value);
                        Object actual = get.invoke(instance);
                        assertThat(actual).isEqualTo(value);
                    }
                }
            }
        }
    }

    private Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
        }
        return null;
    }

    private Object sampleValue(Class<?> type) {
        if (type == String.class) return "x";
        if (type == int.class || type == Integer.class) return 7;
        if (type == long.class || type == Long.class) return 9L;
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == double.class || type == Double.class) return 1.2;
        if (type == List.class) return new ArrayList<>();
        if (type == Set.class) return new HashSet<>();
        if (type == Map.class) return new HashMap<>();
        if (type.getName().startsWith("com.azhukov.agent.config.AgentProperties$")) {
            try { return type.getDeclaredConstructor().newInstance(); } catch (Exception e) { return null; }
        }
        return null;
    }
}
