package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.FileSafety;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@RestController
@RequiredArgsConstructor
public class FilesystemDashboardController {

    private static final long DATA_URL_MAX_BYTES = 16L * 1024 * 1024;
    private static final long TEXT_SOURCE_MAX_BYTES = 64L * 1024 * 1024;
    private static final int TEXT_PREVIEW_MAX_BYTES = 512 * 1024;
    private static final int TEXT_WRITE_MAX_BYTES = 8 * 1024 * 1024;
    private static final long MEDIA_MAX_BYTES = 25L * 1024 * 1024;
    private static final long MANAGED_FILE_MAX_BYTES = 100L * 1024 * 1024;
    private static final long CHAT_IMAGE_UPLOAD_MAX_BYTES = 25L * 1024 * 1024;
    private static final int UPLOAD_BUFFER_BYTES = 1024 * 1024;
    private static final long GH_AUTH_TTL_NANOS = TimeUnit.SECONDS.toNanos(300);
    private static final long GIT_TIMEOUT_SECONDS = 30L;
    private static final Path HOSTED_MANAGED_FILES_ROOT = Path.of("/opt/data");
    private static final DateTimeFormatter CHAT_IMAGE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final SecureRandom CHAT_IMAGE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern CHAT_IMAGE_CONTROL_CHARS = Pattern.compile("[\\x00-\\x1f]+");
    private static final Pattern CHAT_IMAGE_STEM_UNSAFE = Pattern.compile("[^A-Za-z0-9_.-]+");
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static volatile long ghAuthCacheNanos;
    private static volatile Map<String, Object> ghAuthCache;

    private static final Set<String> READDIR_HIDDEN = Set.of(
        ".git", ".hg", ".svn", ".cache", ".next", ".turbo", ".venv",
        "__pycache__", "build", "dist", "node_modules", "target", "venv");

    private static final Set<String> SENSITIVE_FILE_BASENAMES = Set.of(
        "auth.json",
        "auth.lock",
        "credentials",
        "config.yaml",
        ".anthropic_oauth.json",
        "google_token.json",
        "google_oauth_pending.json",
        "google_oauth.json",
        "webhook_subscriptions.json",
        "bws_cache.json",
        "bws_cache.enc.json",
        ".git-credentials");

    private static final Set<String> SENSITIVE_DIR_NAMES = Set.of("mcp-tokens", "pairing");

    private static final Set<String> STREAMABLE_MEDIA_EXTENSIONS = Set.of(
        ".avi", ".flac", ".m4a", ".mkv", ".mov", ".mp3", ".mp4", ".ogg", ".opus", ".wav", ".webm");

    private static final Set<String> CHAT_IMAGE_ALLOWED_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp");
    private static final Set<String> RESERVED_PROFILE_NAMES = Set.of("hermes", "test", "tmp", "root", "sudo");

    private static final Map<String, String> LANGUAGE_BY_EXT = Map.ofEntries(
        Map.entry(".c", "c"),
        Map.entry(".conf", "ini"),
        Map.entry(".cpp", "cpp"),
        Map.entry(".css", "css"),
        Map.entry(".csv", "csv"),
        Map.entry(".go", "go"),
        Map.entry(".graphql", "graphql"),
        Map.entry(".h", "c"),
        Map.entry(".hpp", "cpp"),
        Map.entry(".html", "html"),
        Map.entry(".java", "java"),
        Map.entry(".js", "javascript"),
        Map.entry(".json", "json"),
        Map.entry(".jsx", "jsx"),
        Map.entry(".kt", "kotlin"),
        Map.entry(".lua", "lua"),
        Map.entry(".md", "markdown"),
        Map.entry(".mjs", "javascript"),
        Map.entry(".py", "python"),
        Map.entry(".rb", "ruby"),
        Map.entry(".rs", "rust"),
        Map.entry(".sh", "shell"),
        Map.entry(".sql", "sql"),
        Map.entry(".svg", "xml"),
        Map.entry(".toml", "toml"),
        Map.entry(".ts", "typescript"),
        Map.entry(".tsx", "tsx"),
        Map.entry(".txt", "text"),
        Map.entry(".xml", "xml"),
        Map.entry(".yaml", "yaml"),
        Map.entry(".yml", "yaml"),
        Map.entry(".zsh", "shell"));

    private static final Map<String, String> MIME_BY_EXT = Map.ofEntries(
        Map.entry(".avi", "video/x-msvideo"),
        Map.entry(".bmp", "image/bmp"),
        Map.entry(".flac", "audio/flac"),
        Map.entry(".gif", "image/gif"),
        Map.entry(".ico", "image/x-icon"),
        Map.entry(".jpeg", "image/jpeg"),
        Map.entry(".jpg", "image/jpeg"),
        Map.entry(".m4a", "audio/mp4"),
        Map.entry(".mkv", "video/x-matroska"),
        Map.entry(".mov", "video/quicktime"),
        Map.entry(".mp3", "audio/mpeg"),
        Map.entry(".mp4", "video/mp4"),
        Map.entry(".ogg", "audio/ogg"),
        Map.entry(".opus", "audio/ogg; codecs=opus"),
        Map.entry(".png", "image/png"),
        Map.entry(".svg", "image/svg+xml"),
        Map.entry(".wav", "audio/wav"),
        Map.entry(".webm", "video/webm"),
        Map.entry(".webp", "image/webp"));

    private final AgentProperties properties;
    private final FileSafety fileSafety;

    @GetMapping("/api/media")
    public ResponseEntity<Map<String, Object>> getMedia(
        @RequestParam(name = "path", required = false) String rawPath
    ) {
        Path target;
        try {
            target = fsPath(rawPath);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        String ext = extension(target);
        if (!isImageMediaExtension(ext)) {
            return status(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
        }
        Path safetyPath = resolveExistingForSafety(target);
        if (!isUnderAny(mediaServeRoots(), safetyPath)) {
            return forbidden("Path outside media roots");
        }

        RegularFile file = regularFile(rawPath);
        if (file.error() != null) {
            return file.error();
        }
        if (!isReadAllowed(file.path())) {
            return forbidden("File is not readable");
        }
        if (file.byteSize() > MEDIA_MAX_BYTES) {
            return status(HttpStatus.CONTENT_TOO_LARGE, "File too large");
        }
        try {
            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(file.path()));
            return ResponseEntity.ok(Map.of("data_url", "data:" + mimeType(file.path()) + ";base64," + encoded));
        } catch (IOException | SecurityException e) {
            return badRequest(e.getMessage() != null ? e.getMessage() : "File read failed");
        }
    }

    @PostMapping("/api/chat/image-upload")
    public ResponseEntity<Map<String, Object>> uploadChatImage(
        @RequestBody(required = false) ChatImageUpload body,
        @RequestParam(name = "profile", required = false) String profile
    ) {
        if (body == null) {
            return badRequest("Upload payload must be provided");
        }
        try {
            ChatImageDecoded decoded = decodeChatImageUpload(body);
            Path imageDir = chatImageHomePath(profile).resolve("images").toAbsolutePath().normalize();
            if (!isWriteAllowed(imageDir)) {
                return forbidden("Image directory is not writable");
            }
            try {
                Files.createDirectories(imageDir);
            } catch (AccessDeniedException e) {
                return status(HttpStatus.FORBIDDEN, "Image directory is not writable");
            } catch (IOException | SecurityException e) {
                return status(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create image directory: " + e.getMessage());
            }

            String stem = sanitizeChatImageStem(body.filename());
            Path target = imageDir.resolve(
                "dashboard_" + LocalDateTime.now().format(CHAT_IMAGE_TIMESTAMP) + "_"
                    + randomHex(4) + "_" + stem + decoded.extension());
            if (!isWriteAllowed(target)) {
                return forbidden("Image directory is not writable");
            }

            try {
                Files.write(target, decoded.data(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (AccessDeniedException e) {
                return status(HttpStatus.FORBIDDEN, "Image directory is not writable");
            } catch (IOException | SecurityException e) {
                return status(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write image: " + e.getMessage());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("path", target.toString());
            response.put("name", target.getFileName().toString());
            response.put("bytes", decoded.data().length);
            response.put("mime_type", decoded.mimeType());
            return ResponseEntity.ok(response);
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @GetMapping("/api/files")
    public ResponseEntity<Map<String, Object>> listManagedFiles(
        @RequestParam(name = "path", required = false) String rawPath
    ) {
        ManagedPath managed;
        try {
            managed = resolveManagedPath(rawPath, false);
        } catch (ApiError e) {
            return apiError(e);
        }
        Path target = managed.target();
        if (!managedReadAllowed(target)) {
            return forbidden("Directory is not readable");
        }
        if (!Files.exists(target)) {
            return status(HttpStatus.NOT_FOUND, "Path not found");
        }
        if (!Files.isDirectory(target)) {
            return badRequest("Path is not a directory");
        }

        try (Stream<Path> stream = Files.list(target)) {
            List<Map<String, Object>> entries = stream
                .filter(path -> !isSensitivePath(path))
                .filter(this::managedReadAllowed)
                .map(path -> managedFileEntry(managed.policy(), path))
                .sorted(managedEntryComparator())
                .toList();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("path", managed.displayPath());
            response.put("parent", managedParent(managed));
            response.put("entries", entries);
            response.putAll(managedResponseMeta(managed.policy()));
            return ResponseEntity.ok(response);
        } catch (ApiError e) {
            return apiError(e);
        } catch (SecurityException e) {
            return forbidden("Directory is not readable");
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read directory: " + e.getMessage());
        }
    }

    @GetMapping("/api/files/read")
    public ResponseEntity<Map<String, Object>> readManagedFile(
        @RequestParam(name = "path", required = false) String rawPath
    ) {
        ManagedFile file;
        try {
            file = managedRegularFile(rawPath, false);
        } catch (ApiError e) {
            return apiError(e);
        }
        if (file.byteSize() > MANAGED_FILE_MAX_BYTES) {
            return status(HttpStatus.CONTENT_TOO_LARGE, "File is too large");
        }
        try {
            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(file.path()));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", file.path().getFileName().toString());
            response.put("path", file.displayPath());
            response.put("size", file.byteSize());
            response.put("mime_type", mimeType(file.path()));
            response.put("data_url", "data:" + mimeType(file.path()) + ";base64," + encoded);
            response.putAll(managedResponseMeta(file.policy()));
            return ResponseEntity.ok(response);
        } catch (IOException | SecurityException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file: " + e.getMessage());
        }
    }

    @GetMapping("/api/files/download")
    public ResponseEntity<?> downloadManagedFile(
        @RequestParam(name = "path", required = false) String rawPath,
        @RequestHeader(name = "Sec-Fetch-Dest", required = false) String fetchDestination,
        @RequestHeader(name = "Range", required = false) String rangeHeader
    ) {
        boolean mediaSubresource = "audio".equalsIgnoreCase(fetchDestination) || "video".equalsIgnoreCase(fetchDestination);
        return managedFileResponse(rawPath, mediaSubresource ? "inline" : "attachment", mediaSubresource, rangeHeader, false);
    }

    @RequestMapping(path = "/api/files/stream", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<?> streamManagedFile(
        @RequestParam(name = "path", required = false) String rawPath,
        @RequestHeader(name = "Range", required = false) String rangeHeader,
        HttpServletRequest request
    ) {
        return managedFileResponse(rawPath, "inline", true, rangeHeader, "HEAD".equalsIgnoreCase(request.getMethod()));
    }

    @PostMapping("/api/files/upload")
    public ResponseEntity<Map<String, Object>> uploadManagedFile(@RequestBody(required = false) ManagedFileUpload body) {
        if (body == null) {
            return badRequest("Upload payload must be provided");
        }
        try {
            DecodedDataUrl decoded = decodeDataUrl(body.dataUrl());
            ManagedPath managed = resolveManagedPath(body.path(), true);
            writeManagedBytes(managed.target(), decoded.data(), overwrite(body.overwrite()));
            return managedWriteResponse(managed);
        } catch (ApiError e) {
            return apiError(e);
        } catch (IOException | SecurityException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write file: " + e.getMessage());
        }
    }

    @PostMapping(value = "/api/files/upload-stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadManagedFileStream(
        @RequestPart("file") MultipartFile file,
        @RequestParam("path") String rawPath,
        @RequestParam(name = "overwrite", defaultValue = "true") boolean overwrite
    ) {
        try {
            ManagedPath managed = resolveManagedPath(rawPath, true);
            writeManagedStream(managed.target(), file, overwrite);
            return managedWriteResponse(managed);
        } catch (ApiError e) {
            return apiError(e);
        } catch (IOException | SecurityException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write file: " + e.getMessage());
        }
    }

    @PostMapping("/api/files/mkdir")
    public ResponseEntity<Map<String, Object>> createManagedDirectory(@RequestBody(required = false) ManagedDirectoryCreate body) {
        if (body == null) {
            return badRequest("Directory payload must be provided");
        }
        try {
            ManagedPath managed = resolveManagedPath(body.path(), true);
            Path target = managed.target();
            if (!isWriteAllowed(target)) {
                return forbidden("Directory is not writable");
            }
            if (Files.exists(target) && !Files.isDirectory(target)) {
                return status(HttpStatus.CONFLICT, "A file already exists at that path");
            }
            Files.createDirectories(target);
            return managedWriteResponse(managed);
        } catch (ApiError e) {
            return apiError(e);
        } catch (IOException | SecurityException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create directory: " + e.getMessage());
        }
    }

    @DeleteMapping("/api/files")
    public ResponseEntity<Map<String, Object>> deleteManagedFile(@RequestBody(required = false) ManagedFileDelete body) {
        if (body == null) {
            return badRequest("Delete payload must be provided");
        }
        try {
            ManagedPath managed = resolveManagedPath(body.path(), false);
            Path target = managed.target();
            if (managed.policy().lockedRoot() != null && target.equals(managed.policy().lockedRoot())) {
                return badRequest("Cannot delete the managed files root");
            }
            if (target.getParent() == null || target.getParent().equals(target)) {
                return badRequest("Cannot delete the filesystem root");
            }
            if (!Files.exists(target)) {
                return status(HttpStatus.NOT_FOUND, "Path not found");
            }
            if (!isWriteAllowed(target)) {
                return forbidden("Path is not writable");
            }
            if (Files.isDirectory(target) && Boolean.TRUE.equals(body.recursive())) {
                deleteRecursively(target);
            } else {
                Files.delete(target);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("path", managed.displayPath());
            response.putAll(managedResponseMeta(managed.policy()));
            return ResponseEntity.ok(response);
        } catch (ApiError e) {
            return apiError(e);
        } catch (IOException | SecurityException e) {
            return status(HttpStatus.CONFLICT, "Could not delete path: " + e.getMessage());
        }
    }

    @GetMapping("/api/fs/list")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(name = "path", required = false) String rawPath) {
        Path target;
        try {
            target = fsPath(rawPath);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (!isReadAllowed(target)) {
            return ResponseEntity.ok(listError("EACCES"));
        }
        if (!Files.exists(target)) {
            return ResponseEntity.ok(listError("ENOENT"));
        }
        if (!Files.isDirectory(target)) {
            return ResponseEntity.ok(listError("ENOTDIR"));
        }
        try (Stream<Path> stream = Files.list(target)) {
            List<Map<String, Object>> entries = stream
                .filter(path -> !isHiddenDirectoryEntry(path))
                .filter(this::isReadAllowed)
                .map(this::dirEntry)
                .sorted(entryComparator())
                .toList();
            return ResponseEntity.ok(Map.of("entries", entries));
        } catch (SecurityException e) {
            return ResponseEntity.ok(listError("EACCES"));
        } catch (IOException e) {
            return ResponseEntity.ok(listError(e.getMessage() != null ? e.getMessage() : "read-error"));
        }
    }

    @GetMapping("/api/fs/read-text")
    public ResponseEntity<Map<String, Object>> readText(
        @RequestParam(name = "path", required = false) String rawPath
    ) {
        RegularFile file = regularFile(rawPath);
        if (file.error() != null) {
            return file.error();
        }
        if (!isReadAllowed(file.path())) {
            return forbidden("File is not readable");
        }
        if (file.byteSize() > TEXT_SOURCE_MAX_BYTES) {
            return status(HttpStatus.CONTENT_TOO_LARGE, "File too large");
        }
        int bytesToRead = (int) Math.min(file.byteSize(), TEXT_PREVIEW_MAX_BYTES);
        byte[] data;
        try (var input = Files.newInputStream(file.path())) {
            data = input.readNBytes(bytesToRead);
        } catch (IOException | SecurityException e) {
            return badRequest(e.getMessage() != null ? e.getMessage() : "File read failed");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("binary", looksBinary(data.length > 4096 ? copyOf(data, 4096) : data));
        response.put("byteSize", file.byteSize());
        response.put("language", LANGUAGE_BY_EXT.getOrDefault(extension(file.path()), "text"));
        response.put("mimeType", mimeType(file.path()));
        response.put("path", file.path().toString());
        response.put("text", new String(data, StandardCharsets.UTF_8));
        response.put("truncated", file.byteSize() > TEXT_PREVIEW_MAX_BYTES);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/fs/write-text")
    public ResponseEntity<Map<String, Object>> writeText(@RequestBody(required = false) FsWriteText body) {
        String rawPath = body != null ? body.path() : null;
        String content = body != null && body.content() != null ? body.content() : "";
        Path target;
        try {
            target = fsPath(rawPath);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        int byteSize = content.getBytes(StandardCharsets.UTF_8).length;
        if (byteSize > TEXT_WRITE_MAX_BYTES) {
            return status(HttpStatus.CONTENT_TOO_LARGE, "Content too large");
        }
        if (!isWriteAllowed(target)) {
            return forbidden("File is not writable");
        }
        try {
            if (Files.isDirectory(target)) {
                return badRequest("Path points to a directory");
            }
            if (Files.exists(target) && !Files.isRegularFile(target)) {
                return badRequest("Only regular files can be written");
            }
            Path parent = target.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                return badRequest("Parent directory does not exist");
            }
            Path tmp = target.resolveSibling("." + target.getFileName() + ".java-agent-tmp-" + ProcessHandle.current().pid());
            try {
                Files.writeString(
                    tmp,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
                moveReplace(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return ResponseEntity.ok(Map.of("ok", true, "path", target.toString(), "byteSize", byteSize));
        } catch (IOException | SecurityException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write file: " + e.getMessage());
        }
    }

    @GetMapping("/api/fs/read-data-url")
    public ResponseEntity<Map<String, Object>> readDataUrl(
        @RequestParam(name = "path", required = false) String rawPath
    ) {
        RegularFile file = regularFile(rawPath);
        if (file.error() != null) {
            return file.error();
        }
        if (!isReadAllowed(file.path())) {
            return forbidden("File is not readable");
        }
        if (file.byteSize() > DATA_URL_MAX_BYTES) {
            return status(HttpStatus.CONTENT_TOO_LARGE, "File too large");
        }
        try {
            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(file.path()));
            return ResponseEntity.ok(Map.of("dataUrl", "data:" + mimeType(file.path()) + ";base64," + encoded));
        } catch (IOException | SecurityException e) {
            return badRequest(e.getMessage() != null ? e.getMessage() : "File read failed");
        }
    }

    @GetMapping("/api/fs/download")
    public ResponseEntity<?> download(@RequestParam(name = "path", required = false) String rawPath) {
        RegularFile file = regularFile(rawPath);
        if (file.error() != null) {
            return file.error();
        }
        if (!isReadAllowed(file.path())) {
            return forbidden("File is not readable");
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mimeType(file.path())))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                attachmentHeader(file.path().getFileName().toString()))
            .body(new FileSystemResource(file.path()));
    }

    @GetMapping("/api/fs/git-root")
    public ResponseEntity<Map<String, Object>> gitRoot(@RequestParam(name = "path", required = false) String rawPath) {
        Path target;
        try {
            target = fsPath(rawPath);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (!isReadAllowed(target)) {
            return forbidden("Path is not readable");
        }
        Path start = Files.isDirectory(target) ? target : target.getParent();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("root", start != null ? findGitRoot(start) : null);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/fs/default-cwd")
    public Map<String, Object> defaultCwd() {
        Path cwd = defaultCwdPath();
        return Map.of("cwd", cwd.toString(), "branch", gitBranch(cwd));
    }

    @GetMapping("/api/git/status")
    public ResponseEntity<?> gitStatus(@RequestParam(name = "path", required = false) String rawPath) {
        try {
            return ResponseEntity.ok().body(repoStatus(gitPath(rawPath)));
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @GetMapping("/api/git/gh-auth")
    public Map<String, Object> ghAuthStatus(@RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        long now = System.nanoTime();
        Map<String, Object> cached = ghAuthCache;
        if (!refresh && cached != null && now - ghAuthCacheNanos < GH_AUTH_TTL_NANOS) {
            return cached;
        }
        Map<String, Object> payload = probeGhAuth();
        ghAuthCache = payload;
        ghAuthCacheNanos = now;
        return payload;
    }

    @GetMapping("/api/git/worktrees")
    public ResponseEntity<Map<String, Object>> gitWorktrees(@RequestParam(name = "path", required = false) String rawPath) {
        try {
            return ResponseEntity.ok(Map.of("worktrees", worktreeList(gitPath(rawPath))));
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @GetMapping("/api/git/branches")
    public ResponseEntity<Map<String, Object>> gitBranches(@RequestParam(name = "path", required = false) String rawPath) {
        try {
            return ResponseEntity.ok(Map.of("branches", branchList(gitPath(rawPath))));
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @GetMapping("/api/git/base-branches")
    public ResponseEntity<Map<String, Object>> gitBaseBranches(@RequestParam(name = "path", required = false) String rawPath) {
        try {
            return ResponseEntity.ok(Map.of("branches", baseBranchList(gitPath(rawPath))));
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @GetMapping("/api/git/review/list")
    public ResponseEntity<Map<String, Object>> gitReviewList(
        @RequestParam(name = "path", required = false) String rawPath,
        @RequestParam(name = "scope", defaultValue = "uncommitted") String scope,
        @RequestParam(name = "base", required = false) String base
    ) {
        try {
            return ResponseEntity.ok(reviewList(gitPath(rawPath), scope, base));
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @GetMapping("/api/git/review/diff")
    public ResponseEntity<Map<String, Object>> gitReviewDiff(
        @RequestParam(name = "path", required = false) String rawPath,
        @RequestParam(name = "file", required = false) String rawFile,
        @RequestParam(name = "scope", defaultValue = "uncommitted") String scope,
        @RequestParam(name = "base", required = false) String base,
        @RequestParam(name = "staged", defaultValue = "false") boolean staged
    ) {
        try {
            Path repo = gitPath(rawPath);
            String file = gitFile(rawFile);
            return ResponseEntity.ok(Map.of("diff", reviewDiff(repo, file, scope, base, staged)));
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @GetMapping("/api/git/review/commit-context")
    public ResponseEntity<Map<String, Object>> gitReviewCommitContext(@RequestParam(name = "path", required = false) String rawPath) {
        try {
            return ResponseEntity.ok(reviewCommitContext(gitPath(rawPath)));
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @GetMapping("/api/git/review/rev-parse")
    public ResponseEntity<Map<String, Object>> gitRevParse(
        @RequestParam(name = "path", required = false) String rawPath,
        @RequestParam(name = "ref", required = false) String ref
    ) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sha", reviewRevParse(gitPath(rawPath), ref));
            return ResponseEntity.ok(response);
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @GetMapping("/api/git/review/ship-info")
    public ResponseEntity<Map<String, Object>> gitShipInfo(@RequestParam(name = "path", required = false) String rawPath) {
        try {
            return ResponseEntity.ok(reviewShipInfo(gitPath(rawPath)));
        } catch (ApiError e) {
            return apiError(e);
        }
    }

    @PostMapping("/api/git/review/pr-list")
    public ResponseEntity<Map<String, Object>> gitPrList(@RequestBody(required = false) GitPrListBody body) {
        return ResponseEntity.ok(Map.of("ghReady", false, "prs", List.of()));
    }

    @PostMapping({
        "/api/git/review/stage",
        "/api/git/review/unstage",
        "/api/git/review/revert",
        "/api/git/review/commit",
        "/api/git/review/push",
        "/api/git/review/create-pr",
        "/api/git/worktree/add",
        "/api/git/worktree/remove",
        "/api/git/branch/switch"
    })
    public ResponseEntity<Map<String, Object>> unsupportedGitMutation() {
        return status(HttpStatus.NOT_IMPLEMENTED, "Git mutation endpoints are not implemented");
    }

    @GetMapping("/api/git/file-diff")
    public ResponseEntity<Map<String, Object>> fileDiff(
        @RequestParam(name = "path", required = false) String rawPath,
        @RequestParam(name = "file", required = false) String rawFile
    ) {
        Path repo;
        try {
            repo = fsPath(rawPath);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (rawFile == null || rawFile.isBlank() || rawFile.indexOf('\0') >= 0) {
            return badRequest("file is required");
        }
        Path repoRoot = repo.toAbsolutePath().normalize();
        Path resolvedFile = resolveRepoFile(repoRoot, rawFile);
        if (resolvedFile == null || !resolvedFile.startsWith(repoRoot)) {
            return badRequest("file must stay within the repository path");
        }
        if (!isReadAllowed(repoRoot) || !isReadAllowed(resolvedFile)) {
            return forbidden("Path is not readable");
        }
        if (!Files.isDirectory(repoRoot) || !Files.exists(repoRoot.resolve(".git"))) {
            return ResponseEntity.ok(Map.of("diff", ""));
        }

        String relative = repoRoot.relativize(resolvedFile).toString();
        String diff = gitOutput(repoRoot, List.of("diff", "HEAD", "--", relative));
        if (diff.isBlank() && gitOutput(repoRoot, List.of("status", "--porcelain", "--", relative)).strip().startsWith("??")) {
            diff = git(repoRoot, List.of("diff", "--no-index", "--", nullDevice(), relative)).stdout();
        }
        return ResponseEntity.ok(Map.of("diff", diff));
    }

    private Path gitPath(String rawPath) {
        Path path;
        try {
            path = fsPath(rawPath);
        } catch (IllegalArgumentException e) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (!isReadAllowed(path)) {
            throw apiErrorException(HttpStatus.FORBIDDEN, "Path is not readable");
        }
        return path;
    }

    private static String gitFile(String rawFile) {
        if (rawFile == null || rawFile.isBlank() || rawFile.indexOf('\0') >= 0) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "file is required");
        }
        try {
            Path path = Path.of(rawFile);
            if (path.isAbsolute() || hasTraversal(path)) {
                throw apiErrorException(HttpStatus.BAD_REQUEST, "file must stay within the repository path");
            }
        } catch (ApiError e) {
            throw e;
        } catch (RuntimeException e) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Invalid file path");
        }
        return rawFile.replace('\\', '/');
    }

    private static Map<String, Object> repoStatus(Path cwd) {
        if (!Files.isDirectory(cwd)) {
            return null;
        }
        GitResult status = git(cwd, List.of("status", "--porcelain=v2", "--branch", "-z"));
        if (status.code() != 0) {
            return null;
        }

        String branch = null;
        boolean detached = false;
        int ahead = 0;
        int behind = 0;
        for (String rec : status.stdout().split("\u0000")) {
            if (rec.startsWith("# branch.head ")) {
                String head = rec.substring("# branch.head ".length());
                detached = "(detached)".equals(head);
                branch = detached ? null : head;
            } else if (rec.startsWith("# branch.ab ")) {
                for (String token : rec.split(" ")) {
                    if (token.startsWith("+")) {
                        ahead = parseInt(token.substring(1));
                    } else if (token.startsWith("-")) {
                        behind = parseInt(token.substring(1));
                    }
                }
            }
        }

        List<StatusEntry> entries = walkStatusEntries(status.stdout());
        List<Map<String, Object>> files = entries.stream().map(FilesystemDashboardController::classifiedStatusFile).toList();
        long added = 0;
        long removed = 0;
        for (LineCount count : numstat(cwd, List.of("HEAD")).values()) {
            added += count.added();
            removed += count.removed();
        }
        int scanned = 0;
        for (Map<String, Object> file : files) {
            if (Boolean.TRUE.equals(file.get("untracked")) && scanned++ < 500) {
                added += untrackedInsertions(cwd, String.valueOf(file.get("path")));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("branch", branch);
        response.put("defaultBranch", defaultBranchName(cwd));
        response.put("detached", detached);
        response.put("ahead", ahead);
        response.put("behind", behind);
        response.put("staged", files.stream().filter(file -> Boolean.TRUE.equals(file.get("staged"))).count());
        response.put("unstaged", files.stream().filter(file -> Boolean.TRUE.equals(file.get("unstaged"))).count());
        response.put("untracked", files.stream().filter(file -> Boolean.TRUE.equals(file.get("untracked"))).count());
        response.put("conflicted", files.stream().filter(file -> Boolean.TRUE.equals(file.get("conflicted"))).count());
        response.put("changed", files.size());
        response.put("added", added);
        response.put("removed", removed);
        response.put("files", files.stream().limit(200).toList());
        return response;
    }

    private static Map<String, Object> reviewList(Path cwd, String rawScope, String baseRef) {
        if (!Files.isDirectory(cwd)) {
            return reviewListResponse(List.of(), null);
        }
        String scope = rawScope == null || rawScope.isBlank() ? "uncommitted" : rawScope;
        if ("branch".equals(scope) || "lastTurn".equals(scope)) {
            String base = "branch".equals(scope) ? branchBase(cwd) : baseRef;
            if (base == null || base.isBlank()) {
                return reviewListResponse(List.of(), null);
            }
            String range = "branch".equals(scope) ? base + "...HEAD" : base;
            List<Map<String, Object>> files = new ArrayList<>();
            for (Map.Entry<String, LineCount> entry : numstat(cwd, List.of(range)).entrySet()) {
                files.add(reviewFile(entry.getKey(), entry.getValue().added(), entry.getValue().removed(), "M", false));
            }
            if ("lastTurn".equals(scope)) {
                Set<String> seen = files.stream().map(file -> String.valueOf(file.get("path"))).collect(java.util.stream.Collectors.toSet());
                for (StatusEntry entry : walkStatusEntries(gitOutput(cwd, List.of("status", "--porcelain=v2", "-z")))) {
                    if ("?".equals(entry.tag()) && !seen.contains(entry.path())) {
                        files.add(reviewFile(entry.path(), 0, 0, "?", false));
                    }
                }
            }
            fillUntrackedCounts(cwd, files);
            files.sort(Comparator.comparing(file -> String.valueOf(file.get("path"))));
            return reviewListResponse(files, base);
        }

        GitResult status = git(cwd, List.of("status", "--porcelain=v2", "-z"));
        if (status.code() != 0) {
            return reviewListResponse(List.of(), null);
        }
        Map<String, LineCount> staged = numstat(cwd, List.of("--cached"));
        Map<String, LineCount> unstaged = numstat(cwd, List.of());
        List<Map<String, Object>> files = new ArrayList<>();
        for (StatusEntry entry : walkStatusEntries(status.stdout())) {
            LineCount cached = staged.getOrDefault(entry.path(), new LineCount(0, 0));
            LineCount worktree = unstaged.getOrDefault(entry.path(), new LineCount(0, 0));
            files.add(reviewFile(
                entry.path(),
                cached.added() + worktree.added(),
                cached.removed() + worktree.removed(),
                statusLetter(entry),
                entryStaged(entry)));
        }
        fillUntrackedCounts(cwd, files);
        files.sort(Comparator.comparing(file -> String.valueOf(file.get("path"))));
        return reviewListResponse(files, null);
    }

    private static String reviewDiff(Path cwd, String file, String rawScope, String baseRef, boolean staged) {
        if (!Files.isDirectory(cwd)) {
            return "";
        }
        String scope = rawScope == null || rawScope.isBlank() ? "uncommitted" : rawScope;
        if ("branch".equals(scope)) {
            String base = branchBase(cwd);
            return base == null || base.isBlank() ? "" : gitOutput(cwd, List.of("diff", base + "...HEAD", "--", file));
        }
        if ("lastTurn".equals(scope)) {
            return baseRef == null || baseRef.isBlank() ? "" : gitOutput(cwd, List.of("diff", baseRef, "--", file));
        }
        if (staged) {
            return gitOutput(cwd, List.of("diff", "--cached", "--", file));
        }
        String worktree = gitOutput(cwd, List.of("diff", "--", file));
        if (!worktree.isBlank()) {
            return worktree;
        }
        return git(cwd, List.of("diff", "--no-index", "--", nullDevice(), file)).stdout();
    }

    private static Map<String, Object> reviewCommitContext(Path cwd) {
        if (!Files.isDirectory(cwd)) {
            return Map.of("diff", "", "recent", "");
        }
        GitResult status = git(cwd, List.of("status", "--porcelain=v2", "-z"));
        if (status.code() != 0) {
            return Map.of("diff", "", "recent", "");
        }
        List<StatusEntry> entries = walkStatusEntries(status.stdout());
        boolean hasStaged = entries.stream().anyMatch(FilesystemDashboardController::entryStaged);
        String diff = hasStaged ? gitOutput(cwd, List.of("diff", "--cached")) : gitOutput(cwd, List.of("diff", "HEAD"));
        int maxChars = 120_000;
        if (diff.length() > maxChars) {
            int omitted = diff.length() - maxChars;
            diff = diff.substring(0, maxChars) + "\n# diff truncated: " + omitted + " chars omitted\n";
        }
        List<String> untracked = entries.stream().filter(entry -> "?".equals(entry.tag())).map(StatusEntry::path).toList();
        if (!untracked.isEmpty()) {
            StringBuilder note = new StringBuilder("\n# New (untracked) files:\n");
            int visible = Math.min(untracked.size(), 80);
            for (int i = 0; i < visible; i++) {
                note.append("#   ").append(untracked.get(i)).append('\n');
            }
            if (untracked.size() > visible) {
                note.append("#   ... ").append(untracked.size() - visible).append(" more omitted\n");
            }
            diff = diff.isBlank() ? note.toString() : diff + note;
        }
        return Map.of("diff", diff, "recent", gitOutput(cwd, List.of("log", "-n", "10", "--pretty=format:%s")).strip());
    }

    private static String reviewRevParse(Path cwd, String ref) {
        String value = gitOutput(cwd, List.of("rev-parse", ref == null || ref.isBlank() ? "HEAD" : ref)).strip();
        return value.isBlank() ? null : value;
    }

    private static Map<String, Object> reviewShipInfo(Path cwd) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ghReady", false);
        response.put("pr", null);
        return response;
    }

    private static List<Map<String, Object>> worktreeList(Path cwd) {
        String out = gitOutput(cwd, List.of("worktree", "list", "--porcelain"));
        if (out.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rawTrees = new ArrayList<>();
        Map<String, Object> current = null;
        for (String line : out.split("\n")) {
            if (line.startsWith("worktree ")) {
                if (current != null) {
                    rawTrees.add(current);
                }
                current = new LinkedHashMap<>();
                current.put("path", line.substring("worktree ".length()).trim());
                current.put("branch", null);
                current.put("detached", false);
                current.put("locked", false);
            } else if (current == null) {
                continue;
            } else if (line.startsWith("branch ")) {
                current.put("branch", line.substring("branch ".length()).trim().replaceFirst("^refs/heads/", ""));
            } else if ("detached".equals(line)) {
                current.put("detached", true);
            } else if (line.startsWith("locked")) {
                current.put("locked", true);
            }
        }
        if (current != null) {
            rawTrees.add(current);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < rawTrees.size(); i++) {
            Map<String, Object> raw = rawTrees.get(i);
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("path", raw.get("path"));
            tree.put("branch", raw.get("branch"));
            tree.put("isMain", i == 0);
            tree.put("detached", raw.get("detached"));
            tree.put("locked", raw.get("locked"));
            result.add(tree);
        }
        return result;
    }

    private static List<Map<String, Object>> branchList(Path cwd) {
        String out = gitOutput(cwd, List.of("for-each-ref", "--format=%(refname:short)", "--sort=-committerdate", "refs/heads"));
        if (out.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> trees = worktreeList(cwd);
        Map<String, String> pathByBranch = new LinkedHashMap<>();
        for (Map<String, Object> tree : trees) {
            Object branch = tree.get("branch");
            if (branch != null) {
                pathByBranch.put(String.valueOf(branch), String.valueOf(tree.get("path")));
            }
        }
        String trunk = defaultBranchName(cwd);
        List<String> locals = out.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
        Set<String> localSet = Set.copyOf(locals);
        List<String> remotes = gitOutput(cwd, List.of("for-each-ref", "--format=%(refname:short)", "--sort=-committerdate", "refs/remotes"))
            .lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .filter(line -> !line.endsWith("/HEAD"))
            .filter(line -> {
                int slash = line.indexOf('/');
                String shortName = slash >= 0 ? line.substring(slash + 1) : line;
                return !localSet.contains(shortName);
            })
            .toList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : locals) {
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("name", name);
            branch.put("checkedOut", pathByBranch.containsKey(name));
            branch.put("isDefault", trunk != null && trunk.equals(name));
            branch.put("isRemote", false);
            branch.put("worktreePath", pathByBranch.get(name));
            result.add(branch);
        }
        for (String name : remotes) {
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("name", name);
            branch.put("checkedOut", false);
            branch.put("isDefault", false);
            branch.put("isRemote", true);
            branch.put("worktreePath", null);
            result.add(branch);
        }
        return result;
    }

    private static List<Map<String, Object>> baseBranchList(Path cwd) {
        String out = gitOutput(
            cwd,
            List.of(
                "for-each-ref",
                "--format=%(refname:short)\t%(committerdate:iso)",
                "--sort=-committerdate",
                "refs/heads",
                "refs/remotes"));
        if (out.isBlank()) {
            return List.of();
        }
        String remoteDefault = gitOutput(cwd, List.of("symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")).strip();
        String localDefault = remoteDefault.isBlank() ? defaultBranchName(cwd) : "";
        List<Map<String, Object>> result = new ArrayList<>();
        for (String line : out.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String name = trimmed.split("\t", 2)[0];
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("name", name);
            branch.put("isRemote", name.contains("/"));
            branch.put("isDefault", (!remoteDefault.isBlank() && name.equals(remoteDefault))
                || (remoteDefault.isBlank() && localDefault != null && !localDefault.isBlank() && name.equals(localDefault)));
            result.add(branch);
        }
        return result;
    }

    private static Map<String, Object> reviewListResponse(List<Map<String, Object>> files, String base) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("files", files);
        response.put("base", base);
        return response;
    }

    private static Map<String, Object> reviewFile(String path, long added, long removed, String status, boolean staged) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("path", path);
        file.put("added", added);
        file.put("removed", removed);
        file.put("status", status);
        file.put("staged", staged);
        return file;
    }

    private static Map<String, Object> classifiedStatusFile(StatusEntry entry) {
        boolean staged = entryStaged(entry);
        boolean unstaged = "?".equals(entry.tag()) || (("1".equals(entry.tag()) || "2".equals(entry.tag())) && entry.xy().length() > 1 && entry.xy().charAt(1) != '.' && entry.xy().charAt(1) != '?');
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("path", entry.path());
        file.put("staged", staged);
        file.put("unstaged", unstaged);
        file.put("untracked", "?".equals(entry.tag()));
        file.put("conflicted", "u".equals(entry.tag()));
        return file;
    }

    private static List<StatusEntry> walkStatusEntries(String raw) {
        List<StatusEntry> entries = new ArrayList<>();
        String[] records = raw.split("\u0000");
        for (int i = 0; i < records.length; i++) {
            String rec = records[i];
            if (rec.isBlank() || rec.startsWith("#")) {
                continue;
            }
            String tag = rec.substring(0, 1);
            if ("?".equals(tag)) {
                entries.add(new StatusEntry("?", "??", rec.length() > 2 ? rec.substring(2) : ""));
            } else if ("u".equals(tag)) {
                String[] parts = rec.split(" ", 11);
                if (parts.length >= 11) {
                    entries.add(new StatusEntry("u", parts[1], parts[10]));
                }
            } else if ("1".equals(tag) || "2".equals(tag)) {
                String[] parts = rec.split(" ", "1".equals(tag) ? 9 : 10);
                if (parts.length >= ("1".equals(tag) ? 9 : 10)) {
                    if ("2".equals(tag)) {
                        i++;
                    }
                    entries.add(new StatusEntry(tag, parts[1], resolveRenamePath(parts[parts.length - 1])));
                }
            }
        }
        return entries;
    }

    private static boolean entryStaged(StatusEntry entry) {
        return ("1".equals(entry.tag()) || "2".equals(entry.tag()))
            && !entry.xy().isBlank()
            && entry.xy().charAt(0) != '.'
            && entry.xy().charAt(0) != '?';
    }

    private static String statusLetter(StatusEntry entry) {
        if ("?".equals(entry.tag())) {
            return "?";
        }
        if ("u".equals(entry.tag())) {
            return "U";
        }
        char code = !entry.xy().isBlank() && entry.xy().charAt(0) != '.'
            ? entry.xy().charAt(0)
            : (entry.xy().length() > 1 ? entry.xy().charAt(1) : 'M');
        return String.valueOf(code == '.' ? 'M' : Character.toUpperCase(code));
    }

    private static Map<String, LineCount> numstat(Path cwd, List<String> args) {
        String out = gitOutput(cwd, concat(List.of("diff", "--numstat"), args));
        Map<String, LineCount> counts = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length < 3) {
                continue;
            }
            int added = "-".equals(parts[0]) ? 0 : parseInt(parts[0]);
            int removed = "-".equals(parts[1]) ? 0 : parseInt(parts[1]);
            counts.put(resolveRenamePath(parts[2]), new LineCount(added, removed));
        }
        return counts;
    }

    private static long untrackedInsertions(Path cwd, String relativePath) {
        try {
            Path target = cwd.resolve(relativePath).normalize();
            if (!target.startsWith(cwd.toAbsolutePath().normalize()) || !Files.isRegularFile(target) || Files.size(target) > 1024 * 1024) {
                return 0;
            }
            byte[] data = Files.readAllBytes(target);
            for (byte b : data) {
                if (b == 0) {
                    return 0;
                }
            }
            long lines = 0;
            for (byte b : data) {
                if (b == '\n') {
                    lines++;
                }
            }
            return data.length > 0 && data[data.length - 1] != '\n' ? lines + 1 : lines;
        } catch (IOException | SecurityException e) {
            return 0;
        }
    }

    private static void fillUntrackedCounts(Path cwd, List<Map<String, Object>> files) {
        for (Map<String, Object> file : files) {
            if ("?".equals(file.get("status")) && "0".equals(String.valueOf(file.get("added"))) && "0".equals(String.valueOf(file.get("removed")))) {
                file.put("added", untrackedInsertions(cwd, String.valueOf(file.get("path"))));
            }
        }
    }

    private static String branchBase(Path cwd) {
        List<String> candidates = new ArrayList<>();
        String head = gitOutput(cwd, List.of("rev-parse", "--abbrev-ref", "origin/HEAD")).strip();
        if (!head.isBlank()) {
            candidates.add(head);
        }
        candidates.addAll(List.of("origin/main", "origin/master", "main", "master"));
        for (String ref : candidates) {
            String base = gitOutput(cwd, List.of("merge-base", "HEAD", ref)).strip();
            if (!base.isBlank()) {
                return base;
            }
        }
        return null;
    }

    private static String defaultBranchName(Path cwd) {
        String head = gitOutput(cwd, List.of("rev-parse", "--abbrev-ref", "origin/HEAD")).strip();
        if (!head.isBlank() && !"origin/HEAD".equals(head)) {
            int slash = head.indexOf('/');
            return slash >= 0 ? head.substring(slash + 1) : head;
        }
        for (String ref : List.of("refs/heads/main", "refs/heads/master", "refs/remotes/origin/main", "refs/remotes/origin/master")) {
            if (git(cwd, List.of("rev-parse", "--verify", "--quiet", ref)).code() == 0) {
                int slash = ref.lastIndexOf('/');
                return slash >= 0 ? ref.substring(slash + 1) : ref;
            }
        }
        return null;
    }

    private static String resolveRenamePath(String raw) {
        String path = raw == null ? "" : raw.trim();
        if (!path.contains(" => ")) {
            return path;
        }
        int open = path.indexOf('{');
        int close = path.indexOf('}', open + 1);
        if (open >= 0 && close > open) {
            String head = path.substring(0, open);
            String inner = path.substring(open + 1, close);
            String suffix = path.substring(close + 1);
            int arrow = inner.indexOf(" => ");
            if (arrow >= 0) {
                return (head + inner.substring(arrow + 4) + suffix).replace("//", "/");
            }
        }
        int arrow = path.lastIndexOf(" => ");
        return arrow >= 0 ? path.substring(arrow + 4).trim() : path;
    }

    private static List<String> concat(List<String> left, List<String> right) {
        List<String> out = new ArrayList<>(left);
        out.addAll(right);
        return out;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value == null || value.isBlank() ? "0" : value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ResponseEntity<?> managedFileResponse(
        String rawPath,
        String dispositionType,
        boolean mediaOnly,
        String rangeHeader,
        boolean head
    ) {
        ManagedFile file;
        try {
            file = managedRegularFile(rawPath, mediaOnly);
        } catch (ApiError e) {
            return apiError(e);
        }
        if (file.byteSize() > MANAGED_FILE_MAX_BYTES) {
            return status(HttpStatus.CONTENT_TOO_LARGE, "File is too large");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeType(file.path())));
        headers.setContentLength(file.byteSize());
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDispositionHeader(dispositionType, file.path().getFileName().toString()));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (mediaOnly) {
            headers.set("X-Content-Type-Options", "nosniff");
        }

        ByteRange range = parseRange(rangeHeader, file.byteSize());
        if (range != null) {
            headers.setContentLength(range.length());
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + range.start() + "-" + range.end() + "/" + file.byteSize());
            if (head) {
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).build();
            }
            try {
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).body(readRange(file.path(), range));
            } catch (IOException | SecurityException e) {
                return status(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file: " + e.getMessage());
            }
        }

        if (head) {
            return ResponseEntity.ok().headers(headers).build();
        }
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file.path()));
    }

    private ManagedFile managedRegularFile(String rawPath, boolean mediaOnly) {
        ManagedPath managed = resolveManagedPath(rawPath, false);
        Path target = managed.target();
        if (!Files.exists(target)) {
            throw apiErrorException(HttpStatus.NOT_FOUND, "File not found");
        }
        if (!Files.isRegularFile(target)) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, Files.isDirectory(target) ? "Path is not a file" : "Only regular files can be read");
        }
        if (isSensitivePath(target)) {
            throw apiErrorException(HttpStatus.FORBIDDEN, "Access to sensitive files is not allowed");
        }
        if (!managedReadAllowed(target)) {
            throw apiErrorException(HttpStatus.FORBIDDEN, "File is not readable");
        }
        if (mediaOnly && !STREAMABLE_MEDIA_EXTENSIONS.contains(extension(target))) {
            throw apiErrorException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
        }
        try {
            return new ManagedFile(managed.policy(), target, managed.displayPath(), Files.size(target));
        } catch (IOException | SecurityException e) {
            throw apiErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not stat file: " + e.getMessage());
        }
    }

    private ManagedPath resolveManagedPath(String rawPath, boolean forWrite) {
        ManagedFilesPolicy policy = managedFilesPolicy();
        String text = pathText(rawPath);
        Path root = policy.lockedRoot();
        Path candidate;

        if (root != null && (text.isEmpty() || ".".equals(text) || "/".equals(text))) {
            candidate = root;
        } else if (text.isEmpty()) {
            candidate = policy.defaultPath();
        } else {
            candidate = pathFromText(text);
            if (root != null && !candidate.isAbsolute()) {
                if (hasTraversal(candidate)) {
                    throw apiErrorException(HttpStatus.BAD_REQUEST, "Path cannot contain '..'");
                }
                candidate = root.resolve(candidate);
            } else if (!candidate.isAbsolute()) {
                throw apiErrorException(HttpStatus.BAD_REQUEST, "Path must be absolute");
            }
        }

        if (hasTraversal(candidate)) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Path cannot contain '..'");
        }

        Path resolved;
        if (forWrite && !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = candidate.getParent();
            if (parent == null) {
                throw apiErrorException(HttpStatus.BAD_REQUEST, "Invalid path");
            }
            resolved = resolveExistingForSafety(parent).resolve(candidate.getFileName().toString()).normalize();
        } else {
            resolved = resolveExistingForSafety(candidate);
        }

        if (root != null && !isUnder(root, resolved)) {
            throw apiErrorException(HttpStatus.FORBIDDEN, "Path outside managed files root");
        }
        return new ManagedPath(policy, resolved, resolved.toString());
    }

    private ManagedFilesPolicy managedFilesPolicy() {
        String forcedRoot = System.getenv("HERMES_DASHBOARD_FILES_ROOT");
        if (forcedRoot != null && !forcedRoot.isBlank()) {
            Path root = ensureManagedRoot(pathFromText(forcedRoot.trim()));
            return new ManagedFilesPolicy(root, root, false);
        }
        if (defaultHermesRootIsOptData()) {
            Path root = ensureManagedRoot(HOSTED_MANAGED_FILES_ROOT);
            return new ManagedFilesPolicy(root, root, false);
        }
        return new ManagedFilesPolicy(resolveExistingForSafety(Path.of(System.getProperty("user.home", "."))), null, true);
    }

    private static Path ensureManagedRoot(Path rawRoot) {
        try {
            Files.createDirectories(rawRoot);
            Path root = rawRoot.toRealPath().normalize();
            if (!Files.isDirectory(root)) {
                throw apiErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Managed files root is not a directory");
            }
            return root;
        } catch (IOException | SecurityException e) {
            throw apiErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Managed files root is unavailable: " + e.getMessage());
        }
    }

    private static boolean defaultHermesRootIsOptData() {
        String raw = configuredHermesHome();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return resolveExistingForSafety(pathFromText(raw.trim())).equals(HOSTED_MANAGED_FILES_ROOT.toAbsolutePath().normalize());
    }

    private ResponseEntity<Map<String, Object>> managedWriteResponse(ManagedPath managed) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("entry", managedFileEntry(managed.policy(), managed.target()));
        response.put("path", managed.displayPath());
        response.putAll(managedResponseMeta(managed.policy()));
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> managedFileEntry(ManagedFilesPolicy policy, Path target) {
        Path resolved = resolveExistingForSafety(target);
        if (policy.lockedRoot() != null && !isUnder(policy.lockedRoot(), resolved)) {
            throw apiErrorException(HttpStatus.FORBIDDEN, "Path outside managed files root");
        }
        try {
            boolean directory = Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", resolved.getFileName() != null ? resolved.getFileName().toString() : resolved.toString());
            entry.put("path", resolved.toString());
            entry.put("is_directory", directory);
            entry.put("size", directory ? null : Files.size(resolved));
            entry.put("mtime", Files.getLastModifiedTime(resolved, LinkOption.NOFOLLOW_LINKS).toMillis() / 1000.0d);
            entry.put("mime_type", directory ? null : mimeType(resolved));
            return entry;
        } catch (IOException | SecurityException e) {
            throw apiErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not stat path: " + e.getMessage());
        }
    }

    private static Map<String, Object> managedResponseMeta(ManagedFilesPolicy policy) {
        String lockedRoot = policy.lockedRoot() != null ? policy.lockedRoot().toString() : null;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("root", lockedRoot);
        meta.put("locked_root", lockedRoot);
        meta.put("can_change_path", policy.canChangePath());
        return meta;
    }

    private static String managedParent(ManagedPath managed) {
        Path target = managed.target();
        Path lockedRoot = managed.policy().lockedRoot();
        Path parent = target.getParent();
        if (parent == null || parent.equals(target)) {
            return null;
        }
        if (lockedRoot != null && target.equals(lockedRoot)) {
            return null;
        }
        return parent.toString();
    }

    private boolean managedReadAllowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!isWithinAllowedPaths(normalized)) {
            return false;
        }
        return fileSafety == null || !fileSafety.isReadBlocked(normalized);
    }

    private void writeManagedBytes(Path target, byte[] data, boolean overwrite) throws IOException {
        validateManagedWriteTarget(target, overwrite);
        Path tmp = temporarySibling(target, ".upload");
        boolean moved = false;
        try {
            Files.write(tmp, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveReplace(tmp, target);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private void writeManagedStream(Path target, MultipartFile file, boolean overwrite) throws IOException {
        if (file == null) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Upload file must be provided");
        }
        if (file.getSize() > MANAGED_FILE_MAX_BYTES) {
            throw apiErrorException(HttpStatus.CONTENT_TOO_LARGE, "File is too large");
        }
        validateManagedWriteTarget(target, overwrite);
        Path tmp = temporarySibling(target, ".upload");
        boolean moved = false;
        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[UPLOAD_BUFFER_BYTES];
            long total = 0;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > MANAGED_FILE_MAX_BYTES) {
                    throw apiErrorException(HttpStatus.CONTENT_TOO_LARGE, "File is too large");
                }
                output.write(buffer, 0, read);
            }
            moveReplace(tmp, target);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private void validateManagedWriteTarget(Path target, boolean overwrite) throws IOException {
        if (!isWriteAllowed(target)) {
            throw apiErrorException(HttpStatus.FORBIDDEN, "File is not writable");
        }
        if (Files.exists(target) && Files.isDirectory(target)) {
            throw apiErrorException(HttpStatus.CONFLICT, "A directory already exists at that path");
        }
        if (Files.exists(target) && !overwrite) {
            throw apiErrorException(HttpStatus.CONFLICT, "File already exists");
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        Files.createDirectories(parent);
    }

    private static DecodedDataUrl decodeDataUrl(String dataUrl) {
        String text = dataUrl == null ? "" : dataUrl.trim();
        int comma = text.indexOf(',');
        if (!text.startsWith("data:") || comma < 0) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Upload payload must be a data URL");
        }
        String header = text.substring(5, comma);
        if (!header.contains(";base64")) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Upload payload must be base64 encoded");
        }
        String encoded = text.substring(comma + 1);
        byte[] data;
        try {
            data = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Upload payload is not valid base64");
        }
        if (data.length > MANAGED_FILE_MAX_BYTES) {
            throw apiErrorException(HttpStatus.CONTENT_TOO_LARGE, "File is too large");
        }
        String mimeType = header.substring(0, header.indexOf(';'));
        return new DecodedDataUrl(data, mimeType.isBlank() ? "application/octet-stream" : mimeType);
    }

    private static ChatImageDecoded decodeChatImageUpload(ChatImageUpload upload) {
        DecodedDataUrl decoded = decodeDataUrl(upload.dataUrl());
        String mimeType = decoded.mimeType();
        if (!mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Upload payload must be an image");
        }
        if (decoded.data().length > CHAT_IMAGE_UPLOAD_MAX_BYTES) {
            long mb = CHAT_IMAGE_UPLOAD_MAX_BYTES / (1024 * 1024);
            throw apiErrorException(HttpStatus.CONTENT_TOO_LARGE, "Image is too large; cap is " + mb + " MB");
        }
        String extension = chatImageExtension(decoded.data());
        if (!CHAT_IMAGE_ALLOWED_EXTENSIONS.contains(extension)) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Unsupported image type");
        }
        return new ChatImageDecoded(decoded.data(), mimeType, extension);
    }

    private static String chatImageExtension(byte[] data) {
        if (startsWith(data, new byte[] {'R', 'I', 'F', 'F'}) && data.length >= 12
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return ".webp";
        }
        if (startsWith(data, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) {
            return ".png";
        }
        if (startsWith(data, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return ".jpg";
        }
        if (startsWith(data, new byte[] {'G', 'I', 'F', '8', '7', 'a'})
            || startsWith(data, new byte[] {'G', 'I', 'F', '8', '9', 'a'})) {
            return ".gif";
        }
        if (startsWith(data, new byte[] {'B', 'M'})) {
            return ".bmp";
        }
        return "";
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sanitizeChatImageStem(String filename) {
        String candidate = sanitizeChatImageFilename(filename);
        String stem = filenameStem(candidate);
        stem = CHAT_IMAGE_STEM_UNSAFE.matcher(stem).replaceAll("_");
        stem = stripCharacters(stem, "._-");
        return stem.isBlank() ? "pasted-image" : stem;
    }

    private static String sanitizeChatImageFilename(String filename) {
        String raw = filename == null ? "" : filename.trim();
        String candidate;
        try {
            Path name = Path.of(raw).getFileName();
            candidate = name != null ? name.toString() : "";
        } catch (RuntimeException e) {
            candidate = raw;
        }
        candidate = CHAT_IMAGE_CONTROL_CHARS.matcher(candidate).replaceAll("_").strip();
        candidate = stripCharacters(candidate, ".");
        return candidate.isBlank() ? "pasted-image" : candidate;
    }

    private static String filenameStem(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String stripCharacters(String text, String chars) {
        int start = 0;
        int end = text.length();
        while (start < end && chars.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(start, end);
    }

    private Path chatImageHomePath(String profile) {
        String requested = profile == null ? "" : profile.trim();
        if (requested.isBlank() || "current".equalsIgnoreCase(requested)) {
            return hermesHomePath();
        }
        String normalized = normalizeProfileName(requested);
        if ("default".equals(normalized)) {
            return hermesHomePath();
        }
        if (!isValidNamedProfileName(normalized)) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Invalid profile name: " + profile);
        }
        Path profileDir = profilesRootPath().resolve(normalized).toAbsolutePath().normalize();
        if (!Files.isDirectory(profileDir)) {
            throw apiErrorException(HttpStatus.NOT_FOUND, "Profile '" + normalized + "' does not exist.");
        }
        return profileDir;
    }

    private Path profilesRootPath() {
        String baseDir = properties != null && properties.getProfile() != null
            ? clean(properties.getProfile().getBaseDir())
            : null;
        return baseDir != null
            ? Path.of(baseDir).toAbsolutePath().normalize()
            : hermesHomePath().resolve("profiles").toAbsolutePath().normalize();
    }

    private static boolean isValidNamedProfileName(String value) {
        return value != null && PROFILE_ID.matcher(value).matches() && !RESERVED_PROFILE_NAMES.contains(value);
    }

    private static String normalizeProfileName(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        if ("default".equalsIgnoreCase(cleaned)) {
            return "default";
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        CHAT_IMAGE_RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private static boolean overwrite(Boolean value) {
        return value == null || value;
    }

    private static ByteRange parseRange(String rawRange, long size) {
        if (rawRange == null || rawRange.isBlank() || size <= 0) {
            return null;
        }
        String text = rawRange.trim();
        if (!text.startsWith("bytes=") || text.indexOf(',') >= 0) {
            return null;
        }
        String spec = text.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (left.isEmpty()) {
                long suffixLength = Long.parseLong(right);
                if (suffixLength <= 0) {
                    return null;
                }
                start = Math.max(0, size - suffixLength);
                end = size - 1;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? size - 1 : Long.parseLong(right);
            }
            if (start < 0 || end < start || start >= size) {
                return null;
            }
            return new ByteRange(start, Math.min(end, size - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] readRange(Path path, ByteRange range) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            input.skipNBytes(range.start());
            return input.readNBytes((int) range.length());
        }
    }

    private static void deleteRecursively(Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static Path temporarySibling(Path target, String suffix) {
        return target.resolveSibling("." + target.getFileName() + "." + ProcessHandle.current().pid() + "." + System.nanoTime() + suffix);
    }

    private static Comparator<Map<String, Object>> managedEntryComparator() {
        return Comparator
            .comparing((Map<String, Object> item) -> !Boolean.TRUE.equals(item.get("is_directory")))
            .thenComparing(item -> String.valueOf(item.get("name")).toLowerCase(Locale.ROOT))
            .thenComparing(item -> String.valueOf(item.get("name")));
    }

    private static String pathText(String rawPath) {
        String text = rawPath == null ? "" : rawPath.trim();
        if (text.indexOf('\0') >= 0) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        return text;
    }

    private static Path pathFromText(String text) {
        try {
            if (text.regionMatches(true, 0, "file:", 0, 5)) {
                URI uri = URI.create(text);
                String host = uri.getHost();
                if (host != null && !host.isBlank() && !"localhost".equalsIgnoreCase(host)) {
                    throw apiErrorException(HttpStatus.BAD_REQUEST, "Invalid path");
                }
                return Path.of(uri);
            }
            return expandHome(text);
        } catch (ApiError e) {
            throw e;
        } catch (RuntimeException e) {
            throw apiErrorException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
    }

    private static boolean hasTraversal(Path path) {
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnderAny(List<Path> roots, Path target) {
        for (Path root : roots) {
            if (isUnder(root, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnder(Path root, Path target) {
        Path normalizedRoot = resolveExistingForSafety(root);
        Path normalizedTarget = resolveExistingForSafety(target);
        return normalizedTarget.equals(normalizedRoot) || normalizedTarget.startsWith(normalizedRoot);
    }

    private static List<Path> mediaServeRoots() {
        Path home = hermesHomePath();
        return List.of(
            resolveExistingForSafety(home.resolve("images")),
            resolveExistingForSafety(home.resolve("screenshots")),
            resolveExistingForSafety(home.resolve("cache")));
    }

    private static Path hermesHomePath() {
        String configured = configuredHermesHome();
        if (configured != null && !configured.isBlank()) {
            return pathFromText(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."), ".hermes").toAbsolutePath().normalize();
    }

    private static String configuredHermesHome() {
        String property = System.getProperty("hermes.home");
        if (property != null && !property.isBlank()) {
            return property;
        }
        return System.getenv("HERMES_HOME");
    }

    private static boolean isImageMediaExtension(String extension) {
        String mime = MIME_BY_EXT.get(extension);
        return mime != null && mime.startsWith("image/");
    }

    private RegularFile regularFile(String rawPath) {
        Path target;
        try {
            target = fsPath(rawPath);
        } catch (IllegalArgumentException e) {
            return new RegularFile(null, 0, badRequest(e.getMessage()));
        }
        try {
            if (!Files.exists(target)) {
                return new RegularFile(null, 0, status(HttpStatus.NOT_FOUND, "File not found"));
            }
            if (Files.isDirectory(target)) {
                return new RegularFile(null, 0, badRequest("Path points to a directory"));
            }
            if (!Files.isRegularFile(target)) {
                return new RegularFile(null, 0, badRequest("Only regular files can be read"));
            }
            return new RegularFile(target, Files.size(target), null);
        } catch (SecurityException e) {
            return new RegularFile(null, 0, forbidden("File is not readable"));
        } catch (IOException e) {
            return new RegularFile(null, 0, badRequest(e.getMessage() != null ? e.getMessage() : "Invalid path"));
        }
    }

    private Path fsPath(String rawPath) {
        String raw = rawPath == null ? "" : rawPath.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("Path is required");
        }
        if (raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid path");
        }
        try {
            Path candidate;
            if (raw.regionMatches(true, 0, "file:", 0, 5)) {
                URI uri = URI.create(raw);
                String host = uri.getHost();
                if (host != null && !host.isBlank() && !"localhost".equalsIgnoreCase(host)) {
                    throw new IllegalArgumentException("Invalid path");
                }
                candidate = Path.of(uri);
            } else {
                candidate = expandHome(raw);
            }
            return candidate.toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid path", e);
        }
    }

    private static Path expandHome(String raw) {
        if ("~".equals(raw)) {
            return Path.of(System.getProperty("user.home", "."));
        }
        if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home", ".")).resolve(raw.substring(2));
        }
        return Path.of(raw);
    }

    private boolean isReadAllowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (isSensitivePath(normalized)) {
            return false;
        }
        if (!isWithinAllowedPaths(normalized)) {
            return false;
        }
        return fileSafety == null || !fileSafety.isReadBlocked(normalized);
    }

    private boolean isWriteAllowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return fileSafety == null
            || (fileSafety.isPathAllowed(normalized) && fileSafety.getCrossProfileWarning(normalized).isEmpty());
    }

    private boolean isWithinAllowedPaths(Path path) {
        if (properties == null || !properties.getSecurity().isFileSafetyEnabled()) {
            return true;
        }
        List<String> allowed = properties.getSecurity().getAllowedPaths();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        Path safetyPath = resolveExistingForSafety(path);
        for (String rawBase : allowed) {
            if (rawBase == null || rawBase.isBlank()) {
                continue;
            }
            Path base = resolveExistingForSafety(Path.of(rawBase));
            if (safetyPath.startsWith(base)) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveExistingForSafety(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                return absolute.toRealPath().normalize();
            }
            Path current = absolute;
            List<Path> missing = new ArrayList<>();
            while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Path fileName = current.getFileName();
                if (fileName != null) {
                    missing.add(fileName);
                }
                current = current.getParent();
            }
            if (current == null) {
                return absolute;
            }
            Path resolved = current.toRealPath().normalize();
            for (int i = missing.size() - 1; i >= 0; i--) {
                resolved = resolved.resolve(missing.get(i).toString());
            }
            return resolved.normalize();
        } catch (IOException | SecurityException e) {
            return absolute;
        }
    }

    private static boolean isHiddenDirectoryEntry(Path path) {
        String name = path.getFileName().toString();
        return READDIR_HIDDEN.contains(name) || isSensitivePath(path);
    }

    private static boolean isSensitivePath(Path path) {
        if (isSensitiveFilename(path.getFileName() != null ? path.getFileName().toString() : "")) {
            return true;
        }
        for (Path part : path) {
            if (SENSITIVE_DIR_NAMES.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSensitiveFilename(String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        return lowered.equals(".env")
            || lowered.startsWith(".env.")
            || lowered.equals(".envrc")
            || SENSITIVE_FILE_BASENAMES.contains(lowered);
    }

    private Map<String, Object> dirEntry(Path path) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", path.getFileName().toString());
        entry.put("path", path.toString());
        entry.put("isDirectory", Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
        return entry;
    }

    private static Comparator<Map<String, Object>> entryComparator() {
        return Comparator
            .comparing((Map<String, Object> item) -> !Boolean.TRUE.equals(item.get("isDirectory")))
            .thenComparing(item -> String.valueOf(item.get("name")).toLowerCase(Locale.ROOT))
            .thenComparing(item -> String.valueOf(item.get("name")));
    }

    private static Map<String, Object> listError(String error) {
        return Map.of("entries", List.of(), "error", error);
    }

    private static boolean looksBinary(byte[] data) {
        if (data.length == 0) {
            return false;
        }
        for (byte b : data) {
            if (b == 0) {
                return true;
            }
        }
        int suspicious = 0;
        for (byte b : data) {
            int value = b & 0xff;
            if (value < 32 && value != 9 && value != 10 && value != 13) {
                suspicious++;
            }
        }
        return ((double) suspicious / data.length) > 0.12d;
    }

    private static byte[] copyOf(byte[] data, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(data, 0, copy, 0, length);
        return copy;
    }

    private static String extension(Path path) {
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static String mimeType(Path path) {
        String ext = extension(path);
        if (MIME_BY_EXT.containsKey(ext)) {
            return MIME_BY_EXT.get(ext);
        }
        try {
            String probed = Files.probeContentType(path);
            return probed != null && !probed.isBlank() ? probed : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static void moveReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path defaultCwdPath() {
        String raw = System.getenv("TERMINAL_CWD");
        if (raw != null && !raw.isBlank() && !Set.of(".", "auto", "cwd").contains(raw.trim())) {
            try {
                Path candidate = expandHome(raw.trim()).toAbsolutePath().normalize();
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // Fall back to the JVM working directory.
            }
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static String findGitRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        for (int i = 0; i < 50; i++) {
            if (Files.exists(current.resolve(".git"))) {
                return current.toString();
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) {
                return null;
            }
            current = parent;
        }
        return null;
    }

    private static String gitBranch(Path cwd) {
        return gitOutput(cwd, List.of("branch", "--show-current")).strip();
    }

    private static String gitOutput(Path cwd, List<String> args) {
        GitResult result = git(cwd, args);
        return result.code() == 0 ? result.stdout() : "";
    }

    private static Map<String, Object> probeGhAuth() {
        Process process = null;
        try {
            process = new ProcessBuilder("gh", "auth", "status")
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Map.of("available", true, "authenticated", false);
            }
            return Map.of("available", true, "authenticated", process.exitValue() == 0);
        } catch (IOException e) {
            return Map.of("available", false, "authenticated", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return Map.of("available", true, "authenticated", false);
        } catch (RuntimeException e) {
            return Map.of("available", true, "authenticated", false);
        }
    }

    private static GitResult git(Path cwd, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(cwd.toString());
        command.addAll(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GCM_INTERACTIVE", "Never");
            Process process = builder.start();
            process.getOutputStream().close();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread stdoutReader = Thread.startVirtualThread(() -> {
                try {
                    process.getInputStream().transferTo(stdout);
                } catch (IOException ignored) {
                    // Keep git-backed dashboard reads best-effort.
                }
            });
            Thread stderrReader = Thread.startVirtualThread(() -> {
                try {
                    process.getErrorStream().transferTo(stderr);
                } catch (IOException ignored) {
                    // Keep git-backed dashboard reads best-effort.
                }
            });
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new GitResult(1, new String(stdout.toByteArray(), StandardCharsets.UTF_8), "git command timed out");
            }
            stdoutReader.join(TimeUnit.SECONDS.toMillis(1));
            stderrReader.join(TimeUnit.SECONDS.toMillis(1));
            return new GitResult(
                process.exitValue(),
                new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(1, "", "git command interrupted");
        } catch (IOException e) {
            return new GitResult(1, "", e.getMessage() != null ? e.getMessage() : "git invocation failed");
        }
    }

    private static Path resolveRepoFile(Path repoRoot, String rawFile) {
        try {
            Path file = Path.of(rawFile);
            if (file.isAbsolute()) {
                return file.toAbsolutePath().normalize();
            }
            return repoRoot.resolve(file).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String nullDevice() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null";
    }

    private static String attachmentHeader(String filename) {
        String safe = filename == null ? "download" : filename.replaceAll("[\\r\\n\"]", "_");
        return "attachment; filename=\"" + safe + "\"";
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return status(HttpStatus.BAD_REQUEST, detail);
    }

    private static ResponseEntity<Map<String, Object>> forbidden(String detail) {
        return status(HttpStatus.FORBIDDEN, detail);
    }

    private static ResponseEntity<Map<String, Object>> status(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> apiError(ApiError e) {
        return status(e.status, e.detail);
    }

    private static ApiError apiErrorException(HttpStatus status, String detail) {
        return new ApiError(status, detail);
    }

    private static String contentDispositionHeader(String type, String filename) {
        String safeType = "inline".equalsIgnoreCase(type) ? "inline" : "attachment";
        String safe = filename == null ? "download" : filename.replaceAll("[\\r\\n\"]", "_");
        return safeType + "; filename=\"" + safe + "\"";
    }

    private record FsWriteText(String path, String content) {
    }

    private record ChatImageUpload(
        @JsonProperty("data_url") String dataUrl,
        String filename
    ) {
    }

    private record ManagedFileUpload(
        String path,
        @JsonProperty("data_url") String dataUrl,
        Boolean overwrite
    ) {
    }

    private record ManagedDirectoryCreate(String path) {
    }

    private record ManagedFileDelete(String path, Boolean recursive) {
    }

    private record ManagedFilesPolicy(Path defaultPath, Path lockedRoot, boolean canChangePath) {
    }

    private record ManagedPath(ManagedFilesPolicy policy, Path target, String displayPath) {
    }

    private record ManagedFile(ManagedFilesPolicy policy, Path path, String displayPath, long byteSize) {
    }

    private record DecodedDataUrl(byte[] data, String mimeType) {
    }

    private record ChatImageDecoded(byte[] data, String mimeType, String extension) {
    }

    private record ByteRange(long start, long end) {
        long length() {
            return end - start + 1;
        }
    }

    private record GitPrListBody(String path, List<String> branches, List<Integer> numbers) {
    }

    private record GitResult(int code, String stdout, String stderr) {
    }

    private record StatusEntry(String tag, String xy, String path) {
    }

    private record LineCount(int added, int removed) {
    }

    private record RegularFile(
        Path path,
        long byteSize,
        ResponseEntity<Map<String, Object>> error
    ) {
    }

    private static final class ApiError extends RuntimeException {
        private final HttpStatus status;
        private final String detail;

        private ApiError(HttpStatus status, String detail) {
            super(detail);
            this.status = status;
            this.detail = detail;
        }
    }
}
