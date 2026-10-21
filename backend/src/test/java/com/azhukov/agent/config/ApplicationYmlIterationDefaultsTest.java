package com.azhukov.agent.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shipped application.yml iteration defaults: the agentic loop
 * (agent.core.max-turns) must default to 1000, matching the model-call and
 * tool-execution budgets (agent.budget.*-per-turn=1000).
 * <p>
 * The yml used to ship ${AGENT_CORE_MAX_TURNS:25}, silently cutting long tool
 * chains to a quarter of the model budget while the AgentProperties Java
 * default said 100 — the yml placeholder default always wins at runtime, so
 * neither the Java-defaults test nor a green build could catch the drift.
 * Reads the FIRST (base, non-profile) document of application.yml — the one
 * Spring loads before any profile override — via SnakeYAML directly:
 * YamlPropertiesFactoryBean merges later documents over the base and hides
 * base-only keys (verified empirically), which would make this test blind.
 */
class ApplicationYmlIterationDefaultsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> baseDocument() {
        // Read the SHIPPED main resources file by path: src/test/resources also
        // carries an application.yml (H2 test overrides without agent.core), and
        // classpath resolution would find that one first.
        java.nio.file.Path ymlPath = java.nio.file.Path.of("src", "main", "resources", "application.yml");
        assertThat(java.nio.file.Files.exists(ymlPath))
            .as("shipped application.yml must exist at src/main/resources (test cwd is the module dir)")
            .isTrue();
        try (InputStream in = java.nio.file.Files.newInputStream(ymlPath)) {
            Yaml yaml = new Yaml();
            for (Object document : yaml.loadAll(in)) {
                Map<String, Object> map = (Map<String, Object>) document;
                if (map == null) {
                    continue;
                }
                // Skip profile-gated documents: spring.config.activate.on-profile
                Map<String, Object> spring = (Map<String, Object>) map.get("spring");
                if (spring != null) {
                    Map<String, Object> config = (Map<String, Object>) spring.get("config");
                    if (config != null && config.get("activate") != null) {
                        continue;
                    }
                }
                return map;
            }
            throw new IllegalStateException("application.yml has no base document");
        } catch (IllegalStateException | ClassCastException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse application.yml", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String rawValue(String dottedKey) {
        Map<String, Object> current = baseDocument();
        String value = null;
        for (String part : dottedKey.split("\\.")) {
            Object next = current.get(part);
            assertThat(next).as("%s present in base application.yml document (part '%s')", dottedKey, part).isNotNull();
            if (next instanceof Map<?, ?> nested) {
                current = (Map<String, Object>) nested;
            } else {
                value = String.valueOf(next);
            }
        }
        return value;
    }

    private static int placeholderDefault(String dottedKey) {
        String raw = rawValue(dottedKey);
        assertThat(raw).as("%s must use a ${ENV:default} placeholder", dottedKey).contains("${");
        int colon = raw.indexOf(':');
        int end = raw.indexOf('}');
        assertThat(colon).as("%s placeholder must carry an explicit default", dottedKey).isGreaterThan(0);
        assertThat(end).as("%s placeholder must terminate", dottedKey).isGreaterThan(colon);
        return Integer.parseInt(raw.substring(colon + 1, end).trim());
    }

    @Test
    void agenticLoopDefaultIs1000AndDisablesIndependentTokenAndTimeBudgets() {
        int turns = placeholderDefault("agent.core.max-turns");
        int modelCalls = placeholderDefault("agent.budget.max-model-calls-per-turn");
        int toolExecutions = placeholderDefault("agent.budget.max-tool-executions-per-turn");
        int tokenBudget = placeholderDefault("agent.budget.max-tokens-per-turn");
        int toolDurationBudget = placeholderDefault("agent.budget.max-tool-duration-ms-per-turn");
        int runBudgetSeconds = placeholderDefault("agent.budget.run-budget-seconds");

        assertThat(turns)
            .as("agent.core.max-turns yml default must be 1000")
            .isEqualTo(1_000);
        assertThat(modelCalls)
            .as("agent.budget.max-model-calls-per-turn must match core.max-turns")
            .isEqualTo(turns);
        assertThat(toolExecutions)
            .as("agent.budget.max-tool-executions-per-turn must match core.max-turns")
            .isEqualTo(turns);
        assertThat(tokenBudget)
            .as("agent.budget.max-tokens-per-turn must be disabled by default")
            .isZero();
        assertThat(toolDurationBudget)
            .as("agent.budget.max-tool-duration-ms-per-turn must be disabled by default")
            .isZero();
        assertThat(runBudgetSeconds)
            .as("agent.budget.run-budget-seconds must default to disabled")
            .isZero();
    }

    @Test
    void javaDefaultsMatchYmlDefaults() {
        // The AgentProperties Java fallback and the shipped yml placeholder
        // default must agree — divergence means one of them drifted.
        AgentProperties properties = new AgentProperties();
        assertThat(properties.getCore().getMaxTurns())
            .as("AgentProperties.core.maxTurns must equal the yml default")
            .isEqualTo(placeholderDefault("agent.core.max-turns"));
        assertThat(properties.getBudget().getMaxModelCallsPerTurn())
            .as("AgentProperties.budget.maxModelCallsPerTurn must equal the yml default")
            .isEqualTo(placeholderDefault("agent.budget.max-model-calls-per-turn"));
        assertThat(properties.getBudget().getMaxToolExecutionsPerTurn())
            .as("AgentProperties.budget.maxToolExecutionsPerTurn must equal the yml default")
            .isEqualTo(placeholderDefault("agent.budget.max-tool-executions-per-turn"));
        assertThat(properties.getBudget().getMaxTokensPerTurn())
            .as("AgentProperties.budget.maxTokensPerTurn must equal the yml default")
            .isEqualTo(placeholderDefault("agent.budget.max-tokens-per-turn"));
        assertThat(properties.getBudget().getMaxToolDurationMsPerTurn())
            .as("AgentProperties.budget.maxToolDurationMsPerTurn must equal the yml default")
            .isEqualTo(placeholderDefault("agent.budget.max-tool-duration-ms-per-turn"));
        assertThat(properties.getBudget().getRunBudgetSeconds())
            .as("AgentProperties.budget.runBudgetSeconds must equal the yml default")
            .isEqualTo(placeholderDefault("agent.budget.run-budget-seconds"));
    }
}
