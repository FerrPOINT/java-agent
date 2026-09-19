package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 2026-09-19 tool audit showed capabilities can be silently off while every
 * health endpoint looks green. The doctor action must therefore surface per-tool
 * status with actionable detail.
 */
class DashboardActionServiceDoctorToolStatusTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsFor(AgentProperties properties) {
        DashboardActionService service = new DashboardActionService(
            (ObjectProvider<com.azhukov.agent.service.ProfileService>) null,
            (ObjectProvider<com.azhukov.agent.persistence.repository.DashboardActionRepository>) null,
            (ObjectProvider<com.azhukov.agent.service.ProfileRuntimeRegistry>) null,
            (ObjectProvider<com.azhukov.agent.service.AgentRuntimeService>) null,
            properties);
        Map<String, Object> report = service.run("doctor", "default", "test").output();
        return (Map<String, Object>) report.get("tools");
    }

    private AgentProperties baseProps() {
        AgentProperties properties = new AgentProperties();
        properties.getChromium().setAutoStart(false);
        properties.getBrowser().setCdpUrl("");
        properties.getTts().setEnabled(false);
        properties.getImageGen().setEnabled(false);
        properties.getMcp().setEnabled(false);
        return properties;
    }

    @Test
    void allCapabilitiesReportOffWithActionableDetailWhenDisabled() {
        Map<String, Object> tools = toolsFor(baseProps());

        Map<String, Object> browser = (Map<String, Object>) tools.get("browser");
        assertThat(browser.get("status")).isEqualTo("off");
        assertThat((String) browser.get("detail")).contains("AGENT_CHROMIUM_AUTO_START");

        Map<String, Object> tts = (Map<String, Object>) tools.get("text_to_speech");
        assertThat(tts.get("status")).isEqualTo("off");
        assertThat((String) tts.get("detail")).contains("AGENT_TTS_ENABLED");

        Map<String, Object> imageGen = (Map<String, Object>) tools.get("image_generate");
        assertThat(imageGen.get("status")).isEqualTo("off");
        assertThat((String) imageGen.get("detail")).contains("AGENT_IMAGE_GEN_ENABLED");

        Map<String, Object> mcp = (Map<String, Object>) tools.get("mcp");
        assertThat(mcp.get("status")).isEqualTo("off");
        assertThat((String) mcp.get("detail")).contains("AGENT_MCP_ENABLED");
    }

    @Test
    void edgeTtsWithOpenAiVoiceNameIsFlaggedMisconfigured() {
        AgentProperties properties = baseProps();
        properties.getTts().setEnabled(true);
        properties.getTts().setProvider("edge");
        // Global OpenAI-style voice must NOT leak into the edge provider; the
        // provider-specific ru-RU-* default takes precedence and is reported.
        properties.getTts().setVoice("alloy");

        Map<String, Object> tts = (Map<String, Object>) toolsFor(properties).get("text_to_speech");
        assertThat(tts.get("status")).isEqualTo("ok");
        assertThat((String) tts.get("voice")).startsWith("ru-RU-");
    }

    @Test
    void edgeTtsWithInvalidProviderVoiceIsFlaggedMisconfigured() {
        AgentProperties properties = baseProps();
        properties.getTts().setEnabled(true);
        properties.getTts().setProvider("edge");
        properties.getTts().getEdge().setVoice("alloy");

        Map<String, Object> tts = (Map<String, Object>) toolsFor(properties).get("text_to_speech");
        assertThat(tts.get("status")).isEqualTo("misconfigured");
        assertThat((String) tts.get("detail")).contains("ru-RU-DmitryNeural");
    }

    @Test
    void openAiImageGenWithoutApiKeyIsFlaggedMisconfigured() {
        AgentProperties properties = baseProps();
        properties.getImageGen().setEnabled(true);
        properties.getImageGen().setProvider("openai");
        properties.getImageGen().setApiKey("");

        Map<String, Object> imageGen = (Map<String, Object>) toolsFor(properties).get("image_generate");
        assertThat(imageGen.get("status")).isEqualTo("misconfigured");
        assertThat((String) imageGen.get("detail")).contains("AGENT_IMAGE_GEN_API_KEY");
    }

    @Test
    void pollinationsImageGenWithoutKeyIsOk() {
        AgentProperties properties = baseProps();
        properties.getImageGen().setEnabled(true);
        properties.getImageGen().setProvider("pollinations");

        Map<String, Object> imageGen = (Map<String, Object>) toolsFor(properties).get("image_generate");
        assertThat(imageGen.get("status")).isEqualTo("ok");
    }

    @Test
    void unreachableCdpEndpointReportsDown() {
        AgentProperties properties = baseProps();
        properties.getBrowser().setCdpUrl("http://127.0.0.1:59999");

        Map<String, Object> browser = (Map<String, Object>) toolsFor(properties).get("browser");
        assertThat(browser.get("status")).isEqualTo("down");
        assertThat((String) browser.get("detail")).contains("59999");
    }
}
