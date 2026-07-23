package com.azhukov.agent.tools.vision;

import com.azhukov.agent.client.NoOpModelClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_BROWSER_TEST", matches = "true")
class VisionAnalyzeNoOpLiveTest {

    @Test
    void noOpVisionReturnsPlaceholder() {
        VisionAnalyzeTool tool = new VisionAnalyzeTool(new NoOpModelClient());
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
