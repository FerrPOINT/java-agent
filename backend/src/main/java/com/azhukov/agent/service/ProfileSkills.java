package com.azhukov.agent.service;

import com.azhukov.agent.core.security.SecureFileAccess;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.SkillSecurityScanner;
import com.azhukov.agent.core.skill.SkillUtils;
import com.azhukov.agent.core.skill.TrustLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The skill view of a single profile. Base artifacts take precedence and have no write path. */
@Service
@RequiredArgsConstructor
public class ProfileSkills {
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Set<String> SUPPORT_AREAS = Set.of("references", "templates", "scripts", "assets");

    private final ProfileService profiles;
    private final ProfileInstructions instructions;

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public View forProfile(String profile) {
        try {
            var config = profiles.readExecutionConfig(profile);
            return new View(profiles.profilePath(profile).resolve("skills"), instructions.loadBase(config.get("instructions")),
                ProfileInstructions.disabledSkills(config));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to load skills for the selected profile");
        }
    }

    public List<SkillManager.SkillInfo> list(String profile, ProfileInstructions.Snapshot instructionsSnapshot) {
        return new View(profiles.profilePath(profile).resolve("skills"), instructionsSnapshot.base(), instructionsSnapshot.disabledSkills()).listSkills();
    }

    public void setEnabled(String profile, String name, boolean enabled) throws IOException {
        requireName(name);
        var config = new LinkedHashMap<>(profiles.readExecutionConfig(profile));
        var settings = new LinkedHashMap<String, Object>();
        if (config.get("skills") instanceof Map<?, ?> section) {
            section.forEach((key, value) -> settings.put(String.valueOf(key), value));
        }
        var disabled = new java.util.TreeSet<>(ProfileInstructions.disabledSkills(config));
        if (enabled) {
            disabled.remove(name);
        } else {
            disabled.add(name);
        }
        settings.put("disabled", List.copyOf(disabled));
        config.put("skills", settings);
        profiles.writeConfig(profile, config);
    }

    public record Entry(SkillManager.SkillInfo info, Path path) {}

    public static final class View implements SkillManager {
        private final Path root;
        private final ProfileInstructions.BasePackage base;
        private final Set<String> disabled;

        private View(Path root, ProfileInstructions.BasePackage base, Set<String> disabled) {
            this.root = root;
            this.base = base;
            this.disabled = disabled;
        }

        @Override
        public List<String> listSkillNames() {
            return listSkills().stream().map(SkillInfo::name).toList();
        }

        @Override
        public List<SkillInfo> listSkills() {
            var result = new LinkedHashMap<String, SkillInfo>();
            for (var entry : locals().values()) {
                result.put(entry.info().name(), entry.info());
            }
            for (String name : base.skills().keySet().stream().sorted().toList()) {
                result.put(name, shared(name));
            }
            return List.copyOf(result.values());
        }

        @Override
        public SkillInfo getSkillInfo(String name) {
            Entry entry = find(name);
            return entry == null ? null : entry.info();
        }

        public Entry find(String name) {
            requireName(name);
            if (base.skills().containsKey(name)) {
                return new Entry(shared(name), base.root().resolve("skills").resolve(name).resolve("SKILL.md"));
            }
            return locals().get(name);
        }

        public Path create(String name, String content) {
            requireWritable(name);
            if (locals().containsKey(name)) {
                throw new IllegalArgumentException("Skill already exists in the selected profile");
            }
            Path path = root.resolve(name).resolve("SKILL.md");
            save(name, content, path, true);
            return path;
        }

        public Path update(String name, String content) {
            requireWritable(name);
            Path path = requireLocal(name).path();
            save(name, content, path, false);
            return path;
        }

        @Override
        public String getSkill(String name) {
            SkillInfo info = getSkillInfo(name);
            return info == null ? null : info.content();
        }

        @Override
        public void saveSkill(String name, String content) {
            requireWritable(name);
            Entry existing = locals().get(name);
            Path file = existing == null ? root.resolve(name).resolve("SKILL.md") : existing.path();
            save(name, content, file, existing == null);
        }

        private static void save(String name, String content, Path path, boolean create) {
            if (content == null || content.isBlank()
                || !name.equals(SkillUtils.parseFrontmatter(content).frontmatter().get("name"))) {
                throw new IllegalArgumentException("SKILL.md frontmatter must declare the selected skill name");
            }
            scan(name, content);
            if (!create) {
                write(path, content);
                return;
            }
            try (var dir = SecureFileAccess.openOrCreateDirectory(path.getParent())) {
                dir.createFile(path.getFileName(), content.getBytes(StandardCharsets.UTF_8));
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalArgumentException("Skill already exists in the selected profile");
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create a skill in the selected profile");
            }
        }

        @Override
        public boolean deleteSkill(String name) {
            requireWritable(name);
            Entry existing = locals().get(name);
            return existing != null && remove(existing.path());
        }

        @Override
        public String readSupportFile(String name, String relative) {
            requireName(name);
            Path safe = supportPath(relative);
            // The pinned role manifest declares hashes of SKILL.md entrypoints.
            if (base.skills().containsKey(name)) {
                return null;
            }
            Entry entry = locals().get(name);
            if (entry == null || entry.info().disabled()) {
                return null;
            }
            return read(entry.path().getParent().resolve(safe));
        }

        @Override
        public List<String> listSupportFiles(String name) {
            requireName(name);
            if (base.skills().containsKey(name)) {
                return List.of();
            }
            Entry entry = locals().get(name);
            if (entry == null || entry.info().disabled()) {
                return List.of();
            }
            var files = new java.util.ArrayList<String>();
            try (var dir = SecureFileAccess.openDirectory(entry.path().getParent())) {
                for (String area : SUPPORT_AREAS) {
                    Path path = Path.of(area);
                    try (var support = dir.openDirectory(path, false)) {
                        collectFiles(support, path, files);
                    } catch (NoSuchFileException missing) {
                        // An unused category has no directory.
                    }
                }
                return files.stream().sorted().toList();
            } catch (NoSuchFileException missing) {
                return List.of();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to list files in the selected profile");
            }
        }

        private static void collectFiles(SecureFileAccess.Directory dir, Path relative, List<String> files) throws IOException {
            for (Path entry : dir.entries()) {
                Path name = entry.getFileName();
                var attributes = dir.attributes(name);
                Path child = relative.resolve(name);
                if (attributes.isRegularFile()) {
                    files.add(child.toString());
                } else if (attributes.isDirectory()) {
                    try (var nested = dir.openDirectory(name, false)) {
                        collectFiles(nested, child, files);
                    }
                }
            }
        }

        @Override
        public void writeSupportFile(String name, String relative, String content) {
            requireWritable(name);
            Path path = requireLocal(name).path().getParent().resolve(supportPath(relative));
            scan(name, content);
            write(path, content);
        }

        @Override
        public boolean removeSupportFile(String name, String relative) {
            requireWritable(name);
            return remove(requireLocal(name).path().getParent().resolve(supportPath(relative)));
        }

        private SkillInfo shared(String name) {
            try {
                return info(name, base.readSkill(name), TrustLevel.BUILTIN.name());
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Unable to verify the pinned Base skill");
            }
        }

        private Map<String, Entry> locals() {
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (Path path : SkillUtils.iterSkillIndexFiles(root, "SKILL.md")) {
                if (SkillUtils.isExcludedSkillPath(path)) {
                    continue;
                }
                String content = read(path);
                if (content == null) {
                    continue;
                }
                var meta = SkillUtils.parseFrontmatter(content).frontmatter();
                String name = value(meta.get("name"));
                if (name.isEmpty()) {
                    name = path.getParent().getFileName().toString();
                }
                requireName(name);
                if (base.skills().containsKey(name)) {
                    continue;
                }
                if (entries.putIfAbsent(name, new Entry(info(name, content, "AGENT_CREATED"), path)) != null) {
                    throw new IllegalStateException("Ambiguous skill name in the selected profile");
                }
            }
            return entries;
        }

        private Entry requireLocal(String name) {
            Entry entry = locals().get(name);
            if (entry == null) {
                throw new IllegalArgumentException("Skill not found in the selected profile");
            }
            return entry;
        }

        private SkillInfo info(String name, String content, String trust) {
            Map<String, Object> meta = SkillUtils.parseFrontmatter(content).frontmatter();
            return new SkillInfo(name, content, value(meta.get("description")), value(meta.get("category")),
                null, 0, 0, null, false, trust, SkillUtils.parseTags(meta.get("tags")),
                SkillUtils.parseTags(meta.get("related_skills")),
                disabled.contains(name) || Boolean.parseBoolean(value(meta.get("disabled"))),
                null);
        }

        private void requireWritable(String name) {
            requireName(name);
            if (base.skills().containsKey(name)) {
                throw new IllegalArgumentException("Pinned Base skills are read-only");
            }
        }
    }

    private static Path supportPath(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("\\")) {
            throw new IllegalArgumentException("A relative skill support file path is required");
        }
        Path path = Path.of(raw);
        if (path.isAbsolute() || path.getNameCount() < 2 || !path.equals(path.normalize())
            || !SUPPORT_AREAS.contains(path.getName(0).toString())) {
            throw new IllegalArgumentException("Invalid skill support file path");
        }
        return path;
    }

    private static void requireName(String name) {
        if (!validName(name)) {
            throw new IllegalArgumentException("Invalid skill name");
        }
    }

    private static String read(Path path) {
        try {
            return SecureFileAccess.readUtf8(path);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read a file in the selected profile");
        }
    }

    private static void write(Path path, String content) {
        try (var dir = SecureFileAccess.openOrCreateDirectory(path.getParent())) {
            dir.replaceFile(path.getFileName(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write a file in the selected profile");
        }
    }

    private static boolean remove(Path path) {
        try (var dir = SecureFileAccess.openDirectory(path.getParent())) {
            dir.entries().deleteFile(path.getFileName());
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to delete a file in the selected profile");
        }
    }

    private static void scan(String name, String content) {
        String refusal = SkillSecurityScanner.scanAndGuard(name, content, TrustLevel.AGENT_CREATED);
        if (refusal != null) {
            throw new SecurityException(refusal);
        }
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
