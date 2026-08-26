package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class FallbackControllerTest {

    @Mock private ModelClient modelClient;
    @Mock private ErrorClassifier errorClassifier;
    @Mock private ContextCompressor contextCompressor;
    @Mock private DefaultContextEngine contextEngineDelegate;

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
    }

    private FallbackController createController() {
        return new FallbackController(modelClient, errorClassifier, properties,
            contextCompressor, contextEngineDelegate);
    }

    // ─── initTurn ───

    @Test
    void initTurn_setsActiveModelClientToPrimary() {
        FallbackController controller = createController();
        controller.initTurn();
        assertThat(controller.getActiveModelClient()).isSameAs(modelClient);
        assertThat(controller.hasFallbackManager()).isTrue();
    }

    @Test
    void initTurn_createsFallbackManagerWithConfiguredChain() {
        FallbackConfig fb = new FallbackConfig();
        fb.setProvider("openai-compatible");
        fb.setModel("fallback-model");
        fb.setBaseUrl("http://fallback:8080");
        fb.setApiKey("key");
        properties.setFallbackChain(List.of(fb));

        FallbackController controller = createController();
        controller.initTurn();
        assertThat(controller.hasFallbackManager()).isTrue();
    }

    @Test
    void initTurn_calledTwice_restoresPrimaryAndRecreatesManager() {
        FallbackController controller = createController();
        controller.initTurn();
        controller.initTurn(); // second call should not throw
        assertThat(controller.getActiveModelClient()).isSameAs(modelClient);
    }

    // ─── getActiveModelClient ───

    @Test
    void getActiveModelClient_beforeInitTurn_returnsInjectedClient() {
        FallbackController controller = createController();
        assertThat(controller.getActiveModelClient()).isSameAs(modelClient);
    }

    // ─── hasFallbackManager ───

    @Test
    void hasFallbackManager_beforeInitTurn_returnsFalse() {
        FallbackController controller = createController();
        assertThat(controller.hasFallbackManager()).isFalse();
    }

    @Test
    void hasFallbackManager_afterInitTurn_returnsTrue() {
        FallbackController controller = createController();
        controller.initTurn();
        assertThat(controller.hasFallbackManager()).isTrue();
    }

    // ─── detectRefusalPattern ───

    @Test
    void detectRefusalPattern_nullMessage_returnsNull() {
        assertThat(FallbackController.detectRefusalPattern(null)).isNull();
    }

    @Test
    void detectRefusalPattern_contentPolicy_returnsPattern() {
        assertThat(FallbackController.detectRefusalPattern("Error: content policy violation"))
            .isEqualTo("content policy");
    }

    @Test
    void detectRefusalPattern_contentFilter_returnsPattern() {
        assertThat(FallbackController.detectRefusalPattern("Content Filter triggered"))
            .isEqualTo("content filter");
    }

    @Test
    void detectRefusalPattern_safetyFilter_returnsPattern() {
        assertThat(FallbackController.detectRefusalPattern("Safety filter blocked the request"))
            .isEqualTo("safety filter");
    }

    @Test
    void detectRefusalPattern_contentPolicyViolation_returnsPattern() {
        assertThat(FallbackController.detectRefusalPattern("content_policy_violation error"))
            .isEqualTo("content_policy_violation");
    }

    @Test
    void detectRefusalPattern_iCantAssist_returnsPattern() {
        assertThat(FallbackController.detectRefusalPattern("I can't assist with that"))
            .isEqualTo("i can't assist");
    }

    @Test
    void detectRefusalPattern_iCannotAssist_returnsPattern() {
        assertThat(FallbackController.detectRefusalPattern("I cannot assist with that"))
            .isEqualTo("i cannot assist");
    }

    @Test
    void detectRefusalPattern_imNotAbleToHelp_returnsPattern() {
        assertThat(FallbackController.detectRefusalPattern("I'm not able to help with this"))
            .isEqualTo("i'm not able to help");
    }

    @Test
    void detectRefusalPattern_iAmNotAbleToHelp_returnsPattern() {
        assertThat(FallbackController.detectRefusalPattern("I am not able to help with this"))
            .isEqualTo("i am not able to help");
    }

    @Test
    void detectRefusalPattern_caseInsensitive() {
        assertThat(FallbackController.detectRefusalPattern("CONTENT POLICY")).isEqualTo("content policy");
    }

    @Test
    void detectRefusalPattern_noMatch_returnsNull() {
        assertThat(FallbackController.detectRefusalPattern("A normal error message")).isNull();
    }

    @Test
    void detectRefusalPattern_emptyString_returnsNull() {
        assertThat(FallbackController.detectRefusalPattern("")).isNull();
    }

    // ─── extractRetryAfterMs ───

    @Test
    void extractRetryAfterMs_nullException_returnsZero() {
        assertThat(FallbackController.extractRetryAfterMs(null)).isEqualTo(0);
    }

    @Test
    void extractRetryAfterMs_exceptionWithNullMessage_returnsZero() {
        Exception e = new RuntimeException();
        assertThat(FallbackController.extractRetryAfterMs(e)).isEqualTo(0);
    }

    @Test
    void extractRetryAfterMs_retryAfterHeader_returnsMs() {
        Exception e = new RuntimeException("429 Too Many Requests\nretry-after: 30");
        assertThat(FallbackController.extractRetryAfterMs(e)).isEqualTo(30_000L);
    }

    @Test
    void extractRetryAfterMs_retryAfterWithColonSpace_returnsMs() {
        Exception e = new RuntimeException("retry after: 5 seconds");
        assertThat(FallbackController.extractRetryAfterMs(e)).isEqualTo(5_000L);
    }

    @Test
    void extractRetryAfterMs_noRetryAfter_returnsZero() {
        Exception e = new RuntimeException("500 Internal Server Error");
        assertThat(FallbackController.extractRetryAfterMs(e)).isEqualTo(0);
    }

    @Test
    void extractRetryAfterMs_emptyMessage_returnsZero() {
        Exception e = new RuntimeException("");
        assertThat(FallbackController.extractRetryAfterMs(e)).isEqualTo(0);
    }

    // ─── lowerMessageContains ───

    @Test
    void lowerMessageContains_nullException_returnsFalse() {
        assertThat(FallbackController.lowerMessageContains(null, "test")).isFalse();
    }

    @Test
    void lowerMessageContains_nullMessage_returnsFalse() {
        Exception e = new RuntimeException();
        assertThat(FallbackController.lowerMessageContains(e, "test")).isFalse();
    }

    @Test
    void lowerMessageContains_matchFound_returnsTrue() {
        Exception e = new RuntimeException("Connection Timeout");
        assertThat(FallbackController.lowerMessageContains(e, "timeout")).isTrue();
    }

    @Test
    void lowerMessageContains_caseInsensitiveMatch_returnsTrue() {
        Exception e = new RuntimeException("TIMEOUT occurred");
        assertThat(FallbackController.lowerMessageContains(e, "timeout")).isTrue();
    }

    @Test
    void lowerMessageContains_noMatch_returnsFalse() {
        Exception e = new RuntimeException("Some other error");
        assertThat(FallbackController.lowerMessageContains(e, "timeout")).isFalse();
    }

    @Test
    void lowerMessageContains_nullSubstring_returnsFalse() {
        Exception e = new RuntimeException("some error");
        assertThat(FallbackController.lowerMessageContains(e, null)).isFalse();
    }

    // ─── stripGrammarPatternsFromTools ───

    @Test
    void stripGrammarPatternsFromTools_nullInput_returnsNull() {
        // The method is a passthrough placeholder; it returns what it receives
        List<ToolDefinition> result = FallbackController.stripGrammarPatternsFromTools(null);
        // Placeholder returns the input as-is
        assertThat(result).isNull();
    }

    @Test
    void stripGrammarPatternsFromTools_emptyList_returnsEmpty() {
        List<ToolDefinition> result = FallbackController.stripGrammarPatternsFromTools(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void stripGrammarPatternsFromTools_nonEmptyList_returnsSameList() {
        ToolDefinition tool = new ToolDefinition("test", "desc", Map.of());
        List<ToolDefinition> result = FallbackController.stripGrammarPatternsFromTools(List.of(tool));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("test");
    }

    // ─── tryActivateFallback ───

    @Test
    void tryActivateFallback_noFallbackManager_returnsFalse() {
        FallbackController controller = createController();
        // Don't call initTurn → no fallback manager
        assertThat(controller.tryActivateFallback(ErrorClassifier.ErrorType.RATE_LIMIT,
            new RuntimeException("rate limited"))).isFalse();
    }

    @Test
    void tryActivateFallback_emptyChain_returnsFalse() {
        FallbackController controller = createController();
        controller.initTurn();
        assertThat(controller.tryActivateFallback(ErrorClassifier.ErrorType.RATE_LIMIT,
            new RuntimeException("rate limited"))).isFalse();
    }

    @Test
    void tryActivateFallback_withChain_returnsTrue() {
        FallbackConfig fb = new FallbackConfig();
        fb.setProvider("openai-compatible");
        fb.setModel("fallback-model");
        fb.setBaseUrl("http://fallback:8080");
        fb.setApiKey("key");

        properties.setFallbackChain(List.of(fb));

        FallbackController controller = createController();
        controller.initTurn();

        assertThat(controller.tryActivateFallback(ErrorClassifier.ErrorType.RATE_LIMIT,
            new RuntimeException("rate limited"))).isTrue();
    }

    @Test
    void tryActivateFallback_chainExhausted_returnsFalse() {
        FallbackConfig fb = new FallbackConfig();
        fb.setProvider("openai-compatible");
        fb.setModel("fallback-model");
        fb.setBaseUrl("http://fallback:8080");
        fb.setApiKey("key");

        properties.setFallbackChain(List.of(fb));

        FallbackController controller = createController();
        controller.initTurn();

        // First activation succeeds
        assertThat(controller.tryActivateFallback(ErrorClassifier.ErrorType.RATE_LIMIT,
            new RuntimeException("rate limited"))).isTrue();
        // Second activation — chain exhausted
        assertThat(controller.tryActivateFallback(ErrorClassifier.ErrorType.RATE_LIMIT,
            new RuntimeException("rate limited"))).isFalse();
    }

    // ─── ContentPolicyException ───

    @Test
    void contentPolicyException_holdsMessageAndCause() {
        RuntimeException cause = new RuntimeException("inner");
        FallbackController.ContentPolicyException ex =
            new FallbackController.ContentPolicyException("policy error", cause);
        assertThat(ex.getMessage()).isEqualTo("policy error");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}