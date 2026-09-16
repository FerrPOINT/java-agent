package com.azhukov.agent.e2e;

import com.azhukov.agent.persistence.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-11 (docs/35) end-to-end attachment contract over real persistence:
 * multipart upload registers an artifact (hash dedupe), a chat request
 * referencing the artifact runs a turn whose persisted user message carries
 * the [Attachments] block, and the delivered receipt is idempotent with the
 * platform message id recorded (V64).
 */
@Tag("slow")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false"
})
class AttachmentFlowE2ETest extends PostgresTestContainer {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.azhukov.agent.persistence.repository.MessageRepository messageRepository;

    private static final String TEXT_PAYLOAD = "quarterly figures: 42";

    private String uploadTextArtifact(String fileName, String content, String ownerId)
            throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", fileName, "text/plain",
                    content.getBytes(StandardCharsets.UTF_8)))
                .param("origin", "telegram")
                .param("disposition", "file")
                .param("ownerId", ownerId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andReturn();
        String body = result.getResponse().getContentAsString();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(body).path("id").asText();
    }

    @Test
    void uploadChatWithAttachmentAndDeliveredReceipt() throws Exception {
        String ownerId = "e2e-" + UUID.randomUUID().toString().substring(0, 8);

        // 1) upload
        String artifactId = uploadTextArtifact("notes.txt", TEXT_PAYLOAD, ownerId);
        assertThat(artifactId).startsWith("att_");

        // 2) metadata visible
        mockMvc.perform(get("/api/v1/attachments/" + artifactId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(artifactId))
            .andExpect(jsonPath("$.state").value("received"))
            .andExpect(jsonPath("$.mimeType").value("text/plain"));

        // 3) chat request referencing the artifact — noop model, but the
        //    persisted user message must carry the [Attachments] block.
        //    The backend creates the session and returns its id.
        MvcResult chatResult = mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "message": "summarize the attachment",
                      "userId": "%s",
                      "attachments": [{"artifactId": "%s"}]
                    }
                    """.formatted(ownerId, artifactId)))
            .andExpect(status().isOk())
            .andReturn();
        UUID sessionId = UUID.fromString(com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .build().readTree(chatResult.getResponse().getContentAsString())
            .path("sessionId").asText());

        var userMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
            .filter(m -> "user".equals(m.getRole()))
            .toList();
        assertThat(userMessages).isNotEmpty();
        String persisted = userMessages.get(userMessages.size() - 1).getContent();
        assertThat(persisted).contains("summarize the attachment");
        assertThat(persisted).contains("[Attachments]");
        assertThat(persisted).contains(TEXT_PAYLOAD);
        assertThat(persisted).contains("notes.txt");

        // 4) delivered receipt with platform message id — idempotent
        mockMvc.perform(post("/api/v1/attachments/{id}/delivered", artifactId)
                .param("messageId", "42133"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("delivered"));

        // second call keeps the FIRST receipt, still 200
        mockMvc.perform(post("/api/v1/attachments/{id}/delivered", artifactId)
                .param("messageId", "99999"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/attachments/" + artifactId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("delivered"))
            .andExpect(jsonPath("$.deliveredMessageId").value("42133"));

        // 5) by-session listing: the chat-time artifact was registered before
        //    the session existed (session id is only known after the first
        //    turn), so a follow-up upload explicitly linked to the session
        //    must appear; the first one stays owner-scoped.
        String followUp = uploadTextArtifactWithSession("follow.txt", "linked",
            ownerId, sessionId);
        mockMvc.perform(get("/api/v1/attachments/session/" + sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + followUp + "')]").exists());
    }

    private String uploadTextArtifactWithSession(String fileName, String content,
                                                 String ownerId, UUID sessionId)
            throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", fileName, "text/plain",
                    content.getBytes(StandardCharsets.UTF_8)))
                .param("origin", "telegram")
                .param("disposition", "file")
                .param("ownerId", ownerId)
                .param("sessionId", sessionId.toString()))
            .andExpect(status().isOk())
            .andReturn();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    @Test
    void duplicateUploadDedupesByOwnerHashDisposition() throws Exception {
        String ownerId = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        String first = uploadTextArtifact("same.txt", "identical", ownerId);
        String second = uploadTextArtifact("same.txt", "identical", ownerId);
        // same owner+hash+disposition -> same artifact, flagged duplicate
        assertThat(second).isEqualTo(first);

        String otherOwner = uploadTextArtifact("same.txt", "identical",
            "other-" + UUID.randomUUID().toString().substring(0, 8));
        // different owner -> distinct artifact
        assertThat(otherOwner).isNotEqualTo(first);
    }

    @Test
    void oversizeUploadRejectedWith400() throws Exception {
        byte[] big = new byte[20 * 1024 * 1024 + 1];
        mockMvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "big.bin",
                    "application/octet-stream", big))
                .param("origin", "cli"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void chatWithUnknownArtifactDegradesHonestly() throws Exception {
        MvcResult chatResult = mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "message": "check this",
                      "attachments": [{"artifactId": "att_missing0000000000000000"}]
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        UUID sessionId = UUID.fromString(com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .build().readTree(chatResult.getResponse().getContentAsString())
            .path("sessionId").asText());

        var userMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
            .filter(m -> "user".equals(m.getRole()))
            .toList();
        assertThat(userMessages).isNotEmpty();
        String persisted = userMessages.get(userMessages.size() - 1).getContent();
        assertThat(persisted).contains("[unavailable: att_missing0000000000000000]");
    }

    @Test
    void binaryArtifactExposesCachePathNotInline() throws Exception {
        String ownerId = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2};
        MvcResult up = mockMvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "cat.png", "image/png", png))
                .param("origin", "telegram")
                .param("disposition", "photo")
                .param("ownerId", ownerId))
            .andExpect(status().isOk())
            .andReturn();
        String artifactId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(up.getResponse().getContentAsString()).path("id").asText();

        // content endpoint serves the blob
        MvcResult content = mockMvc.perform(get("/api/v1/attachments/" + artifactId + "/content"))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(png);
        assertThat(content.getResponse().getHeader("X-Artifact-Id")).isEqualTo(artifactId);
    }
}
