package com.ferrpoint.hermes;

import org.springframework.boot.SpringApplication;

public class TestHermesJavaAgentApplication {

    public static void main(String[] args) {
        SpringApplication.from(HermesJavaAgentApplication::main)
            .with(TestcontainersConfiguration.class)
            .run(args);
    }
}
