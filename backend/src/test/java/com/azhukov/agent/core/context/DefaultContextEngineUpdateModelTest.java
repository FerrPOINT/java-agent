package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private MessageRepository messageRepository;
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
            (int) (131_072 * 0.75),
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
    @DisplayName("updateModel applies 64K floor to compressor threshold")
    void updateModelApplies64KFloor() {
        var compressor = new DefaultContextCompressor(null, null, properties);
        var engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, compressor, properties, null, modelMetadataService
        );

        // 8K context model → 0.75 × 8192 = 6144, floored to 64000
        when(modelMetadataService.detectContextLength("small-model")).thenReturn(8_192);

        engine.updateModel("small-model");

        assertThat(engine.getContextLength()).isEqualTo(8_192);
        // Compressor threshold should use the 64K floor
        int expectedThreshold = DefaultContextCompressor.MINIMUM_CONTEXT_LENGTH * 4;
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
}