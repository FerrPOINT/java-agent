package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.WriteOrigin;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
@Tag(name = "Hermes-compatible", description = "Dashboard learning graph compatibility")
public class LearningDashboardController {

    private final SkillManager skillManager;
    private final MemoryProvider memoryProvider;

    @GetMapping("/graph")
    public Map<String, Object> graph(@RequestParam(name = "profile", required = false) String profile) {
        List<SkillManager.SkillInfo> skills = skillManager.listSkills().stream()
            .filter(skill -> !skill.archived())
            .filter(skill -> !skill.disabled())
            .sorted(Comparator.comparing(SkillManager.SkillInfo::name))
            .toList();
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Integer> clusters = new LinkedHashMap<>();
        Set<String> skillNames = new HashSet<>();

        for (SkillManager.SkillInfo skill : skills) {
            skillNames.add(skill.name());
            String category = blank(skill.category()) ? "general" : skill.category();
            clusters.merge(category, 1, Integer::sum);
            nodes.add(skillNode(skill, category));
        }

        List<Map<String, Object>> memoryCards = memoryCards();
        int memoryCount = 0;
        for (Map<String, Object> card : memoryCards) {
            String source = String.valueOf(card.get("source"));
            nodes.add(memoryNode(card, source, memoryCount));
            memoryCount++;
        }
        if (memoryCount > 0) {
            clusters.put("memory", memoryCount);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        for (SkillManager.SkillInfo skill : skills) {
            for (String related : skill.relatedSkills() != null ? skill.relatedSkills() : List.<String>of()) {
                if (skillNames.contains(related) && !related.equals(skill.name())) {
                    edges.add(Map.of("source", skill.name(), "target", related));
                }
            }
        }
        edges.addAll(memorySkillEdges(memoryCards, skills));

        List<Map<String, Object>> clusterRows = clusters.entrySet().stream()
            .map(entry -> Map.<String, Object>of("category", entry.getKey(), "count", entry.getValue()))
            .toList();

        return Map.of(
            "nodes", nodes,
            "edges", edges,
            "clusters", clusterRows,
            "memory", memoryCards,
            "stats", Map.of(
                "nodes", skills.size(),
                "related_edges", edges.size(),
                "memory_nodes", memoryCards.size(),
                "memory_skill_edges", edges.stream()
                    .filter(edge -> String.valueOf(edge.get("source")).startsWith("memory:"))
                    .count(),
                "learned_skills", skills.size()
            )
        );
    }

    @GetMapping("/node")
    public ResponseEntity<Map<String, Object>> node(
        @RequestParam(name = "id") String id,
        @RequestParam(name = "profile", required = false) String profile
    ) {
        NodeRef ref = parseNodeRef(id);
        if (ref.kind() == NodeKind.MEMORY) {
            MemoryEntry entry = memoryEntry(ref);
            if (entry == null) {
                return notFound("memory node not found");
            }
            return ResponseEntity.ok(Map.of(
                "ok", true,
                "kind", "memory",
                "id", id,
                "label", title(entry.content()),
                "content", entry.content()
            ));
        }

        SkillManager.SkillLookupResult lookup = skillManager.getSkillInfoMultiStrategy(id);
        if (lookup.error() != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", lookup.error()));
        }
        SkillManager.SkillInfo info = lookup.info();
        if (info == null) {
            return notFound("skill '" + id + "' not found");
        }
        return ResponseEntity.ok(Map.of(
            "ok", true,
            "kind", "skill",
            "id", id,
            "label", id,
            "content", info.content() != null ? info.content() : ""
        ));
    }

    @DeleteMapping("/node")
    public ResponseEntity<Map<String, Object>> deleteNode(@RequestBody(required = false) LearningNodeRef body) {
        if (body == null || blank(body.id())) {
            return badRequest("id is required");
        }
        NodeRef ref = parseNodeRef(body.id());
        try {
            if (ref.kind() == NodeKind.MEMORY) {
                MemoryEntry entry = memoryEntry(ref);
                if (entry == null) {
                    return badRequest("memory node not found");
                }
                String error = memoryProvider.remove(AgentProperties.DEFAULT_USER_ID, ref.target(), entry.content());
                if (error != null) {
                    return badRequest(error);
                }
                return ResponseEntity.ok(Map.of("ok", true, "message", "deleted memory from " + memoryFileName(ref.target())));
            }
            boolean archived = skillManager.archiveSkill(body.id());
            if (!archived) {
                return badRequest("skill '" + body.id() + "' not found");
            }
            return ResponseEntity.ok(Map.of("ok", true, "message", "archived '" + body.id() + "'"));
        } catch (UnsupportedOperationException | IllegalStateException e) {
            return badRequest(e.getMessage());
        }
    }

    @PutMapping("/node")
    public ResponseEntity<Map<String, Object>> editNode(@RequestBody(required = false) LearningNodeEdit body) {
        if (body == null || blank(body.id())) {
            return badRequest("id is required");
        }
        if (body.content() == null) {
            return badRequest("content is required");
        }
        NodeRef ref = parseNodeRef(body.id());
        try {
            if (ref.kind() == NodeKind.MEMORY) {
                String content = body.content().trim();
                if (content.isBlank()) {
                    return badRequest("empty memory — use delete to remove it");
                }
                MemoryEntry entry = memoryEntry(ref);
                if (entry == null) {
                    return badRequest("memory node not found");
                }
                String error = memoryProvider.replace(AgentProperties.DEFAULT_USER_ID, ref.target(), entry.content(), content);
                if (error != null) {
                    return badRequest(error);
                }
                return ResponseEntity.ok(Map.of("ok", true, "message", "updated memory in " + memoryFileName(ref.target())));
            }

            if (skillManager.getSkillInfoMultiStrategy(body.id()).info() == null) {
                return badRequest("skill '" + body.id() + "' not found");
            }
            skillManager.saveSkill(body.id(), body.content(), WriteOrigin.USER);
            return ResponseEntity.ok(Map.of("ok", true, "message", "updated '" + body.id() + "'"));
        } catch (IllegalArgumentException | SecurityException | UnsupportedOperationException | IllegalStateException e) {
            return badRequest(e.getMessage());
        }
    }

    private Map<String, Object> skillNode(SkillManager.SkillInfo skill, String category) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", skill.name());
        node.put("label", skill.name());
        node.put("kind", "skill");
        node.put("timestamp", timestamp(skill.lastActivityAt() != null ? skill.lastActivityAt() : skill.updatedAt()));
        node.put("category", category);
        node.put("useCount", Math.max(0, skill.viewCount()) + Math.max(0, skill.manageCount()));
        node.put("state", skill.archived() ? "archived" : "active");
        node.put("createdBy", createdBy(skill.trustLevel()));
        node.put("pinned", false);
        return node;
    }

    private Map<String, Object> memoryNode(Map<String, Object> card, String source, int index) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "memory:" + source + ":" + index);
        node.put("label", card.getOrDefault("title", ""));
        node.put("kind", "memory");
        node.put("memorySource", source);
        node.put("timestamp", card.get("timestamp"));
        node.put("category", "memory");
        node.put("useCount", 0);
        node.put("state", "active");
        node.put("createdBy", "memory");
        node.put("pinned", false);
        return node;
    }

    private List<Map<String, Object>> memoryCards() {
        List<Map<String, Object>> cards = new ArrayList<>();
        appendMemoryCards(cards, "memory", "memory");
        appendMemoryCards(cards, "user", "profile");
        return cards;
    }

    private void appendMemoryCards(List<Map<String, Object>> cards, String target, String source) {
        List<String> entries = memoryProvider.getRawEntries(AgentProperties.DEFAULT_USER_ID, target);
        if (entries == null) {
            return;
        }
        long timestamp = Instant.now().getEpochSecond();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("source", source);
            card.put("timestamp", timestamp);
            card.put("title", title(entry));
            card.put("body", entry.length() > 1200 ? entry.substring(0, 1200) : entry);
            cards.add(card);
        }
    }

    private List<Map<String, Object>> memorySkillEdges(List<Map<String, Object>> memoryCards, List<SkillManager.SkillInfo> skills) {
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 0; i < memoryCards.size(); i++) {
            Map<String, Object> card = memoryCards.get(i);
            String source = String.valueOf(card.get("source"));
            Set<String> memoryTokens = tokens(card.get("title") + "\n" + card.get("body"));
            List<String> matches = skills.stream()
                .filter(skill -> !Collections.disjoint(tokens(skill.name()), memoryTokens)
                    || String.valueOf(card.get("body")).toLowerCase(Locale.ROOT).contains(skill.name().toLowerCase(Locale.ROOT)))
                .map(SkillManager.SkillInfo::name)
                .limit(4)
                .toList();
            for (String skill : matches) {
                edges.add(Map.of("source", "memory:" + source + ":" + i, "target", skill));
            }
        }
        return edges;
    }

    private MemoryEntry memoryEntry(NodeRef ref) {
        if (ref.kind() != NodeKind.MEMORY || ref.index() < 0) {
            return null;
        }
        List<String> entries = memoryProvider.getRawEntries(AgentProperties.DEFAULT_USER_ID, ref.target());
        if (entries == null || ref.localIndex() < 0 || ref.localIndex() >= entries.size()) {
            return null;
        }
        String content = entries.get(ref.localIndex());
        return content == null ? null : new MemoryEntry(ref.target(), content);
    }

    private NodeRef parseNodeRef(String id) {
        if (id != null && id.startsWith("memory:")) {
            String[] parts = id.split(":", 3);
            if (parts.length == 3 && ("memory".equals(parts[1]) || "profile".equals(parts[1]))) {
                try {
                    int index = Integer.parseInt(parts[2]);
                    if ("memory".equals(parts[1])) {
                        return new NodeRef(NodeKind.MEMORY, "memory", index, index);
                    }
                    int memoryCount = safeEntries("memory").size();
                    return new NodeRef(NodeKind.MEMORY, "user", index, index - memoryCount);
                } catch (NumberFormatException ignored) {
                    return new NodeRef(NodeKind.MEMORY, "memory", -1, -1);
                }
            }
            return new NodeRef(NodeKind.MEMORY, "memory", -1, -1);
        }
        return new NodeRef(NodeKind.SKILL, null, -1, -1);
    }

    private List<String> safeEntries(String target) {
        List<String> entries = memoryProvider.getRawEntries(AgentProperties.DEFAULT_USER_ID, target);
        return entries != null ? entries : List.of();
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null) {
            return tokens;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String title(String content) {
        String first = content == null ? "" : content.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse("");
        if (first.startsWith("#")) {
            first = first.replaceFirst("^#+\\s*", "");
        }
        return first.length() > 80 ? first.substring(0, 80) + "..." : first;
    }

    private static Long timestamp(Instant instant) {
        return instant != null ? instant.getEpochSecond() : null;
    }

    private static String createdBy(String trustLevel) {
        return "AGENT_CREATED".equalsIgnoreCase(trustLevel) ? "agent" : null;
    }

    private static String memoryFileName(String target) {
        return "user".equals(target) ? "USER.md" : "MEMORY.md";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "message", detail));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "message", detail));
    }

    private enum NodeKind {
        SKILL,
        MEMORY
    }

    private record NodeRef(NodeKind kind, String target, int index, int localIndex) {
    }

    private record MemoryEntry(String target, String content) {
    }

    private record LearningNodeRef(String id, String profile) {
    }

    private record LearningNodeEdit(String id, String content, String profile) {
    }
}
