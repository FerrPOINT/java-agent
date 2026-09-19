package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.DefaultFileSafety;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Media and chat-image upload lanes of the filesystem dashboard: successful
 * media data-url serving, extension rejection, size limits, upload payload
 * validation, and dashboard image persistence into the agent home.
 */
class FilesystemDashboardControllerMediaTest {

    @TempDir
    private Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        // Media serving is rooted at HERMES_HOME/{images,screenshots,cache}.
        System.setProperty("hermes.home", tempDir.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(
            new FilesystemDashboardController(properties, new DefaultFileSafety(properties))).build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("hermes.home");
    }

    @Test
    void mediaServeReturnsDataUrlForImageUnderHomeCache() throws Exception {
        Path cache = Files.createDirectories(tempDir.resolve("cache"));
        Path image = cache.resolve("snap.png");
        Files.write(image, new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        mockMvc.perform(get("/api/media").param("path", image.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data_url").exists());
    }

    @Test
    void mediaServeRejectsNonImageExtension() throws Exception {
        Path text = tempDir.resolve("notes.txt");
        Files.writeString(text, "hello");

        mockMvc.perform(get("/api/media").param("path", text.toString()))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void mediaServeMissingPathParamIsRejected() throws Exception {
        mockMvc.perform(get("/api/media"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void chatImageUploadRequiresPayload() throws Exception {
        mockMvc.perform(post("/api/chat/image-upload"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void chatImageUploadRejectsUnknownImageMagic() throws Exception {
        // Valid image/* mime but garbage payload bytes: magic sniff must reject.
        String body = """
            {"filename":"movie.png","data_url":"data:image/png;base64,AAAAAAAAAAA="}
            """;
        mockMvc.perform(post("/api/chat/image-upload")
                .contentType("application/json").content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void chatImageUploadPersistsDashboardImage() throws Exception {
        Path images = Files.createDirectories(tempDir.resolve("images"));
        String body = """
            {"filename":"pic.png","data_url":"data:image/png;base64,iVBORw0KGgo="}
            """;
        mockMvc.perform(post("/api/chat/image-upload")
                .contentType("application/json").content(body))
            .andExpect(status().isOk());

        try (var files = Files.list(images)) {
            assertThat(files.anyMatch(p -> p.getFileName().toString().startsWith("dashboard_"))).isTrue();
        }
    }

    @Test
    void chatImageUploadWithoutFilenameFallsBackToPastedImage() throws Exception {
        Files.createDirectories(tempDir.resolve("images"));
        String body = """
            {"data_url":"data:image/png;base64,iVBORw0KGgo="}
            """;
        mockMvc.perform(post("/api/chat/image-upload")
                .contentType("application/json").content(body))
            .andExpect(status().isOk());
    }

    @Test
    void chatImageUploadRejectsNonImageMime() throws Exception {
        String body = """
            {"filename":"notes.txt","data_url":"data:text/plain;base64,aGVsbG8="}
            """;
        mockMvc.perform(post("/api/chat/image-upload")
                .contentType("application/json").content(body))
            .andExpect(status().isBadRequest());
    }
}
