package com.azhukov.agent.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AgentTool {

    String name();

    String description();

    String toolset() default "core";

    boolean enabledByDefault() default true;
}
