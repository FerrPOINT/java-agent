package com.ferrpoint.hermes.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(HermesProperties::class)
class HermesConfig

@ConfigurationProperties(prefix = "hermes")
data class HermesProperties(
    val model: ModelProperties = ModelProperties(),
    val vision: VisionProperties = VisionProperties(),
    val browser: BrowserProperties = BrowserProperties(),
    val memory: MemoryProperties = MemoryProperties(),
    val skills: SkillsProperties = SkillsProperties()
)

data class ModelProperties(
    val provider: String = "openai",
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val modelName: String = "gpt-4o-mini",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val timeoutSeconds: Long = 60
)

data class VisionProperties(
    val provider: String = "kimi",
    val baseUrl: String = "https://api.moonshot.ai/v1",
    val apiKey: String? = null,
    val modelName: String = "kimi-k2.7-code",
    val maxDownloadBytes: Long = 50 * 1024 * 1024,
    val downloadTimeoutSeconds: Long = 30
)

data class BrowserProperties(
    val executable: String = "google-chrome",
    val headless: Boolean = true,
    val args: List<String> = listOf("--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage"),
    val cdpUrl: String? = null,
    val timeoutSeconds: Long = 30
)

data class MemoryProperties(
    val enabled: Boolean = true,
    val provider: String = "sqlite",
    val maxContextChars: Int = 6000
)

data class SkillsProperties(
    val enabled: Boolean = true,
    val directory: String = "~/.hermes/skills"
)
