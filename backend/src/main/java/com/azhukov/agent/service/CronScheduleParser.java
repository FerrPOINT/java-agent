package com.azhukov.agent.service;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Parses user-facing schedules and resolves the exact next execution time. */
@Component
public class CronScheduleParser {
    static final String KIND_ONCE = "once";
    static final String KIND_INTERVAL = "interval";
    static final String KIND_CRON = "cron";

    private static final Pattern ISO_TIMESTAMP_PATTERN = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern DURATION_PATTERN = Pattern.compile(
        "^(\\d+)\\s*([smhd])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVERY_PREFIX = Pattern.compile(
        "^every\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRON_FIELD_PATTERN = Pattern.compile("^[\\d*/,-]+$");
    // ScheduledThreadPoolExecutor caps delays at half the nanoTime range.
    // Round down to milliseconds, the unit used when registering the timer.
    private static final Duration MAX_TIMER_DELAY =
        Duration.ofMillis((Long.MAX_VALUE / 2) / 1_000_000);

    private final CronParser cronParser = new CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    public record ScheduleInfo(String kind, Instant nextRunAt) {}

    ScheduleInfo parse(String schedule) {
        return parse(schedule, Instant.now(), ZoneId.systemDefault());
    }

    ScheduleInfo parse(String schedule, Instant now, ZoneId zone) {
        if (schedule == null || schedule.isBlank()) {
            throw new IllegalArgumentException("Schedule must not be blank");
        }
        String trimmed = schedule.trim();
        Matcher everyMatcher = EVERY_PREFIX.matcher(trimmed);
        if (everyMatcher.matches()) {
            return durationSchedule(KIND_INTERVAL, everyMatcher.group(1), now);
        }
        if (ISO_TIMESTAMP_PATTERN.matcher(trimmed).matches()) {
            try {
                return oneShot(parseIsoTimestamp(trimmed, zone), now);
            } catch (DateTimeParseException ignored) {
                // Continue to the other schedule forms for a useful error below.
            }
        }
        try {
            Instant instant = Instant.parse(trimmed);
            return oneShot(instant, now);
        } catch (DateTimeParseException ignored) {
            // Not an offset timestamp.
        }
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(trimmed + "T00:00:00");
                return oneShot(dateTime.atZone(zone).toInstant(), now);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("Invalid schedule date: " + trimmed, invalid);
            }
        }
        if (DURATION_PATTERN.matcher(trimmed).matches()) {
            return durationSchedule(KIND_ONCE, trimmed, now);
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 5 && isCronExpression(parts)) {
            return new ScheduleInfo(KIND_CRON, nextCronExecution(trimmed, now.atZone(zone)));
        }
        throw new IllegalArgumentException("Invalid schedule: " + schedule);
    }

    private static ScheduleInfo oneShot(Instant due, Instant now) {
        if (!due.isAfter(now)) return new ScheduleInfo(KIND_ONCE, now.plusSeconds(1));
        checkTimerRange(now, due);
        return new ScheduleInfo(KIND_ONCE, due);
    }

    void validate(String schedule) {
        parse(schedule);
    }

    private boolean isCronExpression(String[] parts) {
        for (String part : parts) {
            if (!CRON_FIELD_PATTERN.matcher(part).matches()) return false;
        }
        return true;
    }

    private Instant parseIsoTimestamp(String value, ZoneId zone) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone).toInstant();
        }
    }

    private static ScheduleInfo durationSchedule(String kind, String value, Instant now) {
        Matcher matcher = DURATION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid duration: " + value);
        long amount = Long.parseLong(matcher.group(1));
        long unitSeconds = switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "s" -> 1;
            case "m" -> 60;
            case "h" -> 3600;
            case "d" -> 86400;
            default -> throw new IllegalArgumentException("Invalid duration unit: " + value);
        };
        Instant due;
        try {
            due = now.plusSeconds(Math.max(1, Math.multiplyExact(amount, unitSeconds)));
        } catch (ArithmeticException | DateTimeException overflow) {
            throw new IllegalArgumentException("Schedule duration is out of range: " + value, overflow);
        }
        checkTimerRange(now, due);
        return new ScheduleInfo(kind, due);
    }

    private static void checkTimerRange(Instant now, Instant due) {
        if (Duration.between(now, due).compareTo(MAX_TIMER_DELAY) > 0) {
            throw new IllegalArgumentException("Next execution is outside the scheduler's range");
        }
    }

    private Instant nextCronExecution(String expression, ZonedDateTime now) {
        ExecutionTime executionTime = ExecutionTime.forCron(cronParser.parse(expression).validate());
        return executionTime.nextExecution(now).map(ZonedDateTime::toInstant)
            .orElseThrow(() -> new IllegalArgumentException("Cron expression has no next execution: " + expression));
    }
}
