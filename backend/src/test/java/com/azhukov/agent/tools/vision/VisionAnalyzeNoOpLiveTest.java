package com.azhukov.agent.tools.vision;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.ImageShrinkerService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_BROWSER_TEST", matches = "true")
@Tag("live")
class VisionAnalyzeNoOpLiveTest {

    @Test
    void noOpVisionReturnsPlaceholder() {
        VisionAnalyzeTool tool = new VisionAnalyzeTool(new NoOpModelClient(), new ImageShrinkerService(new AgentProperties()));
        var result = tool.execute(
            "{\"image\":\"https://www.google.com/images/branding/googlelogo/1x/googlelogo_color_272x92dp.png\",\"prompt\":\"describe\"}",
            null,
            null
        );
        assertTrue(result.success(), result.error());
        assertTrue(result.content().startsWith("NoOp vision:"), result.content());
        System.out.println("vision result: " + result.content());
    }
}
