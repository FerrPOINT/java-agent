package com.azhukov.agent.tools.vision;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VisionAnalyzeToolTest {

    @Test
    void analyzesLocalFile(@TempDir Path dir) throws Exception {
        Path img = dir.resolve("x.png");
        Files.write(img, new byte[]{1, 2, 3});
        ModelClient client = mock(ModelClient.class);
        when(client.analyzeImage(anyString(), eq("prompt"))).thenReturn("description");
        VisionAnalyzeTool t = new VisionAnalyzeTool(client);
        ToolResult r = t.execute("{\"image\":\"" + img + "\",\"prompt\":\"prompt\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
        assertThat(r.content()).isEqualTo("description");
    }

    @Test
    void missingImageFails() {
        ModelClient client = mock(ModelClient.class);
        VisionAnalyzeTool t = new VisionAnalyzeTool(client);
        ToolResult r = t.execute("{}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void missingFileFails() {
        ModelClient client = mock(ModelClient.class);
        VisionAnalyzeTool t = new VisionAnalyzeTool(client);
        ToolResult r = t.execute("{\"image\":\"/tmp/nonexistent.png\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }
}
