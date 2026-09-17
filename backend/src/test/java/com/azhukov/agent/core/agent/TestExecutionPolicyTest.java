package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestExecutionPolicyTest {

    @Test
    void genericRequestBlocksShellWithoutChoosingATestCommand() {
        assertThat(TestExecutionPolicy.classify("протестируй"))
            .isEqualTo(TestExecutionPolicy.Scope.GENERIC);
        assertThat(TestExecutionPolicy.terminalRefusal("pwd", TestExecutionPolicy.Scope.GENERIC))
            .contains("concrete workspace/test target");
    }

    @Test
    void focusedRequestAllowsFilteredGradleTest() {
        assertThat(TestExecutionPolicy.classify("запусти TargetTest --tests"))
            .isEqualTo(TestExecutionPolicy.Scope.FOCUSED);
        assertThat(TestExecutionPolicy.terminalRefusal(
            "./gradlew :backend:test --tests 'com.example.TargetTest'", TestExecutionPolicy.Scope.FOCUSED))
            .isNull();
    }

    @Test
    void focusedRequestRejectsBroadAndIntegrationSuites() {
        assertThat(TestExecutionPolicy.terminalRefusal("./gradlew :backend:test", TestExecutionPolicy.Scope.FOCUSED))
            .contains("focused testing");
        assertThat(TestExecutionPolicy.terminalRefusal("./gradlew :backend:slowTest", TestExecutionPolicy.Scope.FOCUSED))
            .contains("slowTest");
    }

    @Test
    void broadRequestPermitsExplicitBroadSuite() {
        assertThat(TestExecutionPolicy.classify("запусти все интеграционные тесты"))
            .isEqualTo(TestExecutionPolicy.Scope.BROAD);
        assertThat(TestExecutionPolicy.terminalRefusal("./gradlew :backend:slowTest", TestExecutionPolicy.Scope.BROAD))
            .isNull();
    }

    @Test
    void genericSmokeExcludesInteractiveAndSideEffectingTools() {
        assertThat(TestExecutionPolicy.GENERIC_SMOKE_EXCLUDED_TOOLS)
            .contains("clarify", "delegate_task", "cronjob", "todo", "browser_snapshot", "text_to_speech", "image_generate")
            .doesNotContain("read_file", "web_search", "session_search");
    }
}
