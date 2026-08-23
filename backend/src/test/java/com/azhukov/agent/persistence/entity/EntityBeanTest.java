package com.azhukov.agent.persistence.entity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class EntityBeanTest {

    @Test
    void exerciseAllEntities() throws Exception {
        List<Class<?>> classes = Arrays.asList(
            SessionEntity.class, MessageEntity.class, MemoryEntity.class,
            SkillEntity.class, TodoEntity.class, CompressionLockEntity.class);

        for (Class<?> cls : classes) {
            Object instance;
            try {
                instance = cls.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException e) {
                continue;
            }
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().startsWith("set") && m.getParameterCount() == 1) {
                    String getter = "get" + m.getName().substring(3);
                    Method get = findMethod(cls, getter);
                    if (get == null && m.getParameterTypes()[0] == boolean.class) {
                        get = findMethod(cls, "is" + m.getName().substring(3));
                    }
                    if (get != null) {
                        Object value = sampleValue(m.getParameterTypes()[0]);
                        m.setAccessible(true);
                        if (value != null) {
                            m.invoke(instance, value);
                            assertThat(get.invoke(instance)).isEqualTo(value);
                        }
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
        if (type == Integer.class || type == int.class) return 7;
        if (type == Long.class || type == long.class) return 9L;
        if (type == Boolean.class || type == boolean.class) return true;
        if (type == UUID.class) return UUID.randomUUID();
        if (type == LocalDateTime.class) return LocalDateTime.now();
        if (type == Instant.class) return Instant.now();
        if (type == List.class) return new ArrayList<>();
        if (type == Map.class) return new HashMap<>();
        return null;
    }
}
