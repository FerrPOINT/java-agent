plugins {
    java
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10"
    id("org.springframework.boot") version "3.4.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ferrpoint"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // LLM
    implementation("dev.langchain4j:langchain4j:1.0.0-beta1")
    implementation("dev.langchain4j:langchain4j-open-ai:1.0.0-beta1")
    implementation("dev.langchain4j:langchain4j-spring-boot-starter:1.0.0-beta1")

    // MCP
    implementation("io.modelcontextprotocol.sdk:mcp:1.0.0-M7")

    // DB / migrations
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.hibernate.orm:hibernate-community-dialects")

    // Templating
    implementation("io.pebbletemplates:pebble:3.2.2")

    // Retry
    implementation("io.github.resilience4j:resilience4j-retry:2.2.0")
    implementation("io.github.resilience4j:resilience4j-kotlin:2.2.0")

    // CLI
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.jline:jline:3.27.0")

    // Logging / utils
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

springBoot {
    buildInfo()
}

gradleLint {
    rules = emptySet()
}

// Minimal configuration to avoid noisy unused-dependency false positives from gradle-lint.
// If gradle-lint plugin is not present, this extension block is harmless.
fun Any.gradleLint(configure: Any.() -> Unit) = Unit
