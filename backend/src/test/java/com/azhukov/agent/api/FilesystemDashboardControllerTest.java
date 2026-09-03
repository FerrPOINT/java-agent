package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.DefaultFileSafety;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FilesystemDashboardControllerTest {

    @TempDir
    private Path tempDir;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(
            new FilesystemDashboardController(properties, new DefaultFileSafety(properties))).build();
    }

    @Test
    void listReturnsHermesShapeAndHidesHeavyOrSensitiveEntries() throws Exception {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root.resolve("folder"));
        Files.createDirectories(root.resolve("node_modules"));
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("mcp-tokens"));
        Files.writeString(root.resolve("b.txt"), "visible");
        Files.writeString(root.resolve(".env"), "TOKEN=secret");
        Files.writeString(root.resolve("auth.json"), "{}");

        mockMvc.perform(get("/api/fs/list").param("path", root.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[*].name", contains("folder", "b.txt")));

        mockMvc.perform(get("/api/fs/list").param("path", root.resolve("missing").toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries").isArray())
            .andExpect(jsonPath("$.error").value("ENOENT"));
    }

    @Test
    void readTextReturnsPreviewMetadataAndBlocksSensitiveFiles() throws Exception {
        Path file = tempDir.resolve("README.md");
        Files.writeString(file, "hello\nworld", StandardCharsets.UTF_8);
        Path secret = tempDir.resolve(".env");
        Files.writeString(secret, "TOKEN=secret", StandardCharsets.UTF_8);

        mockMvc.perform(get("/api/fs/read-text").param("path", file.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.binary").value(false))
            .andExpect(jsonPath("$.byteSize").value(11))
            .andExpect(jsonPath("$.language").value("markdown"))
            .andExpect(jsonPath("$.path").value(file.toString()))
            .andExpect(jsonPath("$.text").value("hello\nworld"))
            .andExpect(jsonPath("$.truncated").value(false));

        mockMvc.perform(get("/api/fs/read-text").param("path", secret.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.detail").value("File is not readable"));
    }

    @Test
    void readDataUrlUsesHermesMimeShape() throws Exception {
        Path image = tempDir.resolve("image.png");
        Files.write(image, new byte[] {1, 2, 3});

        mockMvc.perform(get("/api/fs/read-data-url").param("path", image.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataUrl").value("data:image/png;base64,AQID"));
    }

    @Test
    void writeTextIsBoundedToAllowedRegularFiles() throws Exception {
        Path file = tempDir.resolve("saved.txt");
        String json = "{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\",\"content\":\"saved\"}";

        mockMvc.perform(post("/api/fs/write-text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.path").value(file.toString()))
            .andExpect(jsonPath("$.byteSize").value(5));

        assertThat(Files.readString(file)).isEqualTo("saved");

        Path missingParent = tempDir.resolve("missing").resolve("file.txt");
        String missingJson = "{\"path\":\"" + missingParent.toString().replace("\\", "\\\\") + "\",\"content\":\"saved\"}";
        mockMvc.perform(post("/api/fs/write-text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingJson))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Parent directory does not exist"));
    }

    @Test
    void managedFilesExposeHermesShapesAndHideSensitivePaths() throws Exception {
        Path root = tempDir.resolve("managed");
        Files.createDirectories(root.resolve("folder"));
        Files.createDirectories(root.resolve("mcp-tokens"));
        Files.writeString(root.resolve("plain.txt"), "safe", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".env"), "TOKEN=secret", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("mcp-tokens").resolve("github.json"), "{\"access_token\":\"secret\"}", StandardCharsets.UTF_8);

        mockMvc.perform(get("/api/files").param("path", root.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value(root.toString()))
            .andExpect(jsonPath("$.root").doesNotExist())
            .andExpect(jsonPath("$.locked_root").doesNotExist())
            .andExpect(jsonPath("$.can_change_path").value(true))
            .andExpect(jsonPath("$.entries[*].name", contains("folder", "plain.txt")))
            .andExpect(jsonPath("$.entries[0].is_directory").value(true))
            .andExpect(jsonPath("$.entries[1].mime_type").value("text/plain"));

        mockMvc.perform(get("/api/files").param("path", root.resolve("mcp-tokens").toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries").isEmpty());

        mockMvc.perform(get("/api/files/read").param("path", root.resolve(".env").toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.detail").value("Access to sensitive files is not allowed"));
    }

    @Test
    void managedUploadStreamReadDownloadAndDeleteUseHermesShape() throws Exception {
        Path file = tempDir.resolve("managed").resolve("out").resolve("demo.mp4");
        MockMultipartFile upload = new MockMultipartFile(
            "file",
            "demo.mp4",
            "video/mp4",
            "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/files/upload-stream")
                .file(upload)
                .param("path", file.toString())
                .param("overwrite", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.path").value(file.toString()))
            .andExpect(jsonPath("$.entry.name").value("demo.mp4"))
            .andExpect(jsonPath("$.entry.size").value(5))
            .andExpect(jsonPath("$.entry.mime_type").value("video/mp4"));

        assertThat(Files.readString(file)).isEqualTo("hello");

        mockMvc.perform(get("/api/files/read").param("path", file.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("demo.mp4"))
            .andExpect(jsonPath("$.size").value(5))
            .andExpect(jsonPath("$.mime_type").value("video/mp4"))
            .andExpect(jsonPath("$.data_url").value("data:video/mp4;base64,aGVsbG8="));

        mockMvc.perform(get("/api/files/download").param("path", file.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", startsWith("attachment;")));

        mockMvc.perform(get("/api/files/stream")
                .param("path", file.toString())
                .header("Range", "bytes=1-3"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Disposition", startsWith("inline;")))
            .andExpect(header().string("Content-Range", "bytes 1-3/5"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(content().bytes("ell".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(head("/api/files/stream").param("path", file.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Length", "5"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        Path createdDir = tempDir.resolve("managed").resolve("new-dir");
        String mkdirJson = "{\"path\":\"" + createdDir.toString().replace("\\", "\\\\") + "\"}";
        mockMvc.perform(post("/api/files/mkdir")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mkdirJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entry.is_directory").value(true));
        assertThat(Files.isDirectory(createdDir)).isTrue();

        String deleteJson = "{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\",\"recursive\":false}";
        mockMvc.perform(delete("/api/files")
                .contentType(MediaType.APPLICATION_JSON)
                .content(deleteJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.path").value(file.toString()));
        assertThat(file).doesNotExist();
    }

    @Test
    void mediaEndpointServesOnlyHermesMediaRoots() throws Exception {
        String oldHermesHome = System.getProperty("hermes.home");
        try {
            Path home = tempDir.resolve("home");
            System.setProperty("hermes.home", home.toString());
            Path image = home.resolve("images").resolve("pic.png");
            Files.createDirectories(image.getParent());
            Files.write(image, new byte[] {1, 2, 3});
            Path outside = tempDir.resolve("outside.png");
            Files.write(outside, new byte[] {1, 2, 3});
            Path text = home.resolve("images").resolve("note.txt");
            Files.writeString(text, "hello", StandardCharsets.UTF_8);

            mockMvc.perform(get("/api/media").param("path", image.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data_url").value("data:image/png;base64,AQID"));

            mockMvc.perform(get("/api/media").param("path", outside.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Path outside media roots"));

            mockMvc.perform(get("/api/media").param("path", text.toString()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.detail").value("Unsupported media type"));
        } finally {
            if (oldHermesHome == null) {
                System.clearProperty("hermes.home");
            } else {
                System.setProperty("hermes.home", oldHermesHome);
            }
        }
    }

    @Test
    void chatImageUploadPersistsSniffedImageUnderRequestedProfileHome() throws Exception {
        String oldHermesHome = System.getProperty("hermes.home");
        try {
            Path home = tempDir.resolve("home");
            Path profileHome = home.resolve("profiles").resolve("demo");
            Files.createDirectories(profileHome);
            System.setProperty("hermes.home", home.toString());

            mockMvc.perform(post("/api/chat/image-upload")
                    .param("profile", "demo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"data_url\":\"data:image/png;base64,iVBORw0KGgoBAgM=\",\"filename\":\"bad name.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.path", startsWith(profileHome.resolve("images").toString())))
                .andExpect(jsonPath("$.name", startsWith("dashboard_")))
                .andExpect(jsonPath("$.name", endsWith("_bad_name.png")))
                .andExpect(jsonPath("$.bytes").value(11))
                .andExpect(jsonPath("$.mime_type").value("image/png"));

            try (var stream = Files.list(profileHome.resolve("images"))) {
                List<Path> saved = stream.toList();
                assertThat(saved).hasSize(1);
                assertThat(saved.get(0).getFileName().toString())
                    .startsWith("dashboard_")
                    .endsWith("_bad_name.png");
                assertThat(Files.readAllBytes(saved.get(0))).isEqualTo(new byte[] {
                    (byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G',
                    (byte) '\r', (byte) '\n', 0x1a, (byte) '\n',
                    1, 2, 3
                });
            }
        } finally {
            if (oldHermesHome == null) {
                System.clearProperty("hermes.home");
            } else {
                System.setProperty("hermes.home", oldHermesHome);
            }
        }
    }

    @Test
    void chatImageUploadRejectsNonImageMimeAndSpoofedBytes() throws Exception {
        mockMvc.perform(post("/api/chat/image-upload")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data_url\":\"data:text/plain;base64,aGVsbG8=\",\"filename\":\"note.png\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Upload payload must be an image"));

        mockMvc.perform(post("/api/chat/image-upload")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data_url\":\"data:image/png;base64,aGVsbG8=\",\"filename\":\"fake.png\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unsupported image type"));
    }

    @Test
    void chatImageUploadRejectsUnknownProfilesLikeHermes() throws Exception {
        String oldHermesHome = System.getProperty("hermes.home");
        try {
            Path home = tempDir.resolve("home");
            System.setProperty("hermes.home", home.toString());

            mockMvc.perform(post("/api/chat/image-upload")
                    .param("profile", "missing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"data_url\":\"data:image/png;base64,iVBORw0KGgoBAgM=\",\"filename\":\"pic.png\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Profile 'missing' does not exist."));
        } finally {
            if (oldHermesHome == null) {
                System.clearProperty("hermes.home");
            } else {
                System.setProperty("hermes.home", oldHermesHome);
            }
        }
    }

    @Test
    void gitRootAndFileDiffExposeRemoteDesktopShapesSafely() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path file = repo.resolve("src").resolve("App.java");
        Files.createDirectories(repo.resolve(".git"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}", StandardCharsets.UTF_8);

        mockMvc.perform(get("/api/fs/git-root").param("path", file.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.root").value(repo.toString()));

        mockMvc.perform(get("/api/git/file-diff")
                .param("path", tempDir.toString())
                .param("file", "saved.txt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diff").value(""));

        mockMvc.perform(get("/api/git/file-diff")
                .param("path", repo.toString())
                .param("file", "../outside.txt"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("file must stay within the repository path"));
    }

    @Test
    void ghAuthStatusMirrorsHermesShapeWithoutPrompting() throws Exception {
        mockMvc.perform(get("/api/git/gh-auth").param("refresh", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").exists())
            .andExpect(jsonPath("$.authenticated").exists());
    }

    @Test
    void gitReadOnlyRoutesMirrorHermesDashboardShapes() throws Exception {
        Assumptions.assumeTrue(gitAvailable());

        Path repo = tempDir.resolve("real-repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        git(repo, "add", "-A");
        git(repo, "commit", "-qm", "init");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\nthree\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("new.py"), "print(1)\nprint(2)\n", StandardCharsets.UTF_8);

        mockMvc.perform(get("/api/git/status").param("path", repo.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.branch").value("main"))
            .andExpect(jsonPath("$.defaultBranch").value("main"))
            .andExpect(jsonPath("$.changed").value(2))
            .andExpect(jsonPath("$.unstaged").value(2))
            .andExpect(jsonPath("$.untracked").value(1))
            .andExpect(jsonPath("$.added").value(3))
            .andExpect(jsonPath("$.files[*].path", contains("a.txt", "new.py")));

        mockMvc.perform(get("/api/git/review/list").param("path", repo.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.base").doesNotExist())
            .andExpect(jsonPath("$.files[*].path", contains("a.txt", "new.py")))
            .andExpect(jsonPath("$.files[1].status").value("?"))
            .andExpect(jsonPath("$.files[1].added").value(2));

        mockMvc.perform(get("/api/git/review/diff")
                .param("path", repo.toString())
                .param("file", "new.py"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diff", containsString("+print(1)")));

        mockMvc.perform(get("/api/git/file-diff")
                .param("path", repo.toString())
                .param("file", "a.txt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diff", containsString("+three")));

        mockMvc.perform(get("/api/git/file-diff")
                .param("path", repo.toString())
                .param("file", "new.py"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diff", containsString("+print(2)")));

        mockMvc.perform(get("/api/git/review/commit-context").param("path", repo.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diff", containsString("# New (untracked) files:")))
            .andExpect(jsonPath("$.recent").value("init"));

        mockMvc.perform(get("/api/git/review/rev-parse").param("path", repo.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sha").isString());

        mockMvc.perform(get("/api/git/worktrees").param("path", repo.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.worktrees[0].branch").value("main"))
            .andExpect(jsonPath("$.worktrees[0].isMain").value(true));

        mockMvc.perform(get("/api/git/branches").param("path", repo.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.branches[0].name").value("main"))
            .andExpect(jsonPath("$.branches[0].checkedOut").value(true))
            .andExpect(jsonPath("$.branches[0].isDefault").value(true))
            .andExpect(jsonPath("$.branches[0].isRemote").value(false));

        mockMvc.perform(get("/api/git/base-branches").param("path", repo.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.branches[0].name").value("main"))
            .andExpect(jsonPath("$.branches[0].isDefault").value(true));

        mockMvc.perform(get("/api/git/review/ship-info").param("path", repo.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ghReady").value(false));

        mockMvc.perform(post("/api/git/review/pr-list")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + repo.toString().replace("\\", "\\\\") + "\",\"branches\":[\"main\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ghReady").value(false))
            .andExpect(jsonPath("$.prs").isArray());

        mockMvc.perform(post("/api/git/review/stage")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + repo.toString().replace("\\", "\\\\") + "\",\"file\":\"a.txt\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("Git mutation endpoints are not implemented"));
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertThat(code).as("git %s%n%s", String.join(" ", List.of(args)), output).isEqualTo(0);
    }
}
