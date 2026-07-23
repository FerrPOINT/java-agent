package com.ferrpoint.hermes.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class HealthController {

    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf(
        "status" to "up",
        "service" to "hermes-java-agent"
    )
}
