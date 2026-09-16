package com.azhukov.agent.core.agent;

import com.azhukov.agent.api.dto.AttachmentRef;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.service.AttachmentArtifactService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared CLI state application logic used by both the streaming
 * ({@code AgentStreamingService}) and sync ({@code AgentRuntimeService}) paths.
 *
 * <p>Reads CLI state values (reasoning effort, personality, queued prompt, goal,
 * subgoals, subgoal) from the session entity and merges them into a new
 * {@link ChatRequest} with a suitably prefixed user message.
 *
 * <p>WP-11 (docs/35): when the request carries {@link AttachmentRef}s, the
 * resolved artifacts are appended to the merged message as a bounded
 * {@code [Attachments]} block (safe cache path + metadata, never client paths).
 */
@Component
public class CliStateApplier {

    /** True when the last applyCliState merged a queued prompt that must be cleared. */
    private final java.util.concurrent.atomic.AtomicBoolean consumedQueuedPrompt =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private final ObjectProvider<AttachmentArtifactService> attachmentsProvider;

    public CliStateApplier(ObjectProvider<AttachmentArtifactService> attachmentsProvider) {
        this.attachmentsProvider = attachmentsProvider;
    }


    /**
     * Apply CLI runtime settings from the session entity to the request.
     *
     * <p>If the session is {@code null} the request is returned unchanged.
     * The merged message is built from the session's goal, subgoals, subgoal,
     * and queued-prompt CLI state values, prepended to the original user message.
     *
     * @param request the incoming chat request
     * @param session the session entity (may be {@code null})
     * @return a new {@link ChatRequest} with merged message and resolved fields
     */
    public ChatRequest applyCliState(ChatRequest request, SessionEntity session) {
        if (session == null) {
            // WP-11: no session entity (first turn — the session is created
            // later by the resolver) still resolves attachment references:
            // they are request-scoped, not session-scoped.
            if (request.attachments() == null || request.attachments().isEmpty()) {
                return request;
            }
            String withAttachments = appendAttachments(request.message(), request.attachments());
            return new ChatRequest(
                request.sessionId(), withAttachments,
                request.delegationDepth(), request.timeoutMs(),
                request.model(), request.provider(), request.baseUrl(), request.apiKey(),
                request.reasoningEffort(), request.fastMode(), request.voiceMode(),
                request.personality(), request.enabledTools(), request.disabledTools(),
                request.queuedPrompt(), request.subgoal(),
                request.maxCompletionTokens(), request.systemPromptOverride(),
                request.cdpUrl(), null, request.userId(), request.username(),
                request.firstName(), request.languageCode(), request.chatType(),
                request.serviceTier(), request.yoloMode(), request.verboseMode(),
                request.footerEnabled(), request.attachments());
        }
        String reasoningEffort = request.reasoningEffort() != null ? request.reasoningEffort() : session.getCliStateValue("reasoningEffort");
        String personality = request.personality() != null ? request.personality() : session.getCliStateValue("personality");
        String queuedPrompt = request.queuedPrompt() != null ? request.queuedPrompt() : session.getCliStateValue("queuedPrompt");
        String subgoal = request.subgoal() != null ? request.subgoal() : session.getSubgoal();
        String goal = request.goal() != null ? request.goal() : session.getCliStateValue("goal");
        if (goal == null || "true".equals(session.getCliStateValue("goalPaused"))) {
            goal = null;
        }
        String subgoals = session.getCliStateValue("subgoals");

        String finalMessage = buildMergedMessage(request.message(), queuedPrompt, goal, subgoals, subgoal);
        String withAttachments = appendAttachments(finalMessage, request.attachments());
        // Hermes /queue semantics: the queued prompt applies to the NEXT turn only.
        // Mark it consumed so callers can clear the persisted value inside a write tx.
        this.consumedQueuedPrompt.set(queuedPrompt != null && !queuedPrompt.isBlank());
        return new ChatRequest(
            request.sessionId(),
            withAttachments,
            request.delegationDepth(),
            request.timeoutMs(),
            request.model(),
            request.provider(),
            request.baseUrl(),
            request.apiKey(),
            reasoningEffort,
            request.fastMode(),
            request.voiceMode(),
            personality,
            request.enabledTools(),
            request.disabledTools(),
            null, // consumed
            null,
            request.maxCompletionTokens(),
            request.systemPromptOverride(),
            request.cdpUrl(),
            null, // goal
            request.userId(),
            request.username(),
            request.firstName(),
            request.languageCode(),
            request.chatType(),
            request.serviceTier(),
            request.yoloMode(),
            request.verboseMode(),
            request.footerEnabled(),
            request.attachments()
        );
    }

    /**
     * Build the merged user message by prepending goal/subgoal/queued-prompt
     * context blocks to the original user message.
     */
    private String buildMergedMessage(String userMessage, String queuedPrompt, String goal, String subgoals, String subgoal) {
        StringBuilder sb = new StringBuilder();
        if (goal != null && !goal.isBlank()) {
            sb.append("[Standing Goal]\n").append(goal).append("\n\n");
        }
        if (subgoals != null && !subgoals.isBlank()) {
            sb.append("[Subgoals]\n").append(subgoals).append("\n\n");
        }
        if (subgoal != null && !subgoal.isBlank()) {
            sb.append("[Goal/Subgoal]\n").append(subgoal).append("\n\n");
        }
        if (queuedPrompt != null && !queuedPrompt.isBlank()) {
            sb.append("[Queued context]\n").append(queuedPrompt).append("\n\n");
        }
        sb.append(userMessage);
        return sb.toString();
    }

    /**
     * WP-11: append a bounded [Attachments] block describing each referenced
     * artifact. Text-ish artifacts inline a preview; everything else gets the
     * safe cache path. Unknown/unresolvable ids degrade honestly — the model
     * sees "[unavailable]" instead of a fabricated path.
     */
    private String appendAttachments(String message, java.util.List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return message;
        }
        AttachmentArtifactService service = attachmentsProvider == null
            ? null : attachmentsProvider.getIfAvailable();
        StringBuilder sb = new StringBuilder();
        int appended = 0;
        for (AttachmentRef ref : attachments) {
            if (ref == null || !ref.valid()) {
                continue;
            }
            AttachmentArtifactService.AttachmentArtifact artifact =
                service != null ? service.find(ref.artifactId()).orElse(null) : null;
            if (artifact == null) {
                sb.append("[unavailable: ").append(ref.artifactId()).append("]\n");
                appended++;
                continue;
            }
            java.util.Optional<Path> content = service.contentPath(ref.artifactId());
            String preview = content
                .map(this::boundedTextPreview)
                .filter(t -> t != null && !t.isBlank())
                .orElse(null);
            if (preview != null) {
                sb.append("[Attachment: ").append(displayName(artifact))
                    .append(" | ").append(artifact.mimeType())
                    .append(" | ").append(artifact.sizeBytes()).append(" bytes]\n")
                    .append(preview).append("\n");
            } else {
                sb.append("[Attachment: ").append(displayName(artifact))
                    .append(" | ").append(artifact.mimeType())
                    .append(" | ").append(artifact.sizeBytes()).append(" bytes")
                    .append(" | content at ").append(content.map(Path::toString).orElse("?"))
                    .append("]\n");
            }
            appended++;
        }
        if (appended == 0) {
            return message;
        }
        return message + "\n\n[Attachments]\n" + sb.toString().stripTrailing();
    }

    private static final int TEXT_PREVIEW_CHARS = 4_000;
    private static final java.util.Set<String> TEXT_MIME_PREFIXES = java.util.Set.of("text/", "application/json");

    private String boundedTextPreview(Path path) {
        try {
            String mime = Files.probeContentType(path);
            if (mime == null || TEXT_MIME_PREFIXES.stream().noneMatch(mime::startsWith)) {
                return null;
            }
            String content = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            return content.length() <= TEXT_PREVIEW_CHARS
                ? content
                : content.substring(0, TEXT_PREVIEW_CHARS) + "\n[...truncated " 
                    + (content.length() - TEXT_PREVIEW_CHARS) + " chars]";
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayName(AttachmentArtifactService.AttachmentArtifact artifact) {
        return artifact.fileName() != null && !artifact.fileName().isBlank()
            ? artifact.fileName() : artifact.id();
    }

    /** Whether the last {@link #applyCliState} consumed a queued prompt (callers clear it in a write tx). */
    public boolean consumeQueuedPromptFlag() {
        return consumedQueuedPrompt.getAndSet(false);
    }
}
