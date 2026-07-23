package com.ferrpoint.agent;

import org.springframework.boot.SpringApplication;

public class TestJavaAgentApplication {

    public static void main(String[] args) {
        SpringApplication.from(JavaAgentApplication::main)
            .with(TestcontainersConfiguration.class)
            .run(args);
    }
}
