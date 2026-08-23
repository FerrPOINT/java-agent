package com.azhukov.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests for {@link CronBlueprintService}.
 */
class CronBlueprintServiceTest {

    private CronBlueprintService service;

    @BeforeEach
    void setUp() {
        service = new CronBlueprintService();
    }

    @Test
    void catalogIsNotEmpty() {
        assertThat(service.listBlueprints()).isNotEmpty();
    }

    @Test
    void getBlueprint_existingKey_returnsBlueprint() {
        Optional<CronBlueprintService.AutomationBlueprint> bp = service.getBlueprint("morning-brief");
        assertThat(bp).isPresent();
        assertThat(bp.get().title()).isEqualTo("Morning briefing");
    }

    @Test
    void getBlueprint_unknownKey_returnsEmpty() {
        assertThat(service.getBlueprint("nonexistent")).isEmpty();
    }

    @Test
    void formSchema_containsAllFields() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        var schema = service.formSchema(bp);

        assertThat(schema).containsKey("key");
        assertThat(schema).containsKey("title");
        assertThat(schema).containsKey("fields");
        @SuppressWarnings("unchecked")
        var fields = (List<Map<String, Object>>) schema.get("fields");
        assertThat(fields).hasSize(2); // time + deliver
        assertThat(fields.get(0).get("name")).isEqualTo("time");
        assertThat(fields.get(1).get("name")).isEqualTo("deliver");
    }

    @Test
    void fillBlueprintWithTime_morningBrief_withDefaults() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of());

        assertThat(spec).isNotNull();
        // Default time is 08:00 → minute=0, hour=8
        assertThat(spec.schedule()).isEqualTo("0 8 * * *");
        assertThat(spec.deliverTo()).isEqualTo("origin");
        assertThat(spec.prompt()).contains("morning briefing");
    }

    @Test
    void fillBlueprintWithTime_morningBrief_customTime() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of("time", "14:30"));

        assertThat(spec.schedule()).isEqualTo("30 14 * * *");
    }

    @Test
    void fillBlueprintWithTime_morningBrief_customDeliver() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of("deliver", "telegram"));

        assertThat(spec.deliverTo()).isEqualTo("telegram");
    }

    @Test
    void fillBlueprintWithTime_weeklyReview_resolvesWeekday() {
        var bp = service.getBlueprint("weekly-review").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of("time", "18:00", "day", "friday"));

        // Friday = 5 in cron
        assertThat(spec.schedule()).isEqualTo("0 18 * * 5");
    }

    @Test
    void fillBlueprintWithTime_customReminder_withWeekdays() {
        var bp = service.getBlueprint("custom-reminder").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("what", "call mom", "time", "10:00", "recurrence", "weekdays"));

        // weekdays → 1-5
        assertThat(spec.schedule()).isEqualTo("0 10 * * 1-5");
        assertThat(spec.prompt()).contains("call mom");
    }

    @Test
    void fillBlueprintWithTime_customReminder_everyday() {
        var bp = service.getBlueprint("custom-reminder").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("what", "stand up", "time", "09:00", "recurrence", "everyday"));

        // everyday → *
        assertThat(spec.schedule()).isEqualTo("0 9 * * *");
    }

    @Test
    void fillBlueprintWithTime_customReminder_weekends() {
        var bp = service.getBlueprint("custom-reminder").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("what", "relax", "time", "11:00", "recurrence", "weekends"));

        // weekends → 0,6
        assertThat(spec.schedule()).isEqualTo("0 11 * * 0,6");
    }

    @Test
    void fillBlueprintWithTime_invalidTime_throws() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        Throwable thrown = catchThrowable(() -> service.fillBlueprintWithTime(bp, Map.of("time", "invalid")));
        assertThat(thrown).isInstanceOf(CronBlueprintService.BlueprintFillException.class);
    }

    @Test
    void fillBlueprintWithTime_invalidEnum_throws() {
        var bp = service.getBlueprint("weekly-review").orElseThrow();
        Throwable thrown = catchThrowable(() ->
            service.fillBlueprintWithTime(bp, Map.of("time", "18:00", "day", "invalid-day")));
        assertThat(thrown).isInstanceOf(CronBlueprintService.BlueprintFillException.class);
    }

    @Test
    void fillBlueprintWithTime_newsDigest_substitutesAllSlots() {
        var bp = service.getBlueprint("news-digest").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("topic", "quantum computing", "time", "12:00", "recurrence", "weekdays", "count", "3"));

        assertThat(spec.schedule()).isEqualTo("0 12 * * 1-5");
        assertThat(spec.prompt()).contains("quantum computing");
        assertThat(spec.prompt()).contains("3");
    }

    @Test
    void fillBlueprintWithTime_importantMail_substitutesIntervalAndCriteria() {
        var bp = service.getBlueprint("important-mail").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("interval_min", "15", "criteria", "from my boss", "deliver", "telegram"));

        // schedule: */{interval_min} * * * * → */15 * * * *
        assertThat(spec.schedule()).isEqualTo("*/15 * * * *");
        assertThat(spec.prompt()).contains("from my boss");
        assertThat(spec.deliverTo()).isEqualTo("telegram");
    }

    @Test
    void fillBlueprintWithTime_habitCheckin_substitutesHabitAndRecurrence() {
        var bp = service.getBlueprint("habit-checkin").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("habit", "drink water", "time", "10:00", "recurrence", "everyday"));

        assertThat(spec.schedule()).isEqualTo("0 10 * * *");
        assertThat(spec.prompt()).contains("drink water");
    }

    @Test
    void fillBlueprintWithTime_learnDaily_substitutesTopic() {
        var bp = service.getBlueprint("learn-daily").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp,
            Map.of("topic", "Rust programming", "time", "07:30", "recurrence", "weekdays"));

        assertThat(spec.schedule()).isEqualTo("30 7 * * 1-5");
        assertThat(spec.prompt()).contains("Rust programming");
    }

    @Test
    void fillBlueprintWithTime_workdayStart_fixedWeekdaySchedule() {
        var bp = service.getBlueprint("workday-start").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of("time", "09:00"));

        // Fixed: {minute} {hour} * * 1-5
        assertThat(spec.schedule()).isEqualTo("0 9 * * 1-5");
    }

    @Test
    void fillBlueprintWithTime_eveningWinddown_fixedDailySchedule() {
        var bp = service.getBlueprint("evening-winddown").orElseThrow();
        var spec = service.fillBlueprintWithTime(bp, Map.of("time", "21:00"));

        // Fixed: {minute} {hour} * * *
        assertThat(spec.schedule()).isEqualTo("0 21 * * *");
    }

    @Test
    void listBlueprints_containsAllExpectedKeys() {
        var keys = service.listBlueprints().stream()
            .map(CronBlueprintService.AutomationBlueprint::key).toList();
        assertThat(keys).contains(
            "morning-brief", "important-mail", "weekly-review",
            "workday-start", "custom-reminder", "evening-winddown",
            "news-digest", "habit-checkin", "learn-daily"
        );
    }

    @Test
    void blueprintHasTypedSlots() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        assertThat(bp.slots()).isNotEmpty();
        // First slot should be a time type
        assertThat(bp.slots().get(0).type()).isEqualTo("time");
    }

    @Test
    void blueprintHasScheduleTemplate() {
        var bp = service.getBlueprint("morning-brief").orElseThrow();
        assertThat(bp.scheduleTemplate()).contains("{minute}").contains("{hour}");
    }

    @Test
    void blueprintHasPromptTemplate() {
        var bp = service.getBlueprint("news-digest").orElseThrow();
        assertThat(bp.promptTemplate()).contains("{topic}").contains("{count}");
    }
}