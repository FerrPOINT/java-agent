package com.azhukov.agent.service;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Parses user-facing cron schedules and calculates the next delay. */
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

    public record ScheduleInfo(String kind, long delaySeconds) {}

    ScheduleInfo parse(String schedule) {
        if (schedule == null || schedule.isBlank()) {
            throw new IllegalArgumentException("Schedule must not be blank");
        }
        String trimmed = schedule.trim();
        Matcher everyMatcher = EVERY_PREFIX.matcher(trimmed);
        if (everyMatcher.matches()) {
            return new ScheduleInfo(KIND_INTERVAL, parseDurationSeconds(everyMatcher.group(1)));
        }
        if (ISO_TIMESTAMP_PATTERN.matcher(trimmed).matches()) {
            try {
                LocalDateTime dateTime = parseIsoTimestamp(trimmed);
                return new ScheduleInfo(KIND_ONCE, Math.max(1,
                    Duration.between(LocalDateTime.now(), dateTime).getSeconds()));
            } catch (DateTimeParseException ignored) {
                // Continue to the other schedule forms for a useful error below.
            }
        }
        try {
            Instant instant = Instant.parse(trimmed);
            return new ScheduleInfo(KIND_ONCE, Math.max(1, Duration.between(Instant.now(), instant).getSeconds()));
        } catch (DateTimeParseException ignored) {
            // Not an offset timestamp.
        }
        if (DURATION_PATTERN.matcher(trimmed).matches()) {
            return new ScheduleInfo(KIND_ONCE, parseDurationSeconds(trimmed));
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 5 && isCronExpression(parts)) {
            return new ScheduleInfo(KIND_CRON, calculateCronDelaySeconds(trimmed));
        }
        throw new IllegalArgumentException("Invalid schedule: " + schedule);
    }

    long calculateDelaySeconds(String schedule) {
        return parse(schedule).delaySeconds();
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

    private LocalDateTime parseIsoTimestamp(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    private long parseDurationSeconds(String value) {
        Matcher matcher = DURATION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid duration: " + value);
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase()) {
            case "s" -> amount;
            case "m" -> amount * 60;
            case "h" -> amount * 3600;
            case "d" -> amount * 86400;
            default -> throw new IllegalArgumentException("Invalid duration unit: " + value);
        };
    }

    private long calculateCronDelaySeconds(String expression) {
        try {
            Optional<ZonedDateTime> next = ExecutionTime.forCron(cronParser.parse(expression))
                .nextExecution(ZonedDateTime.now());
            return next.map(time -> Math.max(1, Duration.between(ZonedDateTime.now(), time).getSeconds()))
                .orElse(60L);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cron expression: " + expression, e);
        }
    }
}
