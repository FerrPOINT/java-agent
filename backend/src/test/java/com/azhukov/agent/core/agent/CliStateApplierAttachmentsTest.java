package com.azhukov.agent.core.agent;

import com.azhukov.agent.api.dto.AttachmentRef;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.service.AttachmentArtifactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * WP-11 (docs/35): ChatRequest.attachments resolve to a bounded [Attachments]
 * block appended to the merged user message — text artifacts inline a
 * preview, binary artifacts expose only the safe cache path, unknown ids
 * degrade honestly, and the service being absent changes nothing.
 */
class CliStateApplierAttachmentsTest {

    @TempDir
    Path tempDir;

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

    private static final UUID SESSION_ID = UUID.randomUUID();

    private SessionEntity session() {
        SessionEntity entity = new SessionEntity();
        entity.setId(SESSION_ID);
        return entity;
    }

    private ChatRequest requestWith(List<AttachmentRef> attachments) {
        return new ChatRequest(SESSION_ID, "look at this", null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, attachments);
    }

    private AttachmentArtifactService.AttachmentArtifact artifact(String id, String mime, String name) {
        return new AttachmentArtifactService.AttachmentArtifact(
            id, "u1", "default", SESSION_ID, null, "hash" + id, "telegram",
            "file", mime, name, 11, "received", null, null);
    }

    @Test
    void noAttachmentsLeaveMessageUnchanged() {
        CliStateApplier applier = new CliStateApplier(prov(null));
        ChatRequest out = applier.applyCliState(requestWith(null), session());
        assertThat(out.message()).isEqualTo("look at this");
    }

    @Test
    void textArtifactInlinesBoundedPreview() throws Exception {
        Path textFile = tempDir.resolve("notes.txt");
        Files.writeString(textFile, "hello attachment");

        AttachmentArtifactService service = Mockito.mock(AttachmentArtifactService.class);
        Mockito.when(service.find("att_text")).thenReturn(
            Optional.of(artifact("att_text", "text/plain", "notes.txt")));
        Mockito.when(service.contentPath("att_text")).thenReturn(Optional.of(textFile));

        CliStateApplier applier = new CliStateApplier(prov(service));
        ChatRequest out = applier.applyCliState(
            requestWith(List.of(AttachmentRef.of("att_text"))), session());

        assertThat(out.message())
            .contains("look at this")
            .contains("[Attachments]")
            .contains("notes.txt")
            .contains("hello attachment");
    }

    @Test
    void binaryArtifactExposesSafeCachePathOnly() throws Exception {
        Path image = tempDir.resolve("cat.png");
        Files.write(image, new byte[] {1, 2, 3});

        AttachmentArtifactService service = Mockito.mock(AttachmentArtifactService.class);
        Mockito.when(service.find("att_img")).thenReturn(
            Optional.of(artifact("att_img", "image/png", "cat.png")));
        Mockito.when(service.contentPath("att_img")).thenReturn(Optional.of(image));

        CliStateApplier applier = new CliStateApplier(prov(service));
        ChatRequest out = applier.applyCliState(
            requestWith(List.of(AttachmentRef.of("att_img"))), session());

        assertThat(out.message())
            .contains("[Attachment: cat.png | image/png | 11 bytes")
            .contains("content at " + image);
        // no preview inlined for binary
        assertThat(out.message()).doesNotContain("[...truncated");
    }

    @Test
    void unknownArtifactIdDegradesHonestly() {
        AttachmentArtifactService service = Mockito.mock(AttachmentArtifactService.class);
        Mockito.when(service.find(anyString())).thenReturn(Optional.empty());

        CliStateApplier applier = new CliStateApplier(prov(service));
        ChatRequest out = applier.applyCliState(
            requestWith(List.of(AttachmentRef.of("att_missing"))), session());

        assertThat(out.message()).contains("[unavailable: att_missing]");
    }

    @Test
    void invalidRefsAreSkippedEntirely() {
        AttachmentArtifactService service = Mockito.mock(AttachmentArtifactService.class);
        CliStateApplier applier = new CliStateApplier(prov(service));
        ChatRequest out = applier.applyCliState(
            requestWith(java.util.Arrays.asList(new AttachmentRef("not-an-id", null, null), null)), session());

        assertThat(out.message()).isEqualTo("look at this");
        Mockito.verifyNoInteractions(service);
    }

    @Test
    void longTextPreviewIsTruncated() throws Exception {
        Path textFile = tempDir.resolve("big.txt");
        Files.writeString(textFile, "x".repeat(10_000));

        AttachmentArtifactService service = Mockito.mock(AttachmentArtifactService.class);
        Mockito.when(service.find("att_big")).thenReturn(
            Optional.of(artifact("att_big", "text/plain", "big.txt")));
        Mockito.when(service.contentPath("att_big")).thenReturn(Optional.of(textFile));

        CliStateApplier applier = new CliStateApplier(prov(service));
        ChatRequest out = applier.applyCliState(
            requestWith(List.of(AttachmentRef.of("att_big"))), session());

        assertThat(out.message()).contains("[...truncated ");
        assertThat(out.message().length()).isLessThan(10_000);
    }

    @Test
    void attachmentsSurviveNullSessionUntouched() {
        // session == null returns the request unchanged (existing contract)
        CliStateApplier applier = new CliStateApplier(prov(null));
        ChatRequest request = requestWith(List.of(AttachmentRef.of("att_x")));
        ChatRequest out = applier.applyCliState(request, null);
        assertThat(out).isSameAs(request);
    }
}
