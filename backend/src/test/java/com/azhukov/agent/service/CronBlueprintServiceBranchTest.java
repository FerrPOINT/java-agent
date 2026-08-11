package com.azhukov.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Branch-coverage tests for {@link CronBlueprintService} targeting:
 * - fillBlueprint (non-time variant) with null values, defaults, invalid enum, invalid time
 * - fillBlueprintWithTime with null values, optional slots, weekdays validation
 * - formSchema for different blueprints
 * - getBlueprint edge cases
 */
class CronBlueprintServiceBranchTest {

    private CronBlueprintService service;

    @BeforeEach
    void setUp() {
        service = new CronBlueprintService();
    }

    // ── fillBlueprint (non-time variant) with null values ──

    @Test
    void fillBlueprintWithNullValuesUsesDefaults() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        var spec = service.fillBlueprint(bp, null);

        assertThat(spec).isNotNull();
        // Default time=08:00, default deliver=origin
        assertThat(spec.deliverTo()).isEqualTo("origin");
    }

    // ── fillBlueprint with empty values uses defaults ──

    @Test
    void fillBlueprintWithBlankValuesUsesDefaults() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        var spec = service.fillBlueprint(bp, Map.of("time", "  ", "deliver", ""));

        assertThat(spec).isNotNull();
        // Blank values should fall through to defaults
        assertThat(spec.deliverTo()).isEqualTo("origin");
    }

    // ── fillBlueprint with invalid enum ──

    @Test
    void fillBlueprintInvalidEnumThrows() {
        var bp = service.getBlueprint("important-mail").orElseThrow();
        // interval_min is an enum slot with strict=true
        Throwable thrown = catchThrowable(() ->
            service.fillBlueprint(bp, Map.of("interval_min", "99", "criteria", "test")));
        assertThat(thrown).isInstanceOf(CronBlueprintService.BlueprintFillException.class);
        assertThat(thrown.getMessage()).contains("Invalid value for slot 'interval_min'");
    }

    // ── fillBlueprint with invalid time ──

    @Test
    void fillBlueprintInvalidTimeThrows() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        // "invalid" doesn't match HH:MM pattern
        Throwable thrown = catchThrowable(() ->
            service.fillBlueprint(bp, Map.of("time", "invalid")));
        assertThat(thrown).isInstanceOf(CronBlueprintService.BlueprintFillException.class);
        assertThat(thrown.getMessage()).contains("Invalid time format");
    }

    // ── fillBlueprint with missing required slot ──

    @Test
    void fillBlueprintMissingRequiredSlotThrows() {
        // Create a custom blueprint with a required slot that has no default
        var bp = new CronBlueprintService.AutomationBlueprint(
            "test", "Test", "desc", "general",
            "{minute} {hour} * * *", "Prompt: {what}",
            List.of(new CronBlueprintService.BlueprintSlot(
                "what", "text", "What?", null, // null default
                List.of(), false, true, "")),
            "origin", List.of(), List.of()
        );
        Throwable thrown = catchThrowable(() -> service.fillBlueprint(bp, Map.of()));
        assertThat(thrown).isInstanceOf(CronBlueprintService.BlueprintFillException.class);
        assertThat(thrown.getMessage()).contains("Missing required slot: what");
    }

    // ── fillBlueprint with optional slot missing (no default, optional=true) ──

    @Test
    void fillBlueprintOptionalSlotMissingIsSkipped() {
        var bp = new CronBlueprintService.AutomationBlueprint(
            "test-opt", "Test Optional", "desc", "general",
            "0 9 * * *", "Fixed prompt",
            List.of(new CronBlueprintService.BlueprintSlot(
                "optional_field", "text", "Optional?", null,
                List.of(), true, true, "")),
            "origin", List.of(), List.of()
        );
        var spec = service.fillBlueprint(bp, Map.of());
        assertThat(spec).isNotNull();
        assertThat(spec.prompt()).isEqualTo("Fixed prompt");
    }

    // ── fillBlueprintWithTime with null values ──

    @Test
    void fillBlueprintWithTimeNullValuesUsesDefaults() {
        var bp = service.getBlueprint("custom-reminder").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, null);

        assertThat(spec).isNotNull();
        // Should use defaults: what="take a break and stretch", time="14:00", recurrence="everyday"
        assertThat(spec.prompt()).contains("take a break and stretch");
    }

    // ── fillBlueprintWithTime with invalid weekdays ──

    @Test
    void fillBlueprintWithTimeInvalidWeekdaysThrows() {
        var bp = service.getBlueprint("custom-reminder").orElseThrow();
        Throwable thrown = catchThrowable(() ->
            service.fillBlueprintWithTime(bp,
                Map.of("what", "test", "time", "09:00", "recurrence", "invalid-weekday")));
        assertThat(thrown).isInstanceOf(CronBlueprintService.BlueprintFillException.class);
        assertThat(thrown.getMessage()).contains("Invalid value for slot 'recurrence'");
    }

    // ── fillBlueprintWithTime with blank slot value uses default ──

    @Test
    void fillBlueprintWithTimeBlankValueUsesDefault() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of("time", "  "));

        // Blank time should fall back to default "08:00"
        assertThat(spec.schedule()).isEqualTo("0 8 * * *");
    }

    // ── formSchema for weekly-review ──

    @Test
    void formSchemaForWeeklyReviewContainsDaySlot() {
        var bp = service.getBlueprint("weekly-review").orElseThrow();
        var schema = service.formSchema(bp);

        @SuppressWarnings("unchecked")
        var fields = (List<Map<String, Object>>) schema.get("fields");
        // Should have: time, day, deliver
        assertThat(fields).hasSize(3);
        assertThat(fields.get(1).get("name")).isEqualTo("day");
    }

    // ── formSchema contains all expected keys ──

    @Test
    void formSchemaContainsAllExpectedKeys() {
        var bp = service.getBlueprint("news-digest").orElseThrow();
        var schema = service.formSchema(bp);

        assertThat(schema).containsKeys("key", "title", "description", "category", "tags", "fields");
    }

    // ── getBlueprint returns empty for null key ──

    @Test
    void getBlueprintWithNullKeyReturnsEmpty() {
        Optional<CronBlueprintService.AutomationBlueprint> result = service.getBlueprint(null);
        assertThat(result).isEmpty();
    }

    // ── fillBlueprint resolves schedule value for time ──

    @Test
    void fillBlueprintResolvesTimeSlotSchedule() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        var spec = service.fillBlueprint(bp, Map.of("time", "07:30", "deliver", "telegram"));

        assertThat(spec).isNotNull();
        assertThat(spec.deliverTo()).isEqualTo("telegram");
    }

    // ── fillBlueprintWithTime with custom-reminder and non-standard recurrence ──

    @Test
    void fillBlueprintWithTimeCustomReminderWithNonStandardRecurrence() {
        var bp = service.getBlueprint("custom-reminder").orElseThrow();
        // "everyday" → "*" in dow
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("what", "test reminder", "time", "15:00", "recurrence", "everyday"));

        assertThat(spec.schedule()).isEqualTo("0 15 * * *");
        assertThat(spec.prompt()).contains("test reminder");
    }

    // ── fillBlueprintWithTime with news-digest and custom count ──

    @Test
    void fillBlueprintWithTimeNewsDigestWithCount8() {
        var bp = service.getBlueprint("news-digest").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("topic", "AI", "time", "12:00", "recurrence", "everyday", "count", "8"));

        assertThat(spec.schedule()).isEqualTo("0 12 * * *");
        assertThat(spec.prompt()).contains("8");
    }

    // ── fillBlueprintWithTime for workday-start (fixed 1-5 schedule) ──

    @Test
    void fillBlueprintWithTimeWorkdayStartHasFixedWeekdaySchedule() {
        var bp = service.getBlueprint("workday-start").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of("time", "09:30"));

        // Template: {minute} {hour} * * 1-5 → 30 9 * * 1-5
        assertThat(spec.schedule()).isEqualTo("30 9 * * 1-5");
    }

    // ── fillBlueprintWithTime for habit-checkin with weekdays ──

    @Test
    void fillBlueprintWithTimeHabitCheckinWeekdays() {
        var bp = service.getBlueprint("habit-checkin").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("habit", "exercise", "time", "07:00", "recurrence", "weekdays"));

        assertThat(spec.schedule()).isEqualTo("0 7 * * 1-5");
        assertThat(spec.prompt()).contains("exercise");
    }

    // ── fillBlueprintWithTime for learn-daily with weekends ──

    @Test
    void fillBlueprintWithTimeLearnDailyWeekends() {
        var bp = service.getBlueprint("learn-daily").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("topic", "Japanese", "time", "10:00", "recurrence", "weekends"));

        assertThat(spec.schedule()).isEqualTo("0 10 * * 0,6");
        assertThat(spec.prompt()).contains("Japanese");
    }

    // ── fillBlueprintWithTime with blank slot value for optional slot ──

    @Test
    void fillBlueprintWithTimeBlankOptionalSlotIsSkipped() {
        var bp = new CronBlueprintService.AutomationBlueprint(
            "test-opt2", "Test", "desc", "general",
            "0 9 * * *", "Prompt: {required}",
            List.of(
                new CronBlueprintService.BlueprintSlot(
                    "required", "text", "Required?", "default-val",
                    List.of(), false, true, ""),
                new CronBlueprintService.BlueprintSlot(
                    "optional", "text", "Optional?", null,
                    List.of(), true, true, "")
            ),
            "origin", List.of(), List.of()
        );
        var spec = service.fillBlueprintWithTime(bp, Map.of("optional", "  "));
        assertThat(spec).isNotNull();
        assertThat(spec.prompt()).contains("default-val");
    }

    // ── fillBlueprintWithTime with missing required slot ──

    @Test
    void fillBlueprintWithTimeMissingRequiredSlotThrows() {
        var bp = new CronBlueprintService.AutomationBlueprint(
            "test-req", "Test", "desc", "general",
            "0 9 * * *", "Prompt: {what}",
            List.of(new CronBlueprintService.BlueprintSlot(
                "what", "text", "What?", null,
                List.of(), false, true, "")),
            "origin", List.of(), List.of()
        );
        Throwable thrown = catchThrowable(() -> service.fillBlueprintWithTime(bp, Map.of()));
        assertThat(thrown).isInstanceOf(CronBlueprintService.BlueprintFillException.class);
        assertThat(thrown.getMessage()).contains("Missing required slot: what");
    }

    // ── fillBlueprintWithTime with skills (non-empty) ──

    @Test
    void fillBlueprintWithTimeWithSkillsReturnsCommaSeparated() {
        var bp = new CronBlueprintService.AutomationBlueprint(
            "test-skills", "Test", "desc", "general",
            "0 9 * * *", "Prompt",
            List.of(),
            "origin",
            List.of("skill1", "skill2"), // non-empty skills
            List.of()
        );
        var spec = service.fillBlueprintWithTime(bp, Map.of());
        assertThat(spec.skills()).isEqualTo("skill1,skill2");
    }

    // ── fillBlueprint with skills (non-empty) ──

    @Test
    void fillBlueprintWithSkillsReturnsCommaSeparated() {
        var bp = new CronBlueprintService.AutomationBlueprint(
            "test-skills2", "Test", "desc", "general",
            "0 9 * * *", "Prompt",
            List.of(),
            "origin",
            List.of("skill1", "skill2"),
            List.of()
        );
        var spec = service.fillBlueprint(bp, Map.of());
        assertThat(spec.skills()).isEqualTo("skill1,skill2");
    }

    // ── fillBlueprintWithTime with enum slot that has no options ──

    @Test
    void fillBlueprintWithTimeEnumSlotWithoutOptionsDoesNotValidate() {
        var bp = new CronBlueprintService.AutomationBlueprint(
            "test-enum-no-opts", "Test", "desc", "general",
            "0 9 * * *", "Prompt: {val}",
            List.of(new CronBlueprintService.BlueprintSlot(
                "val", "enum", "Value?", "default",
                List.of(), // empty options → no validation
                false, true, "")),
            "origin", List.of(), List.of()
        );
        // Should not throw since options is empty
        var spec = service.fillBlueprintWithTime(bp, Map.of("val", "anything"));
        assertThat(spec).isNotNull();
        assertThat(spec.prompt()).contains("anything");
    }

    // ── fillBlueprintWithTime with day="monday" ──

    @Test
    void fillBlueprintWithTimeWeeklyReviewMonday() {
        var bp = service.getBlueprint("weekly-review").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of("time", "09:00", "day", "monday"));

        // Monday = 1
        assertThat(spec.schedule()).isEqualTo("0 9 * * 1");
    }

    // ── fillBlueprintWithTime with day="saturday" ──

    @Test
    void fillBlueprintWithTimeWeeklyReviewSaturday() {
        var bp = service.getBlueprint("weekly-review").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of("time", "10:00", "day", "saturday"));

        // Saturday = 6
        assertThat(spec.schedule()).isEqualTo("0 10 * * 6");
    }

    // ── fillBlueprintWithTime with day="unknown" (not in DAY_TO_CRON) ──

    @Test
    void fillBlueprintWithTimeWeeklyReviewWednesdayThrows() {
        var bp = service.getBlueprint("weekly-review").orElseThrow();
        // wednesday is NOT in the allowed options [sunday, monday, friday, saturday]
        Throwable thrown = catchThrowable(() ->
            service.fillBlueprintWithTime(bp, Map.of("time", "10:00", "day", "wednesday")));
        assertThat(thrown).isInstanceOf(CronBlueprintService.BlueprintFillException.class);
    }
}