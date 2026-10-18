package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.ports.MessageStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for Feature 4: Wire recalculateThreshold in updateModel.
 * Verifies that when the model changes, the compressor's threshold is also recalculated.
 */
@ExtendWith(MockitoExtension.class)
class DefaultContextEngineUpdateModelTest {

    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private SkillManager skillManager;
    @Mock
    private com.azhukov.agent.core.ports.MessageStorePort messageRepository;
    @Mock
    private ModelMetadataService modelMetadataService;

    private AgentProperties properties;
    private Session session;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        session = Session.create("user-42", "openai-compatible", "gpt-4o-mini");
    }

    @Test
    @DisplayName("updateModel calls contextCompressor.recalculateThreshold with new context length")
    void updateModelRecalculatesCompressorThreshold() {
        // Use a real DefaultContextCompressor (not a mock) so we can verify the threshold was set
        var compressor = new DefaultContextCompressor(null, null, properties);

        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService
        );

        // Model metadata service returns 128K context length for "claude-sonnet"
        when(modelMetadataService.detectContextLength("claude-sonnet")).thenReturn(131_072);

        engine.updateModel("claude-sonnet");

        // Engine's contextLength should be updated
        assertThat(engine.getContextLength()).isEqualTo(131_072);

        // Compressor's threshold should also be recalculated (with 64K floor)
        int expectedThresholdTokens = Math.max(
            (int) ((131_072 - properties.getModel().getMaxTokens()) * 0.75),
            DefaultContextCompressor.MINIMUM_CONTEXT_LENGTH
        );
        int expectedThresholdChars = expectedThresholdTokens * 4; // CHARS_PER_TOKEN = 4
        assertThat(compressor.getCompressionThresholdChars()).isEqualTo(expectedThresholdChars);
    }

    @Test
    @DisplayName("updateModel with null model does not call recalculateThreshold")
    void updateModelNullModelSkipsRecalculation() {
        var compressor = new DefaultContextCompressor(null, null, properties);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService
        );

        engine.updateModel(null);

        // Compressor threshold should still be 0 (not recalculated)
        assertThat(compressor.getCompressionThresholdChars()).isZero();
    }

    @Test
    @DisplayName("updateModel with blank model does not call recalculateThreshold")
    void updateModelBlankModelSkipsRecalculation() {
        var compressor = new DefaultContextCompressor(null, null, properties);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService
        );

        engine.updateModel("");

        assertThat(compressor.getCompressionThresholdChars()).isZero();
    }

    @Test
    @DisplayName("updateModel with null modelMetadataService does not call recalculateThreshold")
    void updateModelNullMetadataServiceSkipsRecalculation() {
        var compressor = new DefaultContextCompressor(null, null, properties);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, null
        );

        engine.updateModel("claude-sonnet");

        // No metadata service → contextLength stays 0, compressor not called
        assertThat(engine.getContextLength()).isZero();
        assertThat(compressor.getCompressionThresholdChars()).isZero();
    }

    @Test
    @DisplayName("updateModel applies reachable trigger to a small compressor threshold")
    void updateModelAppliesReachableTrigger() {
        var compressor = new DefaultContextCompressor(null, null, properties);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService
        );

        when(modelMetadataService.detectContextLength("small-model")).thenReturn(8_192);

        engine.updateModel("small-model");

        assertThat(engine.getContextLength()).isEqualTo(8_192);
        int expectedThreshold = (int) ((8_192 - properties.getModel().getMaxTokens())
            * CompressionPolicy.MIN_CTX_TRIGGER_RATIO) * 4;
        assertThat(compressor.getCompressionThresholdChars()).isEqualTo(expectedThreshold);
    }

    @Test
    @DisplayName("getContextLength returns the current context window size")
    void getContextLengthReturnsValue() {
        var compressor = new DefaultContextCompressor(null, null, properties);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService
        );

        when(modelMetadataService.detectContextLength("model-200k")).thenReturn(200_000);
        engine.updateModel("model-200k");

        assertThat(engine.getContextLength()).isEqualTo(200_000);
    }

    @Test
    @DisplayName("model switch applies the same output reserve to preflight and compressor")
    void updateModelSharesOutputReservation() {
        properties.getModel().setMaxTokens(32_768);
        var compressor = new DefaultContextCompressor(null, null, properties);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService);
        when(modelMetadataService.detectContextLength("model-128k")).thenReturn(131_072);

        engine.updateModel("model-128k");

        int expected = 73_728; // (131072 - 32768) * 0.75
        assertThat(engine.getStatus().get("thresholdTokens")).isEqualTo(expected);
        assertThat(compressor.getCompressionThresholdChars()).isEqualTo(expected * 4);
    }

    @Test
    @DisplayName("configured threshold and output reserve reach the compressor together")
    void updateModelUsesConfiguredThresholdWithOutputReservation() {
        properties.getContext().setThresholdPercent(0.90);
        properties.getModel().setMaxTokens(32_768);
        var compressor = new DefaultContextCompressor(null, null, properties);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService);
        when(modelMetadataService.detectContextLength("model-128k")).thenReturn(131_072);

        engine.updateModel("model-128k");

        assertThat(engine.getStatus().get("thresholdTokens")).isEqualTo(88_473);
        assertThat(compressor.getCompressionThresholdChars()).isEqualTo(88_473 * 4);
    }

    @Test
    @DisplayName("invalid output reserve leaves the previous model and compressor unchanged")
    void invalidModelSwitchPreservesState() {
        properties.getModel().setMaxTokens(32_768);
        var compressor = new DefaultContextCompressor(null, null, properties);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService);
        when(modelMetadataService.detectContextLength("model-128k")).thenReturn(131_072);
        when(modelMetadataService.detectContextLength("small-model")).thenReturn(16_384);
        engine.updateModel("model-128k");
        var previous = engine.getStatus();
        int previousCompressor = compressor.getCompressionThresholdChars();

        assertThatThrownBy(() -> engine.updateModel("small-model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Output token reservation must be smaller than the context window");

        assertThat(engine.getStatus()).isEqualTo(previous);
        assertThat(compressor.getCompressionThresholdChars()).isEqualTo(previousCompressor);
        when(modelMetadataService.getMetadata("model-128k")).thenReturn(
            new ModelMetadataService.ModelMetadata("model-128k", "model-128k", 131_072, 4));
        assertThat(engine.shouldCompressPreflight(List.of(Message.user("still the old model")))).isFalse();
        verify(modelMetadataService).getMetadata("model-128k");
    }

    @Test
    @DisplayName("preflight token estimate uses chars-per-token metadata for the active model")
    void shouldCompressPreflightUsesActiveModelMetadata() {
        properties.getModel().setMaxTokens(0);
        ContextCompressor compressor = mock(ContextCompressor.class);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService
        );

        when(modelMetadataService.detectContextLength("qwen3-coder")).thenReturn(100);
        when(modelMetadataService.getMetadata("qwen3-coder"))
            .thenReturn(new ModelMetadataService.ModelMetadata("qwen3-coder", "qwen3-coder", 100, 3));

        engine.updateModel("qwen3-coder");

        // rev-129 parity: window=100 < 512K → 75% floor → degenerate 85% rule →
        // threshold 85 tokens. 300 chars / charsPerToken(3) = 100 tokens > 85.
        assertThat(engine.shouldCompressPreflight(List.of(Message.user("x".repeat(300))))).isTrue();
        verify(modelMetadataService).getMetadata("qwen3-coder");
    }
}
