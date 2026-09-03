package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProfileService {

    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> RESERVED_PROFILE_NAMES = Set.of("hermes", "test", "tmp", "root", "sudo");
    private static final Set<String> PROFILE_DIRS = Set.of(
        "memories",
        "sessions",
        "skills",
        "skins",
        "logs",
        "plans",
        "workspace",
        "cron",
        "home");
    private static final List<String> CLONE_CONFIG_FILES = List.of("config.yaml", ".env", "SOUL.md");
    private static final List<String> CLONE_SUBDIR_FILES = List.of("memories/MEMORY.md", "memories/USER.md");
    private static final String NO_BUNDLED_SKILLS_MARKER = ".no-bundled-skills";
    private static final String DEFAULT_SOUL =
        "# SOUL\n\n"
            + "This profile has a dedicated persona file. Edit it from the dashboard to customize the agent.\n";
    private static final Set<String> DEFAULT_EXPORT_INCLUDE_ROOT = Set.of(
        "config.yaml", "SOUL.md", "MEMORY.md", "USER.md", "todo.json",
        "system_prompt.md", "AGENTS.md", "CLAUDE.md", ".cursorrules",
        "desktop.json", "skills", "cron", "scripts", "sessions",
        "plugins", "memories", "knowledge", "preferences");
    private static final Set<String> EXPORT_EXCLUDE_NAMES = Set.of(
        "auth.json", ".env", "auth.lock", "active_profile", ".update_check",
        "gateway.pid", "gateway_state.json", "processes.json",
        "state.db", "state.db-shm", "state.db-wal",
        "hermes_state.db", "response_store.db", "response_store.db-shm", "response_store.db-wal",
        "errors.log", ".hermes_history", "__pycache__", "package.json", "package-lock.json");
    private static final Set<String> EXPORT_EXCLUDE_DIRS = Set.of(
        "profiles", ".worktrees", "hermes-agent", "bin", "node_modules",
        "image_cache", "audio_cache", "document_cache", "browser_screenshots",
        "checkpoints", "sandboxes", "logs");
    private static final Set<String> EXPORT_REDACT_SUFFIXES = Set.of(
        ".md", ".txt", ".yaml", ".yml", ".json", ".jsonl", ".toml", ".ini",
        ".cfg", ".conf", ".py", ".sh", ".bash", ".zsh", ".js", ".ts",
        ".tsx", ".jsx", ".css", ".html", ".xml", ".csv");
    private static final Set<String> EXPORT_REDACT_NAMES = Set.of(".cursorrules");
    private static final Pattern[] EXPORT_SECRET_PATTERNS = {
        Pattern.compile("(?i)(sk-[A-Za-z0-9][A-Za-z0-9_\\-]{10,})"),
        Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]{10,}"),
        Pattern.compile("(?i)\\b([A-Z][A-Z0-9_]*(?:API[_-]?KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|PRIVATE[_-]?KEY|CLIENT[_-]?SECRET))=\\S+"),
        Pattern.compile("(?i)\\b(api[_-]?key|token|secret|password|passwd|credential|private[_-]?key|client[_-]?secret)\\s*[:=]\\s*[\"']?[^\"'\\s]+")
    };
    private static final DateTimeFormatter EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AgentProperties properties;
    private final RuntimeConfigService runtimeConfigService;

    public List<Map<String, Object>> listProfileRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addProfileRow(rows, seen, "default");

        Path root = profilesRoot();
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.list(root)) {
                stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .map(this::normalizeProfileNameOrNull)
                    .filter(this::isValidNamedProfileName)
                    .sorted(Comparator.naturalOrder())
                    .forEach(name -> addProfileRow(rows, seen, name));
            } catch (IOException e) {
                log.warn("Could not list profile root {}: {}", root, e.getMessage());
            }
        }

        addProfileRow(rows, seen, activeProfileName());
        return rows;
    }

    public Map<String, Object> createProfile(CreateProfileRequest request)
        throws IOException {
        String canon = normalizeProfileName(request.name());
        validateNamedProfileForCreate(canon);
        Path profileDir = profilePath(canon);
        if (Files.exists(profileDir)) {
            throw new FileAlreadyExistsException("Profile already exists: " + canon);
        }
        if (request.noSkills() && (request.cloneConfig() || request.cloneAll())) {
            throw new IllegalArgumentException("no_skills is mutually exclusive with clone options");
        }

        String cloneFrom = request.cloneFrom();
        Path sourceDir = null;
        if (request.cloneConfig() || request.cloneAll()) {
            String sourceName = cloneFrom != null && !cloneFrom.isBlank() ? cloneFrom : "default";
            sourceDir = requireProfileDirectory(sourceName);
        }

        if (request.cloneAll()) {
            copyProfileTree(sourceDir, profileDir);
        } else {
            bootstrapProfileDirectories(profileDir);
            if (sourceDir != null) {
                copyCloneConfig(sourceDir, profileDir);
            }
        }

        ensureProfileEnv(profileDir);
        ensureProfileSoul(profileDir);
        if (request.noSkills()) {
            atomicWriteString(profileDir.resolve(NO_BUNDLED_SKILLS_MARKER),
                "This profile opted out of bundled-skill seeding.\n");
        }
        if (request.description() != null) {
            writeProfileMeta(profileDir, request.description().trim(), false, null);
        }
        boolean modelSet = false;
        if (!isBlank(request.provider()) && !isBlank(request.model())) {
            writeProfileModel(profileDir, request.provider().trim(), request.model().trim(), clean(request.baseUrl()));
            modelSet = true;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("name", canon);
        response.put("path", profileDir.toString());
        response.put("model_set", modelSet);
        response.put("mcp_written", 0);
        response.put("skills_disabled", 0);
        response.put("hub_installs", List.of());
        return response;
    }

    public Map<String, Object> setActiveProfile(String name) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        if (!knownProfile(canon)) {
            throw new java.io.FileNotFoundException("Unknown profile: " + canon);
        }
        Path activePath = activeProfilePath();
        Files.createDirectories(activePath.getParent());
        if ("default".equals(canon)) {
            Files.deleteIfExists(activePath);
        } else {
            atomicWriteString(activePath, canon + "\n");
        }
        return Map.of("ok", true, "active", canon);
    }

    public Map<String, Object> renameProfile(String oldName, String newName) throws IOException {
        String oldCanon = normalizeProfileName(oldName);
        validateProfileName(oldCanon);
        requireKnownProfile(oldCanon);

        if ("default".equals(oldCanon)) {
            String display = clean(newName);
            if (display == null) {
                throw new IllegalArgumentException("Display name cannot be empty.");
            }
            writeProfileMeta(profilePath("default"), null, null, display);
            return Map.of(
                "ok", true,
                "name", "default",
                "display_name", display,
                "path", profilePath("default").toString());
        }

        String newCanon = normalizeProfileName(newName);
        validateNamedProfileForCreate(newCanon);
        Path oldDir = profilePath(oldCanon);
        Path newDir = profilePath(newCanon);
        if (!Files.isDirectory(oldDir)) {
            throw new java.io.FileNotFoundException("Unknown profile: " + oldCanon);
        }
        if (Files.exists(newDir)) {
            throw new FileAlreadyExistsException("Profile already exists: " + newCanon);
        }
        Files.createDirectories(profilesRoot());
        Files.move(oldDir, newDir);
        if (oldCanon.equals(activeProfileName())) {
            setActiveProfile(newCanon);
        }
        return Map.of("ok", true, "name", newCanon, "path", newDir.toString());
    }

    public Map<String, Object> deleteProfile(String name) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        if ("default".equals(canon)) {
            throw new IllegalArgumentException("The default profile cannot be deleted");
        }
        if (canon.equals(activeProfileName()) || canon.equals(currentProfileName())) {
            throw new IllegalArgumentException("The active/current profile cannot be deleted");
        }
        Path profileDir = requireProfileDirectory(canon);
        deleteTree(profileDir);
        return Map.of("ok", true, "path", profileDir.toString());
    }

    public Map<String, Object> readSoul(String name) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        requireKnownProfile(canon);
        Path soul = soulPath(canon);
        if (!Files.isRegularFile(soul)) {
            return Map.of("exists", false, "content", "");
        }
        return Map.of("exists", true, "content", Files.readString(soul, StandardCharsets.UTF_8));
    }

    public Map<String, Object> writeSoul(String name, String content) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        requireKnownProfile(canon);
        atomicWriteString(soulPath(canon), content != null ? content : "");
        return Map.of("ok", true);
    }

    public Map<String, Object> writeDescription(String name, String description) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        Path profileDir = requireProfileDirectory(canon);
        String text = description != null ? description.trim() : "";
        writeProfileMeta(profileDir, text, false, null);
        return Map.of("ok", true, "description", text, "description_auto", false);
    }

    public Map<String, Object> writeModel(String name, String provider, String model, String baseUrl) throws IOException {
        return writeModel(name, provider, model, baseUrl, null);
    }

    public Map<String, Object> writeModel(String name,
                                          String provider,
                                          String model,
                                          String baseUrl,
                                          String apiKey) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        Path profileDir = requireProfileDirectory(canon);
        String cleanProvider = clean(provider);
        String cleanModel = clean(model);
        if (cleanProvider == null || cleanModel == null) {
            throw new IllegalArgumentException("provider and model are required");
        }
        writeProfileModel(profileDir, cleanProvider, cleanModel, clean(baseUrl), clean(apiKey));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("provider", cleanProvider);
        response.put("model", cleanModel);
        if (clean(baseUrl) != null) {
            response.put("base_url", clean(baseUrl));
        }
        return response;
    }

    public Map<String, Object> exportProfile(String name, String output, Map<String, String> extraFiles) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        requireKnownProfile(canon);
        Path sourceDir = profilePath(canon);
        if (!Files.isDirectory(sourceDir)) {
            throw new java.io.FileNotFoundException("Profile '" + canon + "' does not exist.");
        }

        Path outputPath = resolveExportPath(canon, output);
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path stagingRoot = Files.createTempDirectory("java_agent_profile_export_");
        try {
            Path stagedProfile = stagingRoot.resolve(canon);
            copyExportTree(sourceDir, stagedProfile, canon);
            stageExtraFiles(stagedProfile, extraFiles);
            scrubExportSecrets(stagedProfile);
            writeTarGz(stagingRoot, canon, outputPath);
        } finally {
            deleteTempTree(stagingRoot);
        }
        return Map.of("ok", true, "archive", outputPath.toString());
    }

    public Map<String, Object> importProfile(String archivePath, String name) throws IOException {
        String archiveValue = clean(archivePath);
        if (archiveValue == null) {
            throw new IllegalArgumentException("archive path is required");
        }
        Path archive = Path.of(archiveValue).toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive)) {
            throw new java.io.FileNotFoundException("Archive not found: " + archive);
        }

        Set<String> roots = inspectProfileArchiveRoots(archive);
        String archiveRoot = roots.size() == 1 ? roots.iterator().next() : null;
        if (archiveRoot == null) {
            throw new IllegalArgumentException("Profile archive must contain exactly one top-level directory.");
        }
        String inferredName = clean(name) != null ? name : archiveRoot;
        String canon = normalizeProfileName(inferredName);
        validateProfileName(canon);
        if ("default".equals(canon)) {
            throw new IllegalArgumentException(
                "Cannot import as 'default' - that is the built-in root profile. Specify a different name.");
        }
        Path profileDir = profilePath(canon);
        if (Files.exists(profileDir)) {
            throw new FileAlreadyExistsException("Profile '" + canon + "' already exists at " + profileDir);
        }

        Files.createDirectories(profilesRoot());
        Path stagingRoot = Files.createTempDirectory("java_agent_profile_import_");
        try {
            safeExtractProfileArchive(archive, stagingRoot);
            Path extracted = stagingRoot.resolve(archiveRoot).toAbsolutePath().normalize();
            if (!Files.isDirectory(extracted)) {
                throw new IllegalArgumentException("Profile archive root is missing or invalid: " + archiveRoot);
            }
            Path finalSource = extracted;
            if (!archiveRoot.equals(canon)) {
                finalSource = stagingRoot.resolve(canon).toAbsolutePath().normalize();
                Files.move(extracted, finalSource);
            }
            Files.move(finalSource, profileDir);
        } finally {
            deleteTempTree(stagingRoot);
        }

        ensureProfileEnv(profileDir);
        ensureProfileSoul(profileDir);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("name", canon);
        response.put("path", profileDir.toString());
        response.put("desktop", readDesktopOverlay(profileDir));
        return response;
    }

    public String activeProfileName() {
        Path path = activeProfilePath();
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8);
            String canon = normalizeProfileName(value);
            return isValidProfileName(canon) ? canon : "default";
        } catch (IOException | IllegalArgumentException e) {
            return "default";
        }
    }

    public String currentProfileName() {
        String configured = properties.getProfile() != null ? properties.getProfile().getName() : null;
        try {
            String canon = normalizeProfileName(configured);
            return isValidProfileName(canon) ? canon : "default";
        } catch (IllegalArgumentException e) {
            return "default";
        }
    }

    public boolean knownProfile(String name) {
        String canon;
        try {
            canon = normalizeProfileName(name);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return "default".equals(canon) || Files.isDirectory(profilePath(canon));
    }

    public void requireKnownProfile(String name) throws IOException {
        if (!knownProfile(name)) {
            throw new java.io.FileNotFoundException("Unknown profile: " + normalizeProfileName(name));
        }
    }

    public Map<String, Object> readConfig(String name) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        requireKnownProfile(canon);
        return readYamlMap(profilePath(canon).resolve("config.yaml"));
    }

    public void writeConfig(String name, Map<String, Object> config) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        requireKnownProfile(canon);
        atomicWriteString(profilePath(canon).resolve("config.yaml"), dumpYaml(config));
    }

    public Path configPath(String name) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        requireKnownProfile(canon);
        return profilePath(canon).resolve("config.yaml");
    }

    public String readRawConfig(String name) throws IOException {
        Path path = configPath(name);
        if (!Files.isRegularFile(path)) {
            return "";
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public void writeRawConfig(String name, String yamlText) throws IOException {
        Object loaded = new Yaml().load(yamlText != null ? yamlText : "");
        if (!(loaded instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("YAML must be a mapping");
        }
        atomicWriteString(configPath(name), yamlText);
    }

    public String normalizeProfileName(String value) {
        if (value == null) {
            throw new IllegalArgumentException("profile name cannot be empty");
        }
        String cleaned = value.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("profile name cannot be empty");
        }
        if ("default".equalsIgnoreCase(cleaned)) {
            return "default";
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    public boolean isValidProfileName(String value) {
        return "default".equals(value) || isValidNamedProfileName(value);
    }

    public boolean isValidNamedProfileName(String value) {
        return value != null && PROFILE_ID.matcher(value).matches() && !RESERVED_PROFILE_NAMES.contains(value);
    }

    public void validateProfileName(String name) {
        if ("default".equals(name)) {
            return;
        }
        if (!isValidNamedProfileName(name)) {
            throw new IllegalArgumentException(
                "Invalid profile name '" + name + "'. Must match [a-z0-9][a-z0-9_-]{0,63}");
        }
    }

    public Path profilePath(String name) {
        String canon = normalizeProfileName(name);
        if ("default".equals(canon)) {
            return hermesHome();
        }
        Path root = profilesRoot();
        Path candidate = root.resolve(canon).toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Invalid profile path: " + name);
        }
        return candidate;
    }

    public Path profilesRoot() {
        Path configuredBase = configuredProfilesBaseDir();
        Path root = configuredBase != null
            ? configuredBase
            : hermesHome().resolve("profiles");
        return root.toAbsolutePath().normalize();
    }

    public Path hermesHome() {
        Path configuredBase = configuredProfilesBaseDir();
        if (configuredBase != null && configuredBase.getParent() != null) {
            return configuredBase.getParent().toAbsolutePath().normalize();
        }
        String env = System.getenv("HERMES_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."), ".hermes").toAbsolutePath().normalize();
    }

    public Path soulPath(String profile) {
        String canon = normalizeProfileName(profile);
        String configured = properties.getCore() != null ? clean(properties.getCore().getSoulMdPath()) : null;
        if ("default".equals(canon) && configured != null) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return profilePath(canon).resolve("SOUL.md");
    }

    private void addProfileRow(List<Map<String, Object>> rows, Set<String> seen, String name) {
        String profile = normalizeProfileNameOrNull(name);
        if (profile != null && seen.add(profile)) {
            rows.add(profileRow(profile));
        }
    }

    private Map<String, Object> profileRow(String name) {
        Path path = profilePath(name);
        ProfileMeta meta = readProfileMeta(path);
        ModelSelection model = readConfigModel(path);
        RuntimeConfigService.RuntimeModelSelection runtimeSelection = runtimeConfigService.getModelSelection();
        boolean current = currentProfileName().equals(name);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("display_name", meta.displayName());
        row.put("path", path.toString());
        row.put("is_default", "default".equals(name));
        row.put("has_env", Files.isRegularFile(path.resolve(".env")));
        row.put("skill_count", countSkills(path));
        row.put("gateway_running", false);
        row.put("description", meta.description());
        row.put("description_auto", meta.descriptionAuto());
        row.put("distribution_name", null);
        row.put("distribution_version", null);
        row.put("distribution_source", null);
        row.put("has_alias", false);
        row.put("provider", current && runtimeSelection != null && runtimeSelection.provider() != null
            ? runtimeSelection.provider()
            : current && model.provider() == null ? clean(properties.getModel().getProvider()) : model.provider());
        row.put("model", current && runtimeSelection != null && runtimeSelection.model() != null
            ? runtimeSelection.model()
            : current && model.model() == null ? clean(properties.getModel().getModelName()) : model.model());
        return row;
    }

    private Path activeProfilePath() {
        return hermesHome().resolve("active_profile");
    }

    private Path requireProfileDirectory(String name) throws IOException {
        String canon = normalizeProfileName(name);
        validateProfileName(canon);
        Path profileDir = profilePath(canon);
        if ("default".equals(canon)) {
            Files.createDirectories(profileDir);
            return profileDir;
        }
        if (!Files.isDirectory(profileDir)) {
            throw new java.io.FileNotFoundException("Unknown profile: " + canon);
        }
        return profileDir;
    }

    private void validateNamedProfileForCreate(String canon) {
        validateProfileName(canon);
        if ("default".equals(canon)) {
            throw new IllegalArgumentException(
                "Cannot create a profile named 'default' - it is the built-in profile.");
        }
    }

    private void bootstrapProfileDirectories(Path profileDir) throws IOException {
        Files.createDirectories(profileDir);
        for (String dir : PROFILE_DIRS) {
            Files.createDirectories(profileDir.resolve(dir));
        }
    }

    private void copyCloneConfig(Path sourceDir, Path profileDir) throws IOException {
        for (String fileName : CLONE_CONFIG_FILES) {
            Path source = sourceDir.resolve(fileName);
            if (Files.exists(source)) {
                Path target = profileDir.resolve(fileName);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                if (".env".equals(fileName)) {
                    restrictOwnerOnly(target);
                }
            }
        }
        Path sourceSkills = sourceDir.resolve("skills");
        if (Files.isDirectory(sourceSkills)) {
            copyTree(sourceSkills, profileDir.resolve("skills"));
        }
        for (String relative : CLONE_SUBDIR_FILES) {
            Path source = sourceDir.resolve(relative);
            if (Files.exists(source)) {
                Path target = profileDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private void copyProfileTree(Path sourceDir, Path profileDir) throws IOException {
        copyTree(sourceDir, profileDir);
        Files.deleteIfExists(profileDir.resolve("gateway.pid"));
        Files.deleteIfExists(profileDir.resolve("gateway_state.json"));
        Files.deleteIfExists(profileDir.resolve("processes.json"));
    }

    private void copyTree(Path sourceRoot, Path targetRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path source : stream.toList()) {
                Path relative = sourceRoot.relativize(source);
                if (relative.toString().isEmpty()) {
                    Files.createDirectories(targetRoot);
                    continue;
                }
                Path target = targetRoot.resolve(relative).toAbsolutePath().normalize();
                if (!target.startsWith(targetRoot.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("Unsafe profile copy target: " + relative);
                }
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void copyExportTree(Path sourceRoot, Path targetRoot, String profile) throws IOException {
        Files.createDirectories(targetRoot);
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path source : stream.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (source.equals(sourceRoot) || Files.isSymbolicLink(source)) {
                    continue;
                }
                Path relative = sourceRoot.relativize(source);
                if (!shouldExportProfilePath(relative, profile)) {
                    continue;
                }
                Path target = targetRoot.resolve(relative).toAbsolutePath().normalize();
                if (!target.startsWith(targetRoot.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("Unsafe profile export target: " + relative);
                }
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private boolean shouldExportProfilePath(Path relative, String profile) {
        if (relative == null || relative.getNameCount() == 0) {
            return false;
        }
        String first = relative.getName(0).toString();
        if ("default".equals(profile) && !DEFAULT_EXPORT_INCLUDE_ROOT.contains(first)) {
            return false;
        }
        for (Path part : relative) {
            String name = part.toString();
            if (EXPORT_EXCLUDE_NAMES.contains(name) || EXPORT_EXCLUDE_DIRS.contains(name)
                || name.endsWith(".sock") || name.endsWith(".tmp")) {
                return false;
            }
        }
        return true;
    }

    private void stageExtraFiles(Path stagedProfile, Map<String, String> extraFiles) throws IOException {
        if (extraFiles == null || extraFiles.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> extra : extraFiles.entrySet()) {
            List<String> parts = normalizeArchiveParts(extra.getKey());
            if (parts.stream().anyMatch(this::isCredentialArchiveName)) {
                throw new IllegalArgumentException("Credential files cannot be added to a profile export: " + extra.getKey());
            }
            Path target = stagedProfile;
            for (String part : parts) {
                target = target.resolve(part);
            }
            target = target.toAbsolutePath().normalize();
            if (!target.startsWith(stagedProfile.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Unsafe extra profile export path: " + extra.getKey());
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, extra.getValue() != null ? extra.getValue() : "", StandardCharsets.UTF_8);
        }
    }

    private void scrubExportSecrets(Path stagedProfile) throws IOException {
        try (Stream<Path> stream = Files.walk(stagedProfile)) {
            for (Path path : stream.toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !shouldRedactExportFile(path)) {
                    continue;
                }
                try {
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    String redacted = redactExportSecrets(text);
                    if (!redacted.equals(text)) {
                        Files.writeString(path, redacted, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                    }
                } catch (IOException ignored) {
                    // Keep binary or unreadable files out of the scrub pass; they were already filtered above.
                }
            }
        }
    }

    private boolean shouldRedactExportFile(Path path) {
        String name = path.getFileName().toString();
        if (EXPORT_REDACT_NAMES.contains(name) || name.toLowerCase(Locale.ROOT).endsWith(".env.example")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXPORT_REDACT_SUFFIXES.contains(name.substring(dot).toLowerCase(Locale.ROOT));
    }

    private String redactExportSecrets(String text) {
        String redacted = text != null ? text : "";
        for (Pattern pattern : EXPORT_SECRET_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement(redactedSecret(match.group())));
        }
        return redacted;
    }

    private String redactedSecret(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("bearer ")) {
            return token.substring(0, token.indexOf(' ') + 1) + "[REDACTED]";
        }
        int equals = token.indexOf('=');
        if (equals > 0) {
            return token.substring(0, equals + 1) + "[REDACTED]";
        }
        int colon = token.indexOf(':');
        if (colon > 0) {
            return token.substring(0, colon + 1) + "[REDACTED]";
        }
        return "[REDACTED]";
    }

    private Path resolveExportPath(String profile, String output) {
        String cleaned = clean(output);
        Path requested = cleaned == null
            ? hermesHome().resolve("profile-exports").resolve(profile + "-" + LocalDateTime.now().format(EXPORT_TIMESTAMP) + ".tar.gz")
            : Path.of(cleaned);
        String path = requested.toString();
        String base = path.endsWith(".tar.gz")
            ? path.substring(0, path.length() - ".tar.gz".length())
            : path.endsWith(".tgz") ? path.substring(0, path.length() - ".tgz".length()) : path;
        return Path.of(base + ".tar.gz").toAbsolutePath().normalize();
    }

    private void writeTarGz(Path stagingRoot, String profile, Path outputPath) throws IOException {
        Path source = stagingRoot.resolve(profile);
        try (OutputStream file = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedOutputStream buffered = new BufferedOutputStream(file);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(buffered);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
            try (Stream<Path> stream = Files.walk(source)) {
                for (Path path : stream.sorted(Comparator.comparing(Path::toString)).toList()) {
                    addTarEntry(tar, source, profile, path);
                }
            }
        }
    }

    private void addTarEntry(TarArchiveOutputStream tar, Path sourceRoot, String profile, Path path) throws IOException {
        Path relative = sourceRoot.relativize(path);
        String entryName = relative.toString().isEmpty()
            ? profile + "/"
            : profile + "/" + relative.toString().replace('\\', '/');
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            TarArchiveEntry entry = new TarArchiveEntry(entryName.endsWith("/") ? entryName : entryName + "/");
            entry.setModTime(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
            return;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        TarArchiveEntry entry = new TarArchiveEntry(entryName);
        entry.setSize(Files.size(path));
        entry.setModTime(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
        tar.putArchiveEntry(entry);
        Files.copy(path, tar);
        tar.closeArchiveEntry();
    }

    private Set<String> inspectProfileArchiveRoots(Path archive) throws IOException {
        Set<String> roots = new LinkedHashSet<>();
        try (TarArchiveInputStream tar = openTarGz(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                List<String> parts = normalizeArchiveParts(entry.getName());
                if (parts.size() > 1 || entry.isDirectory()) {
                    roots.add(parts.get(0));
                }
            }
        }
        return roots;
    }

    private void safeExtractProfileArchive(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        try (TarArchiveInputStream tar = openTarGz(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                List<String> parts = normalizeArchiveParts(entry.getName());
                if (parts.stream().anyMatch(this::isCredentialArchiveName)) {
                    continue;
                }
                Path target = resolveArchiveTarget(root, parts, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!entry.isFile()) {
                    throw new IllegalArgumentException("Unsupported archive member type: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    tar.transferTo(out);
                }
            }
        }
    }

    private TarArchiveInputStream openTarGz(Path archive) throws IOException {
        InputStream file = Files.newInputStream(archive);
        try {
            return new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(file)));
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    private Path resolveArchiveTarget(Path root, List<String> parts, String memberName) {
        Path target = root;
        for (String part : parts) {
            target = target.resolve(part);
        }
        target = target.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Unsafe archive member path: " + memberName);
        }
        return target;
    }

    private List<String> normalizeArchiveParts(String memberName) {
        String normalized = memberName == null ? "" : memberName.replace('\\', '/').trim();
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Unsafe archive member path: " + memberName);
        }
        List<String> parts = new ArrayList<>();
        for (String raw : normalized.split("/")) {
            if (raw.isEmpty() || ".".equals(raw)) {
                continue;
            }
            if ("..".equals(raw)) {
                throw new IllegalArgumentException("Unsafe archive member path: " + memberName);
            }
            parts.add(raw);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Unsafe archive member path: " + memberName);
        }
        return parts;
    }

    private boolean isCredentialArchiveName(String name) {
        return ".env".equals(name) || "auth.json".equals(name) || "auth.lock".equals(name);
    }

    private Object readDesktopOverlay(Path profileDir) {
        Path overlay = profileDir.resolve("desktop.json");
        if (!Files.isRegularFile(overlay)) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(overlay.toFile(), Object.class);
        } catch (IOException e) {
            log.warn("Could not parse imported desktop overlay {}: {}", overlay, e.getMessage());
            return null;
        }
    }

    private void deleteTempTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Could not remove temporary profile directory {}: {}", root, e.getMessage());
        }
    }

    private void ensureProfileEnv(Path profileDir) throws IOException {
        Path env = profileDir.resolve(".env");
        if (!Files.exists(env)) {
            Files.writeString(env,
                "# Per-profile secrets for this Java-agent profile.\n"
                    + "# API keys and tokens set here override the shell environment.\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
            restrictOwnerOnly(env);
        }
    }

    private void ensureProfileSoul(Path profileDir) throws IOException {
        Path soul = profileDir.resolve("SOUL.md");
        if (!Files.exists(soul)) {
            Files.writeString(soul, DEFAULT_SOUL, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    private ProfileMeta readProfileMeta(Path profileDir) {
        Path path = profileDir.resolve("profile.yaml");
        Map<String, Object> data = readYamlMap(path);
        return new ProfileMeta(
            stringValue(data.get("description")).trim(),
            Boolean.TRUE.equals(data.get("description_auto")),
            stringValue(data.get("display_name")).trim());
    }

    private void writeProfileMeta(Path profileDir,
                                  String description,
                                  Boolean descriptionAuto,
                                  String displayName) throws IOException {
        if (!Files.isDirectory(profileDir)) {
            throw new java.io.FileNotFoundException("profile directory does not exist: " + profileDir);
        }
        Path path = profileDir.resolve("profile.yaml");
        Map<String, Object> data = readYamlMap(path);
        if (description != null) {
            data.put("description", description.trim());
        }
        if (descriptionAuto != null) {
            data.put("description_auto", descriptionAuto);
        }
        if (displayName != null) {
            String cleaned = displayName.trim();
            if (cleaned.isEmpty()) {
                data.remove("display_name");
            } else {
                data.put("display_name", cleaned);
            }
        }
        atomicWriteString(path, dumpYaml(data));
    }

    private ModelSelection readConfigModel(Path profileDir) {
        Map<String, Object> config = readYamlMap(profileDir.resolve("config.yaml"));
        Object modelConfig = config.get("model");
        if (modelConfig instanceof Map<?, ?> modelMap) {
            Map<String, Object> modelData = toStringKeyMap(modelMap);
            String provider = clean(stringValue(modelData.get("provider")));
            String model = clean(stringValue(modelData.get("default")));
            if (model == null) {
                model = clean(stringValue(modelData.get("model")));
            }
            Object defaultValue = modelData.get("default");
            if (defaultValue instanceof Map<?, ?> defaultMap) {
                Map<String, Object> defaultData = toStringKeyMap(defaultMap);
                if (provider == null) {
                    provider = clean(stringValue(defaultData.get("provider")));
                }
                model = clean(firstNonBlank(
                    stringValue(defaultData.get("model")),
                    stringValue(defaultData.get("name")),
                    stringValue(defaultData.get("id"))));
            }
            return new ModelSelection(provider, model, clean(stringValue(modelData.get("base_url"))));
        }
        return new ModelSelection(null, clean(stringValue(modelConfig)), null);
    }

    @SuppressWarnings("unchecked")
    private void writeProfileModel(Path profileDir, String provider, String model, String baseUrl) throws IOException {
        writeProfileModel(profileDir, provider, model, baseUrl, null);
    }

    private void writeProfileModel(Path profileDir,
                                   String provider,
                                   String model,
                                   String baseUrl,
                                   String apiKey) throws IOException {
        Path path = profileDir.resolve("config.yaml");
        Map<String, Object> config = readYamlMap(path);
        Object rawModelConfig = config.get("model");
        Map<String, Object> modelConfig = rawModelConfig instanceof Map<?, ?> modelMap
            ? toStringKeyMap(modelMap)
            : new LinkedHashMap<>();
        modelConfig.put("provider", provider);
        modelConfig.put("default", model);
        modelConfig.remove("context_length");
        if (baseUrl == null) {
            modelConfig.remove("base_url");
        } else {
            modelConfig.put("base_url", baseUrl);
        }
        if (apiKey != null) {
            modelConfig.put("api_key", apiKey);
        }
        config.put("model", modelConfig);
        atomicWriteString(path, dumpYaml(config));
    }

    private Map<String, Object> readYamlMap(Path path) {
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<>();
        }
        try {
            Object loaded = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
            if (loaded instanceof Map<?, ?> map) {
                return toStringKeyMap(map);
            }
        } catch (Exception e) {
            log.warn("Could not parse YAML {}: {}", path, e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), yamlValue(value));
            }
        });
        return result;
    }

    private Object yamlValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringKeyMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::yamlValue).toList();
        }
        return value;
    }

    private String dumpYaml(Map<String, Object> data) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setAllowUnicode(true);
        String dumped = new Yaml(options).dump(data != null ? data : Map.of());
        return dumped.endsWith("\n") ? dumped : dumped + "\n";
    }

    private void atomicWriteString(Path target, String content) throws IOException {
        Path writeTarget = resolveAtomicWriteTarget(target);
        Path parent = writeTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, "." + writeTarget.getFileName(), ".tmp");
        boolean moved = false;
        try {
            copyExistingPermissions(writeTarget, temp);
            Files.writeString(
                temp,
                content != null ? content : "",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, writeTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, writeTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private Path resolveAtomicWriteTarget(Path target) throws IOException {
        if (!Files.isSymbolicLink(target)) {
            return target;
        }
        Path linkTarget = Files.readSymbolicLink(target);
        if (!linkTarget.isAbsolute()) {
            Path linkParent = target.toAbsolutePath().normalize().getParent();
            linkTarget = linkParent != null ? linkParent.resolve(linkTarget) : linkTarget;
        }
        return linkTarget.toAbsolutePath().normalize();
    }

    private void copyExistingPermissions(Path source, Path target) {
        try {
            if (Files.exists(source)) {
                Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source));
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some filesystems do not expose POSIX modes.
        }
    }

    private void restrictOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Best effort; Windows ACLs are outside POSIX mode handling.
        }
    }

    private void deleteTree(Path root) throws IOException {
        Path profilesRoot = profilesRoot();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!normalizedRoot.startsWith(profilesRoot)) {
            throw new IllegalArgumentException("Refusing to delete a path outside profiles root: " + root);
        }
        try (Stream<Path> stream = Files.walk(normalizedRoot)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private int countSkills(Path profileDir) {
        Path skillsDir = profileDir.resolve("skills");
        if (!Files.isDirectory(skillsDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(skillsDir)) {
            return (int) stream
                .filter(path -> Files.isRegularFile(path) && "SKILL.md".equals(path.getFileName().toString()))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private String normalizeProfileNameOrNull(String value) {
        try {
            return normalizeProfileName(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private Path configuredProfilesBaseDir() {
        String baseDir = properties.getProfile() != null ? clean(properties.getProfile().getBaseDir()) : null;
        return baseDir != null ? Path.of(baseDir).toAbsolutePath().normalize() : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record ProfileMeta(String description, boolean descriptionAuto, String displayName) {
    }

    private record ModelSelection(String provider, String model, String baseUrl) {
    }

    public record CreateProfileRequest(
        String name,
        String cloneFrom,
        boolean cloneConfig,
        boolean cloneAll,
        boolean noSkills,
        String description,
        String provider,
        String model,
        String baseUrl
    ) {
    }
}
