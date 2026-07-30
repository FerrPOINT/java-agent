package com.azhukov.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * S19: Cron blueprint catalog — parameterized automation blueprints with typed slots.
 * <p>
 * Ported from Hermes' cron/blueprint_catalog.py. A blueprint is a one-place definition
 * of an automation with typed slots (schedule, prompt, skills, delivery target) that
 * users can instantiate. fill_blueprint validates user-supplied values and turns a
 * blueprint into a CronSuggestionService.JobSpec.
 * <p>
 * Users never type raw cron — a blueprint carries a fixed recurrence in
 * schedule_template and parameterizes only the human-friendly parts.
 */
@Service
@Slf4j
public class CronBlueprintService {

    private static final Map<String, String> WEEKDAY_PRESETS = Map.of(
        "everyday", "*",
        "weekdays", "1-5",
        "weekends", "0,6"
    );

    private static final List<BlueprintSlot> TIME_SLOT_DEFAULT_08 = List.of(
        new BlueprintSlot("time", "time", "What time?", "08:00", List.of(), false, true, "24h local time, e.g. 08:00")
    );

    private static final BlueprintSlot DELIVER_SLOT = new BlueprintSlot(
        "deliver", "enum", "Where to deliver?",
        "origin", List.of("origin", "local", "telegram", "discord", "email"),
        false, false, "origin = the chat you set this up from"
    );

    /**
     * The catalog of available blueprints.
     */
    public static final List<AutomationBlueprint> CATALOG = List.of(
        new AutomationBlueprint(
            "morning-brief", "Morning briefing",
            "A short daily briefing: today's calendar, weather, and anything urgent.",
            "daily",
            "{minute} {hour} * * *",
            "Produce a concise morning briefing for the user: today's calendar events, the local weather, and any urgent items. Keep it short and scannable.",
            List.of(
                new BlueprintSlot("time", "time", "What time?", "08:00", List.of(), false, true, "24h local time"),
                DELIVER_SLOT
            ),
            "origin", List.of(), List.of("daily", "briefing")
        ),
        new AutomationBlueprint(
            "important-mail", "Important-mail monitor",
            "Check your inbox periodically and ping you ONLY about mail that actually needs attention.",
            "email",
            "*/{interval_min} * * * *",
            "Check the user's inbox for new messages since the last run. Surface ONLY mail matching: {criteria}. If nothing does, respond with [SILENT].",
            List.of(
                new BlueprintSlot("interval_min", "enum", "How often?", "30", List.of("15", "30", "60"), false, true, "minutes between checks"),
                new BlueprintSlot("criteria", "text", "Only notify me if the mail…", "needs a reply today, is from my manager or family, or mentions a deadline", List.of(), false, true, ""),
                DELIVER_SLOT
            ),
            "origin", List.of(), List.of("email", "monitor")
        ),
        new AutomationBlueprint(
            "weekly-review", "Weekly review",
            "A weekly recap: what got done, what's still open, and what's coming up.",
            "weekly",
            "{minute} {hour} * * {dow}",
            "Produce a weekly review for the user: what was accomplished this week, still-open items, and next week's calendar. Keep it tight.",
            List.of(
                new BlueprintSlot("time", "time", "What time?", "18:00", List.of(), false, true, "24h local time"),
                new BlueprintSlot("day", "enum", "Which day?", "sunday", List.of("sunday", "monday", "friday", "saturday"), false, true, ""),
                DELIVER_SLOT
            ),
            "origin", List.of(), List.of("weekly", "review")
        ),
        new AutomationBlueprint(
            "workday-start", "Workday start reminder",
            "A weekday nudge with your agenda and top priorities.",
            "daily",
            "{minute} {hour} * * 1-5",
            "Give the user a brief weekday start-of-day nudge: today's calendar and the 1-3 highest-priority things to focus on.",
            List.of(
                new BlueprintSlot("time", "time", "What time?", "09:00", List.of(), false, true, "24h local time"),
                DELIVER_SLOT
            ),
            "origin", List.of(), List.of("daily", "focus")
        ),
        new AutomationBlueprint(
            "custom-reminder", "Custom reminder",
            "A recurring reminder in your own words, on your schedule.",
            "general",
            "{minute} {hour} * * {dow}",
            "Remind the user: {what}",
            List.of(
                new BlueprintSlot("what", "text", "Remind me to…", "take a break and stretch", List.of(), false, true, ""),
                new BlueprintSlot("time", "time", "What time?", "14:00", List.of(), false, true, "24h local time"),
                new BlueprintSlot("recurrence", "weekdays", "Repeat on", "everyday", List.of("everyday", "weekdays", "weekends"), false, true, ""),
                DELIVER_SLOT
            ),
            "origin", List.of(), List.of("reminder")
        ),
        new AutomationBlueprint(
            "evening-winddown", "Evening wind-down",
            "An end-of-day check-in: tomorrow's calendar at a glance and anything you should prep tonight.",
            "daily",
            "{minute} {hour} * * *",
            "Give the user a short evening wind-down: tomorrow's calendar, any early commitments to prep for, and one gentle nudge to wrap up loose ends.",
            List.of(
                new BlueprintSlot("time", "time", "What time?", "21:00", List.of(), false, true, "24h local time"),
                DELIVER_SLOT
            ),
            "origin", List.of(), List.of("daily", "evening")
        ),
        new AutomationBlueprint(
            "news-digest", "Topic news digest",
            "A recurring digest on a topic you care about — deduped against what was already sent.",
            "general",
            "{minute} {hour} * * {dow}",
            "Search the web for new and noteworthy items about: {topic}. Dedupe against previous runs. Deliver at most {count} bullets.",
            List.of(
                new BlueprintSlot("topic", "text", "What topic?", "AI and technology", List.of(), false, true, ""),
                new BlueprintSlot("time", "time", "What time?", "18:00", List.of(), false, true, "24h local time"),
                new BlueprintSlot("recurrence", "weekdays", "Repeat on", "weekdays", List.of("everyday", "weekdays", "weekends"), false, true, ""),
                new BlueprintSlot("count", "enum", "How many bullets?", "5", List.of("3", "5", "8"), false, true, ""),
                DELIVER_SLOT
            ),
            "origin", List.of(), List.of("digest", "research")
        ),
        new AutomationBlueprint(
            "habit-checkin", "Habit check-in",
            "A recurring nudge to keep a habit on track and reflect on whether you did it.",
            "general",
            "{minute} {hour} * * {dow}",
            "Nudge the user about their habit: {habit}. Ask whether they did it today, keep it warm and non-judgmental.",
            List.of(
                new BlueprintSlot("habit", "text", "Which habit?", "20 minutes of reading", List.of(), false, true, ""),
                new BlueprintSlot("time", "time", "What time?", "20:00", List.of(), false, true, "24h local time"),
                new BlueprintSlot("recurrence", "weekdays", "Repeat on", "everyday", List.of("everyday", "weekdays", "weekends"), false, true, ""),
                DELIVER_SLOT
            ),
            "origin", List.of(), List.of("habit", "wellbeing")
        ),
        new AutomationBlueprint(
            "learn-daily", "Daily learning drip",
            "One bite-sized lesson a day on a topic you want to learn, building progressively over time.",
            "daily",
            "{minute} {hour} * * {dow}",
            "Teach the user one bite-sized lesson about: {topic}. Build on earlier lessons so it progresses. End with a single question to check understanding.",
            List.of(
                new BlueprintSlot("topic", "text", "Learn about…", "Spanish vocabulary", List.of(), false, true, ""),
                new BlueprintSlot("time", "time", "What time?", "08:30", List.of(), false, true, "24h local time"),
                new BlueprintSlot("recurrence", "weekdays", "Repeat on", "weekdays", List.of("everyday", "weekdays", "weekends"), false, true, ""),
                DELIVER_SLOT
            ),
            "origin", List.of(), List.of("learning", "daily")
        )
    );

    private static final Map<String, AutomationBlueprint> CATALOG_BY_KEY = new HashMap<>();
    static {
        for (AutomationBlueprint bp : CATALOG) {
            CATALOG_BY_KEY.put(bp.key(), bp);
        }
    }

    /**
     * Get a blueprint by key.
     */
    public Optional<AutomationBlueprint> getBlueprint(String key) {
        return Optional.ofNullable(CATALOG_BY_KEY.get(key));
    }

    /**
     * List all available blueprints.
     */
    public List<AutomationBlueprint> listBlueprints() {
        return CATALOG;
    }

    /**
     * Emit the form schema for a blueprint (for UI rendering).
     */
    public Map<String, Object> formSchema(AutomationBlueprint blueprint) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (BlueprintSlot slot : blueprint.slots()) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", slot.name());
            field.put("type", slot.type());
            field.put("label", slot.label());
            field.put("default", slot.defaultValue());
            field.put("options", slot.options());
            field.put("optional", slot.optional());
            field.put("help", slot.help());
            fields.add(field);
        }
        return Map.of(
            "key", blueprint.key(),
            "title", blueprint.title(),
            "description", blueprint.description(),
            "category", blueprint.category(),
            "tags", blueprint.tags(),
            "fields", fields
        );
    }

    /**
     * Validate user-supplied slot values and fill the blueprint into a JobSpec.
     *
     * @param blueprint the blueprint to fill
     * @param values    user-supplied slot values (slot name → value)
     * @return the filled JobSpec ready for cron job creation
     * @throws BlueprintFillException if validation fails
     */
    public CronSuggestionService.JobSpec fillBlueprint(AutomationBlueprint blueprint, Map<String, String> values)
            throws BlueprintFillException {
        if (values == null) {
            values = Map.of();
        }
        // Validate and fill slots
        Map<String, String> resolved = new HashMap<>();
        for (BlueprintSlot slot : blueprint.slots()) {
            String value = values.get(slot.name());
            if (value == null || value.isBlank()) {
                value = slot.defaultValue();
            }
            if (value == null || value.isBlank()) {
                if (!slot.optional()) {
                    throw new BlueprintFillException("Missing required slot: " + slot.name());
                }
                continue;
            }
            // Validate enum slots
            if ("enum".equals(slot.type()) && !slot.options().isEmpty() && slot.strict()) {
                if (!slot.options().contains(value)) {
                    throw new BlueprintFillException("Invalid value for slot '" + slot.name()
                        + "': " + value + ". Allowed: " + slot.options());
                }
            }
            // Validate time slot
            if ("time".equals(slot.type())) {
                if (!value.matches("^\\d{2}:\\d{2}$")) {
                    throw new BlueprintFillException("Invalid time format for slot '" + slot.name()
                        + "': " + value + ". Use HH:MM (24h).");
                }
            }
            resolved.put(slot.name(), value);
        }

        // Build schedule from template
        String schedule = blueprint.scheduleTemplate();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            schedule = schedule.replace(placeholder, resolveScheduleValue(entry.getKey(), entry.getValue()));
        }

        // Build prompt from template
        String prompt = blueprint.promptTemplate();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            prompt = prompt.replace(placeholder, entry.getValue());
        }

        // Determine deliver target
        String deliver = resolved.getOrDefault("deliver", blueprint.deliverDefault());

        // Build job name from blueprint key
        String name = blueprint.key() + "-" + System.currentTimeMillis() % 10000;

        return new CronSuggestionService.JobSpec(name, schedule, prompt, deliver,
            blueprint.skills().isEmpty() ? null : String.join(",", blueprint.skills()));
    }

    private static final Map<String, Integer> DAY_TO_CRON = Map.of(
        "sunday", 0, "monday", 1, "tuesday", 2, "wednesday", 3,
        "thursday", 4, "friday", 5, "saturday", 6
    );

    private static String dayToCron(String day) {
        Integer cron = DAY_TO_CRON.get(day.toLowerCase());
        return cron != null ? String.valueOf(cron) : day;
    }

    /**
     * Resolve a slot value into its schedule representation.
     * time → minute and hour fields, weekdays → dow field.
     */
    private String resolveScheduleValue(String slotName, String value) {
        if ("time".equals(slotName)) {
            // time="08:00" → minute=0, hour=8
            String[] parts = value.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            // Replace {hour} and {minute} in schedule
            // This is handled by the template replacement, but time is a single slot
            // that provides both hour and minute. We need special handling.
            return value; // The template has {minute} and {hour} separately
        }
        if ("weekdays".equals(slotName)) {
            String cronValue = WEEKDAY_PRESETS.get(value);
            return cronValue != null ? cronValue : value;
        }
        return value;
    }

    /**
     * Fill a blueprint with special handling for time slots that map to minute/hour.
     */
    public CronSuggestionService.JobSpec fillBlueprintWithTime(AutomationBlueprint blueprint,
                                                                 Map<String, String> values)
            throws BlueprintFillException {
        if (values == null) {
            values = Map.of();
        }
        // Validate and fill slots
        Map<String, String> resolved = new HashMap<>();
        for (BlueprintSlot slot : blueprint.slots()) {
            String value = values.get(slot.name());
            if (value == null || value.isBlank()) {
                value = slot.defaultValue();
            }
            if (value == null || value.isBlank()) {
                if (!slot.optional()) {
                    throw new BlueprintFillException("Missing required slot: " + slot.name());
                }
                continue;
            }
            // Validate enum slots
            if ("enum".equals(slot.type()) && !slot.options().isEmpty() && slot.strict()) {
                if (!slot.options().contains(value)) {
                    throw new BlueprintFillException("Invalid value for slot '" + slot.name()
                        + "': " + value + ". Allowed: " + slot.options());
                }
            }
            // Validate time slot
            if ("time".equals(slot.type())) {
                if (!value.matches("^\\d{2}:\\d{2}$")) {
                    throw new BlueprintFillException("Invalid time format for slot '" + slot.name()
                        + "': " + value + ". Use HH:MM (24h).");
                }
            }
            // Validate weekdays slot
            if ("weekdays".equals(slot.type()) && !slot.options().isEmpty()) {
                if (!slot.options().contains(value)) {
                    throw new BlueprintFillException("Invalid value for slot '" + slot.name()
                        + "': " + value + ". Allowed: " + slot.options());
                }
            }
            resolved.put(slot.name(), value);
        }

        // Build schedule from template — handle time slot specially
        String schedule = blueprint.scheduleTemplate();
        String timeValue = resolved.get("time");
        if (timeValue != null) {
            String[] parts = timeValue.split(":");
            String hour = String.valueOf(Integer.parseInt(parts[0]));
            String minute = String.valueOf(Integer.parseInt(parts[1]));
            schedule = schedule.replace("{hour}", hour).replace("{minute}", minute);
        }
        // Replace other placeholders
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            if (entry.getKey().equals("time")) continue; // already handled
            String value = entry.getValue();

            // Find the matching slot to check its type
            String slotType = blueprint.slots().stream()
                .filter(s -> s.name().equals(entry.getKey()))
                .map(BlueprintSlot::type)
                .findFirst().orElse(null);

            if ("weekdays".equals(slotType)) {
                // Weekdays-type slot (e.g. "recurrence") maps to {dow} in the template
                String cronValue = WEEKDAY_PRESETS.get(value);
                value = cronValue != null ? cronValue : value;
                schedule = schedule.replace("{dow}", value);
            } else if ("enum".equals(slotType) && "day".equals(entry.getKey())) {
                // Day-of-week enum (e.g. "friday") → cron number
                value = dayToCron(value);
                schedule = schedule.replace("{dow}", value);
            } else {
                String placeholder = "{" + entry.getKey() + "}";
                schedule = schedule.replace(placeholder, value);
            }
        }

        // Build prompt from template
        String prompt = blueprint.promptTemplate();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            prompt = prompt.replace(placeholder, entry.getValue());
        }

        // Determine deliver target
        String deliver = resolved.getOrDefault("deliver", blueprint.deliverDefault());

        // Build job name from blueprint key
        String name = blueprint.key() + "-" + System.currentTimeMillis() % 10000;

        return new CronSuggestionService.JobSpec(name, schedule, prompt, deliver,
            blueprint.skills().isEmpty() ? null : String.join(",", blueprint.skills()));
    }

    // ── Records ────────────────────────────────────────────────────────

    /**
     * A single fillable field on a blueprint.
     */
    public record BlueprintSlot(
        String name,
        String type,
        String label,
        String defaultValue,
        List<String> options,
        boolean optional,
        boolean strict,
        String help
    ) {}

    /**
     * A parameterized automation blueprint.
     */
    public record AutomationBlueprint(
        String key,
        String title,
        String description,
        String category,
        String scheduleTemplate,
        String promptTemplate,
        List<BlueprintSlot> slots,
        String deliverDefault,
        List<String> skills,
        List<String> tags
    ) {}

    /**
     * Raised when supplied slot values fail validation.
     */
    public static class BlueprintFillException extends RuntimeException {
        public BlueprintFillException(String message) {
            super(message);
        }
    }
}