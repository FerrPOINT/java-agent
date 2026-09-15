package com.azhukov.agent.api;

import com.azhukov.agent.service.AttachmentArtifactService;
import com.azhukov.agent.service.AttachmentArtifactService.ArtifactRegistration;
import com.azhukov.agent.service.AttachmentArtifactService.AttachmentArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-11 (docs/35): attachment REST surface — upload registers an artifact,
 * metadata/content/delivered/by-session are real behaviors backed by the
 * artifact service, honest 501 when the service bean is absent.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentControllerTest {

    @Mock
    private AttachmentArtifactService service;

    private MockMvc mockMvc;

    private static <T> ObjectProvider<T> prov(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    @BeforeEach
    void setUp() {
        AttachmentController controller = new AttachmentController(prov(service));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .defaultResponseCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)
            .build();
    }

    @Test
    void uploadRegistersArtifactAndReturnsId() throws Exception {
        when(service.register(any(), any(), any(), any(), anyString(), anyString(),
            any(), any(), any())).thenReturn(
            new ArtifactRegistration("att_123", "abc123", "owner/att_123.png", false, null));

        mockMvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1, 2, 3})))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("att_123"))
            .andExpect(jsonPath("$.contentHash").value("abc123"))
            .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void uploadRejectsEmptyFilePart() throws Exception {
        mockMvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "x.png", "image/png", new byte[0])))
            .andExpect(status().isBadRequest());
    }

    @Test
    void uploadMapsServiceRejectionTo400() throws Exception {
        when(service.register(any(), any(), any(), any(), anyString(), anyString(),
            any(), any(), any())).thenReturn(
            new ArtifactRegistration(null, null, null, false, "attachment exceeds 20971520 bytes"));

        mockMvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "big.png", "image/png", new byte[] {1})))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsArtifactMetadata() throws Exception {
        when(service.find("att_1")).thenReturn(Optional.of(new AttachmentArtifact(
            "att_1", "u1", "default", null, null, "hash", "telegram",
            "file", "image/png", "cat.png", 3, "received", null, null)));

        mockMvc.perform(get("/api/v1/attachments/att_1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("att_1"))
            .andExpect(jsonPath("$.mimeType").value("image/png"))
            .andExpect(jsonPath("$.fileName").value("cat.png"));
    }

    @Test
    void getUnknownArtifactIs404() throws Exception {
        when(service.find("att_missing")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/attachments/att_missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    void deliveredMarksAndIsIdempotent() throws Exception {
        when(service.markDelivered("att_1", null)).thenReturn(true);
        mockMvc.perform(post("/api/v1/attachments/att_1/delivered"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("delivered"));
    }

    @Test
    void deliveredUnknownArtifactIs404() throws Exception {
        when(service.markDelivered("att_missing", null)).thenReturn(false);
        mockMvc.perform(post("/api/v1/attachments/att_missing/delivered"))
            .andExpect(status().isNotFound());
    }

    @Test
    void bySessionDelegates() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(service.bySession(sessionId)).thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/v1/attachments/session/" + sessionId))
            .andExpect(status().isOk());
    }

    @Test
    void absentServiceIsHonest501() throws Exception {
        AttachmentController bare = new AttachmentController(prov(null));
        MockMvc bareMvc = MockMvcBuilders.standaloneSetup(bare).build();
        bareMvc.perform(get("/api/v1/attachments/att_1"))
            .andExpect(status().isNotImplemented());
    }

    @Test
    void sanitizesHostileFileName() throws Exception {
        // The controller must never pass a raw client filename with path
        // separators or unsafe characters into the service.
        org.mockito.ArgumentCaptor<String> nameCaptor =
            org.mockito.ArgumentCaptor.forClass(String.class);
        when(service.register(any(), any(), any(), any(), anyString(), anyString(),
            any(), nameCaptor.capture(), any())).thenReturn(
            new ArtifactRegistration("att_x", "h", "p", false, null));

        mockMvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "../../etc/passwd", "application/octet-stream", new byte[] {1})))
            .andExpect(status().isOk());

        String captured = nameCaptor.getValue();
        assertThat(captured).doesNotContain("..").doesNotContain("/");
    }
}
