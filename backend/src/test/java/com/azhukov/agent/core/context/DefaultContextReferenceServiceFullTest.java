package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ContextReference;
import com.azhukov.agent.core.model.ReferenceType;
import com.azhukov.agent.core.skill.SkillManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultContextReferenceServiceFullTest {

    @TempDir
    private Path tempDir;

    @Mock
    private SkillManager skillManager;

    private AgentProperties properties;
    private DefaultContextReferenceService service;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        properties.getCore().setMaxReferenceFileBytes(1_000);
        properties.getCore().setHttpClientTimeoutSeconds(5);
        properties.getCore().setHttpUserAgent("TestAgent/1.0");

        httpClient = mock(HttpClient.class);
        service = new DefaultContextReferenceService(properties, skillManager);
        injectHttpClient(service, httpClient);
    }

    @Test
    void resolveReturnsReferencesForSupportedPrefixes() {
        List<ContextReference> refs = service.resolve(List.of(
            "file:///tmp/notes.txt",
            "http://example.com/page",
            "https://example.com/page",
            "skill://coding"
        ));

        assertThat(refs).hasSize(4);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.FILE);
        assertThat(refs.get(0).source()).isEqualTo("/tmp/notes.txt");
        assertThat(refs.get(0).displayName()).isEqualTo("notes.txt");

        assertThat(refs.get(1).type()).isEqualTo(ReferenceType.URL);
        assertThat(refs.get(1).source()).isEqualTo("http://example.com/page");

        assertThat(refs.get(2).type()).isEqualTo(ReferenceType.URL);
        assertThat(refs.get(2).source()).isEqualTo("https://example.com/page");

        assertThat(refs.get(3).type()).isEqualTo(ReferenceType.SKILL);
        assertThat(refs.get(3).source()).isEqualTo("coding");
    }

    @Test
    void resolveReturnsUnknownForUnsupportedScheme() {
        List<ContextReference> refs = service.resolve(List.of(
            "unknown://x",
            "session-search://past orders",
            "ftp://host/file.txt"
        ));

        assertThat(refs).hasSize(3);
        for (ContextReference ref : refs) {
            assertThat(ref.type()).isEqualTo(ReferenceType.UNKNOWN);
            assertThat(ref.success()).isFalse();
            assertThat(ref.error()).contains("unrecognized reference");
        }
    }

    @Test
    void resolveIgnoresNullAndBlankRefs() {
        List<String> input = Arrays.asList(null, "", "   ", "file://valid.txt");
        List<ContextReference> refs = service.resolve(input);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.FILE);
    }

    @Test
    void loadContentReadsFileContentWithinLimits() throws Exception {
        Path file = writeFile("notes.txt", "Project notes content.");

        ContextReference ref = new ContextReference(ReferenceType.FILE, file.toString(), "notes.txt", null);

        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValue("Project notes content.");
    }

    @Test
    void loadContentRejectsFileThatExceedsMaxBytes() throws Exception {
        properties.getCore().setMaxReferenceFileBytes(5);
        Path file = writeFile("big.txt", "1234567890-abcdefghijklmnopqrstuvwxyz");

        ContextReference ref = new ContextReference(ReferenceType.FILE, file.toString(), "big.txt", null);

        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValueSatisfying(v -> assertThat(v).contains("file too large").contains(file.toString()));
    }

    @Test
    void loadContentReturnsAccessDeniedForFileOutsideWorkingDir() {
        ContextReference ref = new ContextReference(ReferenceType.FILE, "/etc/passwd", "passwd", null);

        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValueSatisfying(v -> assertThat(v).contains("file access denied").contains("/etc/passwd"));
    }

    @Test
    void loadContentFetchesWebContentForHttpReferences() throws Exception {
        String body = "{\"data\":\"web payload\"}";
        HttpResponse<String> response = httpResponse(200, body);
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);

        ContextReference ref = new ContextReference(ReferenceType.URL, "http://example.com/api/data", "data", null);

        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValue(body);
    }

    @Test
    void loadContentReturnsErrorMarkerForNon2xxHttpResponse() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);

        ContextReference ref = new ContextReference(ReferenceType.URL, "http://example.com/missing", "missing", null);

        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValueSatisfying(v -> assertThat(v).contains("url returned 404").contains("http://example.com/missing"));
    }

    @Test
    void loadContentReturnsErrorMarkerWhenHttpFetchFails() throws Exception {
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenThrow(new IOException("connection reset"));

        ContextReference ref = new ContextReference(ReferenceType.URL, "http://example.com/fail", "fail", null);

        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValueSatisfying(v -> assertThat(v).contains("url fetch error").contains("http://example.com/fail"));
    }

    @Test
    void loadContentSearchesSessionsForSessionSearchReferencesIsNotYetSupported() {
        // Current implementation has no ReferenceType.SESSION, so session-search:// is classified as UNKNOWN
        // with an error marker. loadContent therefore reports the reference as failed.
        List<ContextReference> refs = service.resolve(List.of("session-search://past orders"));
        ContextReference ref = refs.get(0);

        assertThat(ref.type()).isEqualTo(ReferenceType.UNKNOWN);
        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValue("[failed to load reference: unrecognized reference]");
    }

    @Test
    void loadContentReturnsUnknownMarkerForUnsupportedReferenceType() {
        // An UNKNOWN reference with no error reaches the UNKNOWN branch in loadContent.
        ContextReference ref = new ContextReference(
            ReferenceType.UNKNOWN,
            "weird://thing",
            "weird://thing",
            null
        );

        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValue("[unknown reference type: weird://thing]");
    }

    @Test
    void loadContentLoadsSkillContent() {

        ContextReference ref = new ContextReference(ReferenceType.SKILL, "coding", "coding", null);
        when(skillManager.getSkill("coding")).thenReturn("public class Example {}");

        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValueSatisfying(v -> {
                assertThat(v).startsWith("[skill coding]\n");
                assertThat(v).contains("public class Example {}");
            });
    }

    @Test
    void loadContentReturnsErrorMarkerForMissingSkill() {

        ContextReference ref = new ContextReference(ReferenceType.SKILL, "missing", "missing", null);

        assertThat(service.loadContent(ref))
            .isPresent()
            .hasValue("[skill not found: missing]");
    }

    private Path writeFile(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.writeString(file, content);
        return file;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> httpResponse(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }

    private void injectHttpClient(DefaultContextReferenceService svc, HttpClient client) throws Exception {
        Field field = DefaultContextReferenceService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(svc, client);
    }
}
