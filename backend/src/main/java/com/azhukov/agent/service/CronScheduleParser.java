package com.azhukov.agent.service;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
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
            return new ScheduleInfo(KIND_INTERVAL, now.plusSeconds(parseDurationSeconds(everyMatcher.group(1))));
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
            return new ScheduleInfo(KIND_ONCE, now.plusSeconds(parseDurationSeconds(trimmed)));
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 5 && isCronExpression(parts)) {
            return new ScheduleInfo(KIND_CRON, nextCronExecution(trimmed, now.atZone(zone)));
        }
        throw new IllegalArgumentException("Invalid schedule: " + schedule);
    }

    private static ScheduleInfo oneShot(Instant due, Instant now) {
        return new ScheduleInfo(KIND_ONCE, due.isAfter(now) ? due : now.plusSeconds(1));
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

    private long parseDurationSeconds(String value) {
        Matcher matcher = DURATION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid duration: " + value);
        long amount = Long.parseLong(matcher.group(1));
        long seconds = switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "s" -> amount;
            case "m" -> amount * 60;
            case "h" -> amount * 3600;
            case "d" -> amount * 86400;
            default -> throw new IllegalArgumentException("Invalid duration unit: " + value);
        };
        return Math.max(1, seconds);
    }

    private Instant nextCronExecution(String expression, ZonedDateTime now) {
        ExecutionTime executionTime = ExecutionTime.forCron(cronParser.parse(expression).validate());
        return executionTime.nextExecution(now).map(ZonedDateTime::toInstant)
            .orElseThrow(() -> new IllegalArgumentException("Cron expression has no next execution: " + expression));
    }
}
