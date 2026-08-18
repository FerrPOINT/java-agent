package com.azhukov.agent.tools.browser;

import com.azhukov.agent.security.UrlSafety;
import com.azhukov.agent.security.Redactor;
import com.azhukov.agent.security.DefaultUrlSafety;
import com.azhukov.agent.security.DefaultRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_BROWSER_TEST", matches = "true")
@Tag("live")
class BrowserToolsLiveTest {

    @Test
    void navigateAndScreenshot() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CdpClient cdp = new CdpClient(mapper);
        UrlSafety guard = new DefaultUrlSafety(new com.azhukov.agent.config.AgentProperties());
        BrowserService service = new BrowserService(cdp, () -> "http://localhost:9222", guard);
        String result = service.navigate("http://example.com");
        assertTrue(result.contains("Navigated to"), result);
        String screenshot = service.screenshot();
        assertTrue(screenshot.startsWith("data:image/png;base64,"), screenshot);
        assertTrue(screenshot.length() > 1000, "screenshot too small: " + screenshot.length());
        System.out.println("navigate result: " + result);
        System.out.println("screenshot length: " + screenshot.length());
    }
}
