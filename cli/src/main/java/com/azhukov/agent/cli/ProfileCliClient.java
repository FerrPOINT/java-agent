package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The named-profile REPL uses the same durable runs API as Fleet. */
@Component
final class ProfileCliClient {

    private static final String SESSIONS = "/api/sessions";
    private static final String CRON = "/api/v1/agent/cron";
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "cancelled", "expired");
    private static final Duration WAIT_LIMIT = Duration.ofMinutes(10);

    private final RestClient http;
    private final ObjectMapper json;
    private final CliProperties settings;

    ProfileCliClient(@Qualifier("backendRestClient") RestClient http, ObjectMapper json, CliProperties settings) {
        this.http = http;
        this.json = json;
        this.settings = settings;
    }

    JsonNode capabilities() {
        JsonNode result = get(route("/capabilities"));
        if (!settings.getProfile().equals(result.path("profile").asText())) {
            throw new IllegalStateException("The backend did not confirm the selected profile.");
        }
        return result;
    }

    String createSession() {
        return UUID.fromString(post(scoped(SESSIONS), Map.of(), null).path("id").asText()).toString();
    }

    boolean ownsSession(String session) {
        if (!isUuid(session)) return false;
        try {
            return session.equals(get(scoped(SESSIONS + "/" + session)).path("id").asText());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw e;
        }
    }

    JsonNode sessions() {
        return get(scoped(SESSIONS));
    }

    JsonNode history(String session) {
        return get(scoped(SESSIONS + "/" + UUID.fromString(session) + "/messages"));
    }

    JsonNode skills() {
        return get(route("/skills")).path("data");
    }

    JsonNode cron() {
        return get(scoped(CRON));
    }

    JsonNode createCron(String name, String schedule, String prompt) {
        return post(CRON, Map.of("profile", settings.getProfile(), "name", name, "schedule", schedule,
            "prompt", prompt), null);
    }

    JsonNode cronAction(String id, String action) {
        return post(scoped(CRON + "/" + UUID.fromString(id) + "/" + action), Map.of(), null);
    }

    void deleteCron(String id) {
        http.delete().uri(scoped(CRON + "/" + UUID.fromString(id))).retrieve().toBodilessEntity();
    }

    String submit(String session, String message) {
        return withReceipt(session, () -> {
            Pending previous = read(session);
            if (previous != null && (previous.runId() == null || !isDone(status(previous.runId())))) {
                throw new IllegalStateException("This session has an unfinished run. Use /wait, /retry or /stop.");
            }
            // The key and exact input survive an exit or lost response.
            Pending pending = new Pending(UUID.randomUUID().toString(), message, null);
            save(session, pending);
            return accept(session, pending);
        });
    }

    String retry(String session) {
        return withReceipt(session, () -> {
            Pending pending = requirePending(session);
            return pending.runId() == null ? accept(session, pending) : pending.runId();
        });
    }

    String currentRun(String session) {
        return withReceipt(session, () -> {
            Pending pending = requirePending(session);
            return pending.runId() == null ? recover(session, pending) : pending.runId();
        });
    }

    private String withReceipt(String session, Supplier<String> action) {
        Path target = receipt(session);
        Path lockPath = target.resolveSibling(target.getFileName() + ".lock");
        try {
            Files.createDirectories(lockPath.getParent());
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException("This profile session is in use by another CLI.");
                return action.get();
            }
        } catch (OverlappingFileLockException e) {
            throw new IllegalStateException("This profile session is in use by another CLI.");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot lock the profile run receipt.", e);
        }
    }

    private String accept(String session, Pending pending) {
        try {
            return remember(session, pending, post(route("/runs"), input(session, pending), pending.key()));
        } catch (ResourceAccessException e) {
            return recover(session, pending);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError() && e.getStatusCode() != HttpStatus.CONFLICT) {
                removePending(session);
            }
            throw e;
        }
    }

    private String recover(String session, Pending pending) {
        try {
            return remember(session, pending, post(route("/runs/lookup"), input(session, pending), pending.key()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new IllegalStateException("The run has no acceptance receipt yet. /retry uses the original request key.");
            }
            throw e;
        }
    }

    private String remember(String session, Pending pending, JsonNode receipt) {
        String run = validRunId(receipt.path("run_id").asText());
        save(session, new Pending(pending.key(), null, run));
        return run;
    }

    String awaitRun(String run, Consumer<String> onToken, Consumer<String> onTool) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        long cursor = 0;
        boolean displayedOutput = false;
        while (System.nanoTime() < deadline) {
            JsonNode current = status(run);
            JsonNode page = events(run, cursor);
            for (JsonNode event : page.path("events")) {
                cursor = event.path("seq").asLong();
                switch (event.path("event").asText()) {
                    case "message.delta" -> {
                        onToken.accept(event.path("delta").asText());
                        displayedOutput = true;
                    }
                    case "tool.started" -> onTool.accept(event.path("tool").asText());
                    default -> { }
                }
            }
            if (cursor < page.path("last_seq").asLong()) continue;
            String state = current.path("status").asText();
            if ("completed".equals(state)) {
                if (!displayedOutput) onToken.accept(current.path("output").asText());
                return "";
            }
            if ("waiting_for_approval".equals(state)) {
                return "Run " + run + " awaits approval. Use /approvals, /approve or /deny, then /wait.";
            }
            if (isDone(current)) {
                return "Run " + run + ": " + state + " " + current.path("error").asText();
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "Run " + run + " is still active. Use /wait to continue.";
    }

    JsonNode status(String run) {
        return get(runRoute(run));
    }

    JsonNode events(String run, long after) {
        if (after < 0) throw new IllegalArgumentException("The event cursor must be nonnegative.");
        return get(runRoute(run) + "/events/replay?after=" + after);
    }

    JsonNode control(String run, String action, Map<String, Object> command) {
        return post(runRoute(run) + "/" + action, command, null);
    }

    private String route(String resource) {
        return "/p/" + settings.getProfile() + "/v1" + resource;
    }

    private String scoped(String resource) {
        return resource + "?profile=" + settings.getProfile();
    }

    private String runRoute(String run) {
        return route("/runs/" + validRunId(run));
    }

    private static String validRunId(String run) {
        if (!run.matches("run_[a-f0-9]{32,60}")) throw new IllegalArgumentException("Invalid run ID.");
        return run;
    }

    private static boolean isDone(JsonNode state) {
        return TERMINAL.contains(state.path("status").asText()) || !state.path("execution_available").asBoolean(true);
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Map<String, Object> input(String session, Pending pending) {
        return Map.of("session_id", session, "input", pending.input());
    }

    private JsonNode get(String path) {
        return decode(http.get().uri(path).retrieve().body(String.class));
    }

    private JsonNode post(String path, Map<String, Object> payload, String key) {
        var request = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (key != null) request.header("Idempotency-Key", key);
        return decode(request.body(payload).retrieve().body(String.class));
    }

    private JsonNode decode(String reply) {
        if (reply == null || reply.isBlank()) throw new IllegalStateException("The profile returned an empty response.");
        try {
            return json.readTree(reply);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("The profile returned invalid JSON.", e);
        }
    }

    private Path receipt(String session) {
        return settings.stateDirectory().resolve("runs").resolve(UUID.fromString(session) + ".json");
    }

    private Pending read(String session) {
        try {
            return json.readValue(Files.readAllBytes(receipt(session)), Pending.class);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the profile run receipt.", e);
        }
    }

    private Pending requirePending(String session) {
        Pending pending = read(session);
        if (pending == null) throw new IllegalStateException("There is no run recorded for this session.");
        return pending;
    }

    private void save(String session, Pending pending) {
        Path target = receipt(session);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".pending-", ".json");
            try {
                Files.write(temporary, json.writeValueAsBytes(pending));
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save the profile run receipt.", e);
        }
    }

    private void removePending(String session) {
        try {
            Files.deleteIfExists(receipt(session));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot remove the rejected profile run receipt.", e);
        }
    }

    private record Pending(String key, String input, String runId) {}
}
