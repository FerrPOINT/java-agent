package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.VisionRequest;
import com.azhukov.agent.client.langchain4j.LangChain4jModelClient;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.tools.browser.BrowserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class VisionController {

    private final BrowserService browserService;
    private final ModelClient modelClient;

    @PostMapping(value = "/agent/vision", produces = MediaType.TEXT_PLAIN_VALUE)
    public String vision(@Valid @RequestBody VisionRequest request) throws Exception {
        browserService.navigate(request.url());
        String dataUrl = browserService.screenshot();
        String base64 = dataUrl.substring(dataUrl.indexOf(",") + 1);
        return modelClient.analyzeImage(base64, request.prompt());
    }
}
