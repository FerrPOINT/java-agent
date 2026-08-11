package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S3: Full JSON Schema definitions for the review agent's tool whitelist.
 * <p>
 * Previously, the review tools had empty parameter schemas ({@code Map.of()}),
 * so the model couldn't reliably call them. This class provides complete
 * JSON Schema parameter definitions for each whitelisted tool, mirroring
 * the original Python reference's {@code get_tool_definitions} output.
 */
public final class ReviewToolSchemas {

 private ReviewToolSchemas() {}

 /**
 * Build the full list of review tool definitions with proper JSON Schema parameters.
 */
 public static List<ToolDefinition> build() {
 return List.of(
 memoryTool(),
 skillManageTool(),
 skillsListTool(),
 skillViewTool()
 );
 }

 /**
 * Memory tool — save durable information to persistent memory.
 */
 static ToolDefinition memoryTool() {
 Map<String, Object> schema = objectSchema(
 List.of("action"),
 Map.of(
 "action", stringEnum("Action: add, replace, remove, or read", List.of("add", "replace", "remove", "read")),
 "target", stringEnum("Target store: memory or user", List.of("memory", "user")),
 "content", stringDesc("Content to store (for add/replace)"),
 "old_text", stringDesc("Text to find and replace/remove (for replace/remove)"),
 "limit", integerDesc("Max results for read", 1, 100)
 )
 );
 return new ToolDefinition("memory",
 "Save durable information to persistent memory for the user. " +
 "Use this tool when you learn something worth remembering long-term — " +
 "user preferences, environment facts, corrections, or conventions.",
 schema);
 }

 /**
 * Skill manage tool — create, update, delete, patch, or manage support files.
 */
 static ToolDefinition skillManageTool() {
 Map<String, Object> schema = objectSchema(
 List.of("action", "name"),
 Map.of(
 "action", stringEnum("Action: create, update, delete, patch, write_file, remove_file",
 List.of("create", "update", "delete", "patch", "write_file", "remove_file")),
 "name", stringDesc("Skill name (lowercase, hyphens, no spaces)"),
 "content", stringDesc("Skill markdown content (required for create/update)"),
 "old_text", stringDesc("Text to find and replace (for patch action)"),
 "new_text", stringDesc("Replacement text (for patch action)"),
 "file_path", stringDesc("File path under references/, templates/, or scripts/ (for write_file/remove_file)")
 )
 );
 return new ToolDefinition("skill_manage",
 "Create, update, delete, patch a skill, or manage support files " +
 "(references/, templates/, scripts/).",
 schema);
 }

 /**
 * Skills list tool — list available skills.
 */
 static ToolDefinition skillsListTool() {
 Map<String, Object> schema = objectSchema(List.of(), Map.of());
 return new ToolDefinition("skills_list",
 "List available skills with name, category, and trust level.",
 schema);
 }

 /**
 * Skill view tool — read a skill by name.
 */
 static ToolDefinition skillViewTool() {
 Map<String, Object> schema = objectSchema(
 List.of("name"),
 Map.of("name", stringDesc("Skill name to read"))
 );
 return new ToolDefinition("skill_view",
 "Read a skill by name. Returns content with metadata, YAML frontmatter, " +
 "and linked support files.",
 schema);
 }

 // ── JSON Schema builders ──────────────────────────────────────────

 private static Map<String, Object> objectSchema(List<String> required, Map<String, Object> properties) {
 Map<String, Object> schema = new LinkedHashMap<>();
 schema.put("type", "object");
 if (!required.isEmpty()) {
 schema.put("required", required);
 }
 schema.put("properties", properties);
 return schema;
 }

 private static Map<String, Object> stringDesc(String description) {
 return Map.of("type", "string", "description", description);
 }

 private static Map<String, Object> stringEnum(String description, List<String> values) {
 return Map.of("type", "string", "description", description, "enum", values);
 }

 private static Map<String, Object> integerDesc(String description, int min, int max) {
 Map<String, Object> prop = new LinkedHashMap<>();
 prop.put("type", "integer");
 prop.put("description", description);
 prop.put("minimum", min);
 prop.put("maximum", max);
 return prop;
 }
}