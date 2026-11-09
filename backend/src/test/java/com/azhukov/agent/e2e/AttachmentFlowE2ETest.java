package com.azhukov.agent.e2e;

import com.azhukov.agent.persistence.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
class AttachmentFlowE2ETest extends PostgresTestContainer {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.azhukov.agent.persistence.repository.MessageRepository messageRepository;

    @Autowired
    private com.azhukov.agent.persistence.repository.SessionRepository sessionRepository;

    @Autowired
    private com.azhukov.agent.service.AttachmentArtifactService artifactService;

    @Autowired
    private org.springframework.web.context.WebApplicationContext webApplicationContext;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.azhukov.agent.service.ArtifactProcessIsolation artifactProcessIsolation;

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
    void firstTurnDoesNotInjectOtherOwnersAttachment() throws Exception {
        String owner = "owner-" + UUID.randomUUID();
        String other = "other-" + UUID.randomUUID();
        String payload = "private-" + UUID.randomUUID();
        String foreignId = uploadTextArtifact("private.txt", payload, other);
        String ownId = uploadTextArtifact("own.txt", "owner-content", owner);
        var response = mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"message":"inspect","userId":"%s","attachments":[{"artifactId":"%s"},{"artifactId":"%s"}]}
                    """.formatted(owner, foreignId, ownId)))
            .andExpect(status().isOk()).andReturn();
        UUID sessionId = UUID.fromString(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(response.getResponse().getContentAsString()).path("sessionId").asText());
        String persisted = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
            .filter(m -> "user".equals(m.getRole())).findFirst().orElseThrow().getContent();
        assertThat(persisted).contains("owner-content", "[unavailable: " + foreignId + "]").doesNotContain(payload);
    }

    @Test
    void repeatTurnUsesSessionOwnerForAttachmentEvenWhenRequestNamesAnother() throws Exception {
        String owner = "repeat-owner-" + UUID.randomUUID();
        String other = "repeat-other-" + UUID.randomUUID();
        String secret = "private-" + UUID.randomUUID();
        String foreign = uploadTextArtifact("private.txt", secret, other);
        String own = uploadTextArtifact("own.txt", "owner-repeat-content", owner);
        var session = new com.azhukov.agent.persistence.entity.SessionEntity();
        session.setUserId(owner);
        session.setModelProvider("noop");
        session.setModelName("noop");
        session = sessionRepository.saveAndFlush(session);
        var result = mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","message":"inspect repeat","userId":"%s","attachments":[{"artifactId":"%s"},{"artifactId":"%s"}]}
                    """.formatted(session.getId(), other, foreign, own)))
            .andExpect(status().isOk()).andReturn();
        assertThat(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(result.getResponse().getContentAsString()).path("sessionId").asText()).isEqualTo(session.getId().toString());
        String persisted = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
            .filter(m -> "user".equals(m.getRole())).findFirst().orElseThrow().getContent();
        assertThat(persisted).contains("owner-repeat-content", "[unavailable: " + foreign + "]").doesNotContain(secret);
    }

    @Test
    void streamingFirstTurnScopesAttachmentsBeforePersisting() throws Exception {
        String owner = "stream-owner-" + UUID.randomUUID();
        String other = "stream-other-" + UUID.randomUUID();
        String secret = "private-" + UUID.randomUUID();
        String foreign = uploadTextArtifact("private.txt", secret, other);
        String own = uploadTextArtifact("own.txt", "own-stream-content", owner);
        var started = mockMvc.perform(post("/api/v1/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"message":"inspect","userId":"%s","attachments":[{"artifactId":"%s"},{"artifactId":"%s"}]}
                    """.formatted(owner, foreign, own)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
            .andReturn();
        started.getAsyncResult(30_000);
        var completed = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
            .andExpect(status().isOk()).andReturn();
        String events = completed.getResponse().getContentAsString();
        var sessions = sessionRepository.findAllByUserId(owner, org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(sessions).hasSize(1);
        String persisted = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessions.getContent().get(0).getId()).stream()
            .filter(m -> "user".equals(m.getRole())).findFirst().orElseThrow().getContent();
        assertThat(persisted).contains("own-stream-content", "[unavailable: " + foreign + "]").doesNotContain(secret);
        assertThat(events).doesNotContain(secret);
    }

    @Test
    void apiKeyFilterCannotImpersonatePersistedSessionOwner() throws Exception {
        String authenticatedUser = "http-owner-" + UUID.randomUUID();
        String requestedUser = "different-owner-" + UUID.randomUUID();
        var access = org.mockito.Mockito.mock(com.azhukov.agent.service.UserAccessService.class);
        org.mockito.Mockito.when(access.authenticate("fixture-user-key")).thenReturn(
            new com.azhukov.agent.service.UserAccessService.AuthenticatedUser(authenticatedUser, "user"));
        var properties = new com.azhukov.agent.config.AgentProperties();
        properties.getSecurity().setApiKey("fixture-global-key");
        var filter = new com.azhukov.agent.api.filter.ApiKeyAuthFilter(properties, access);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .webAppContextSetup(webApplicationContext).addFilters(filter).build();
        var result = mvc.perform(post("/api/v1/agent/chat").header("X-API-Key", "fixture-user-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"inspect\",\"userId\":\"" + requestedUser + "\"}"))
            .andExpect(status().isOk()).andReturn();
        UUID sessionId = UUID.fromString(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(result.getResponse().getContentAsString()).path("sessionId").asText());
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getUserId()).isEqualTo(authenticatedUser);
        assertThat(com.azhukov.agent.core.security.UserContext.getUserId()).isNull();
    }

    @Test
    void authenticatedUserCannotUseForeignSessionToReadAttachments() throws Exception {
        String owner = "session-owner-" + UUID.randomUUID();
        String attacker = "caller-" + UUID.randomUUID();
        String payload = "secret-" + UUID.randomUUID();
        String artifact = uploadTextArtifact("private.txt", payload, owner);
        var session = new com.azhukov.agent.persistence.entity.SessionEntity();
        session.setUserId(owner);
        session.setModelProvider("noop");
        session.setModelName("noop");
        session = sessionRepository.saveAndFlush(session);
        var users = org.mockito.Mockito.mock(com.azhukov.agent.service.UserAccessService.class);
        org.mockito.Mockito.when(users.authenticate("fixture-user-key")).thenReturn(
            new com.azhukov.agent.service.UserAccessService.AuthenticatedUser(attacker, "user"));
        var properties = new com.azhukov.agent.config.AgentProperties();
        properties.getSecurity().setApiKey("fixture-global-key");
        var filter = new com.azhukov.agent.api.filter.ApiKeyAuthFilter(properties, users);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .webAppContextSetup(webApplicationContext).addFilters(filter).build();
        var response = mvc.perform(post("/api/v1/agent/chat").header("X-API-Key", "fixture-user-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","message":"inspect","userId":"%s","attachments":[{"artifactId":"%s"}]}
                    """.formatted(session.getId(), owner, artifact)))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isIn(403, 404);
        assertThat(response.getContentAsString()).doesNotContain(payload);
        assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).isEmpty();
        assertThat(com.azhukov.agent.core.security.UserContext.getUserId()).isNull();
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
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getUserId()).isEqualTo(ownerId);
        assertThat(persisted).contains("[Attachments]");
        assertThat(persisted).contains(TEXT_PAYLOAD);
        assertThat(persisted).contains("notes.txt");

        // 4) by-session listing: the chat-time artifact was registered before
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
    void gatewayCanClaimExactSessionOnlyOnceAndRecordReceipt() throws Exception {
        org.mockito.Mockito.when(artifactProcessIsolation.protects(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        String owner = "701938011";
        UUID session = UUID.randomUUID();
        String payload = "outbound fixture " + session;
        String id = uploadTextArtifactWithSession("report.txt", payload, owner, session);
        var properties = new com.azhukov.agent.config.AgentProperties();
        properties.getSecurity().setApiKey("fixture-gateway-key");
        var gateway = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(new com.azhukov.agent.api.filter.ApiKeyAuthFilter(properties)).build();

        gateway.perform(post("/api/v1/attachments/{id}/outbound", id)
                .header("X-API-Key", "fixture-gateway-key").param("ownerId", "701938012")
                .param("sessionId", session.toString()))
            .andExpect(status().isNotFound());
        gateway.perform(post("/api/v1/attachments/{id}/outbound", id)
                .header("X-API-Key", "fixture-gateway-key").param("ownerId", owner)
                .param("sessionId", UUID.randomUUID().toString()))
            .andExpect(status().isNotFound());
        gateway.perform(post("/api/v1/attachments/{id}/delivered", id)
                .header("X-API-Key", "fixture-gateway-key").param("ownerId", owner).param("sessionId", session.toString())
                .param("messageId", "419"))
            .andExpect(status().isConflict());
        var response = gateway.perform(post("/api/v1/attachments/{id}/outbound", id)
                .header("X-API-Key", "fixture-gateway-key").param("ownerId", owner).param("sessionId", session.toString()))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));

        gateway.perform(post("/api/v1/attachments/{id}/outbound", id)
                .header("X-API-Key", "fixture-gateway-key").param("ownerId", owner).param("sessionId", session.toString()))
            .andExpect(status().isConflict());
        gateway.perform(post("/api/v1/attachments/{id}/delivered", id)
                .header("X-API-Key", "fixture-gateway-key").param("ownerId", owner).param("sessionId", session.toString())
                .param("messageId", "420"))
            .andExpect(status().isOk());
        gateway.perform(post("/api/v1/attachments/{id}/delivered", id)
                .header("X-API-Key", "fixture-gateway-key").param("ownerId", owner).param("sessionId", session.toString())
                .param("messageId", "420"))
            .andExpect(status().isOk());
        gateway.perform(post("/api/v1/attachments/{id}/delivered", id)
                .header("X-API-Key", "fixture-gateway-key").param("ownerId", owner).param("sessionId", session.toString())
                .param("messageId", "421"))
            .andExpect(status().isConflict());
        assertThat(artifactService.find(id, owner).orElseThrow().deliveredMessageId()).isEqualTo("420");
    }

    @Test
    void separatePublicationsOfIdenticalBytesCanEachBeClaimedOnce() {
        var session = new com.azhukov.agent.core.model.Session(UUID.randomUUID(), "701938019",
            "fixture", "noop", "fixture", null, java.util.Map.of());
        byte[] bytes = "same generated bytes".getBytes(StandardCharsets.UTF_8);
        String first = artifactService.publishGenerated(session, "document", "text/plain", "report.txt", bytes)
            .substring("MEDIA:artifact:".length());
        String second = artifactService.publishGenerated(session, "document", "text/plain", "report.txt", bytes)
            .substring("MEDIA:artifact:".length());

        assertThat(second).isNotEqualTo(first);
        assertThat(artifactService.claimOutbound(first, session.userId(), session.id())).isTrue();
        assertThat(artifactService.claimOutbound(first, session.userId(), session.id())).isFalse();
        assertThat(artifactService.claimOutbound(second, session.userId(), session.id())).isTrue();
        assertThat(artifactService.verifiedContent(second, session.userId())).hasValueSatisfying(
            content -> assertThat(content.bytes()).isEqualTo(bytes));
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
