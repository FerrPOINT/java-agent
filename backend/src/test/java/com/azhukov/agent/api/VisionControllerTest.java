package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.VisionRequest;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.tools.browser.BrowserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class VisionControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private BrowserService browserService;

    @Mock
    private ModelClient modelClient;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        VisionController controller = new VisionController(browserService, modelClient);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    @Test
    void visionReturnsAnalysisText() throws Exception {
        when(browserService.navigate("https://example.com")).thenReturn("Navigated to https://example.com");
        when(browserService.screenshot()).thenReturn("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
        when(modelClient.analyzeImage(eq("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="), eq("Describe the page")))
            .thenReturn("A simple placeholder image.");

        VisionRequest request = new VisionRequest("https://example.com", "Describe the page", null);

        mockMvc.perform(post("/api/v1/agent/vision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN_VALUE))
            .andExpect(content().string("A simple placeholder image."));
    }

    @Test
    void invalidBase64UrlReturns400() throws Exception {
        when(browserService.navigate("https://example.com")).thenReturn("Navigated to https://example.com");
        when(browserService.screenshot()).thenReturn("data:image/png;base64,not-valid-base64!!!");
        when(modelClient.analyzeImage(any(), any())).thenThrow(new IllegalArgumentException("Invalid base64 image data"));

        String requestBody = """
            {
              "url": "https://example.com",
              "prompt": "Analyze this"
            }
            """;

        mockMvc.perform(post("/api/v1/agent/vision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("bad_request"))
            .andExpect(jsonPath("$.error").value("Invalid base64 image data"));
    }

    @Test
    void modelErrorReturns500() throws Exception {
        when(browserService.navigate("https://example.com")).thenReturn("Navigated to https://example.com");
        when(browserService.screenshot()).thenReturn("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
        when(modelClient.analyzeImage(any(), any())).thenThrow(new RuntimeException("vision model failed"));

        String requestBody = """
            {
              "url": "https://example.com",
              "prompt": "What do you see?"
            }
            """;

        mockMvc.perform(post("/api/v1/agent/vision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.type").value("internal"))
            .andExpect(jsonPath("$.error").value("Internal error: vision model failed"));
    }
}
