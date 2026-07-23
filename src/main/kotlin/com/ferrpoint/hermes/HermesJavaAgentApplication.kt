package com.ferrpoint.hermes

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class HermesJavaAgentApplication

fun main(args: Array<String>) {
    runApplication<HermesJavaAgentApplication>(*args)
}
