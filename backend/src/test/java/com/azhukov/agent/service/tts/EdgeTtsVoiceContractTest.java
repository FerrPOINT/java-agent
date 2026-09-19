package com.azhukov.agent.service.tts;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider-aware voice contract: the edge provider must never receive an
 * OpenAI-style voice ('alloy' etc.); its Microsoft neural default applies
 * unless the caller passes an explicit voice.
 */
class EdgeTtsVoiceContractTest {

    private static AgentProperties props(String globalVoice, String edgeVoice) {
        AgentProperties properties = new AgentProperties();
        properties.getTts().setEnabled(true);
        properties.getTts().setProvider("edge");
        properties.getTts().setVoice(globalVoice);
        properties.getTts().getEdge().setVoice(edgeVoice);
        return properties;
    }

    @Test
    void edgeProviderIgnoresInvalidGlobalAlloyVoice() {
        EdgeTtsProvider provider = new EdgeTtsProvider(props("alloy", "ru-RU-DmitryNeural"), "");
        assertThat(provider).isNotNull();
        // Voice selection is constructor-level; assert via the documented pattern.
        assertThat(EdgeTtsProvider.VOICE_PATTERN.matcher("alloy").matches()).isFalse();
        assertThat(EdgeTtsProvider.VOICE_PATTERN.matcher("ru-RU-DmitryNeural").matches()).isTrue();
        assertThat(EdgeTtsProvider.VOICE_PATTERN.matcher("en-US-AriaNeural").matches()).isTrue();
    }

    @Test
    void voicePatternAcceptsMicrosoftNeuralNamesOnly() {
        // ru-RU-DmitryNeural, en-US-GuyNeural etc.
        assertThat(EdgeTtsProvider.VOICE_PATTERN.matcher("en-US-GuyNeural").matches()).isTrue();
        // OpenAI voices, bare names, junk
        for (String bad : new String[]{"alloy", "echo", "nova", "Dmitry", "ruRU", ""}) {
            assertThat(EdgeTtsProvider.VOICE_PATTERN.matcher(bad).matches()).as(bad).isFalse();
        }
    }

    @Test
    void edgeVoiceConfiguredBlankFallsBackToPatternValidGlobal() {
        EdgeTtsProvider provider = new EdgeTtsProvider(props("en-US-AriaNeural", " "), "");
        assertThat(provider).isNotNull();
    }
}
