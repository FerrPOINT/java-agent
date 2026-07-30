package com.azhukov.agent.bot.session;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Controls when sessions reset (lose context).
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code daily}: Reset at a specific hour each day</li>
 *   <li>{@code idle}: Reset after N minutes of inactivity</li>
 *   <li>{@code both}: Whichever triggers first (daily boundary OR idle timeout)</li>
 *   <li>{@code none}: Never auto-reset</li>
 * </ul>
 *
 * <p>Mirrors the Python {@code SessionResetPolicy} in
 * {@code gateway/config.py}.
 */
@Getter
@Setter
public class SessionResetPolicy {

    private SessionResetMode mode = SessionResetMode.BOTH;
    private int atHour = 4; // Hour for daily reset (0-23, local time)
    private int idleMinutes = 1440; // Minutes of inactivity before reset (24 hours default)
    private boolean notify = true;

    /**
     * Check if a session should be reset based on this policy.
     *
     * @param createdAt  session creation time
     * @param updatedAt  last update time
     * @param now        current time
     * @return reset reason ("idle" or "daily") if a reset is needed, null if valid
     */
    public String shouldReset(Instant createdAt, Instant updatedAt, Instant now) {
        if (mode == SessionResetMode.NONE) {
            return null;
        }

        if (mode == SessionResetMode.IDLE || mode == SessionResetMode.BOTH) {
            Instant idleDeadline = updatedAt.plusSeconds(idleMinutes * 60L);
            if (now.isAfter(idleDeadline)) {
                return "idle";
            }
        }

        if (mode == SessionResetMode.DAILY || mode == SessionResetMode.BOTH) {
            LocalDateTime nowLocal = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
            LocalDateTime todayReset = nowLocal.withHour(atHour).withMinute(0).withSecond(0).withNano(0);
            if (nowLocal.getHour() < atHour) {
                todayReset = todayReset.minusDays(1);
            }
            Instant todayResetInstant = todayReset.atZone(ZoneId.systemDefault()).toInstant();
            if (updatedAt.isBefore(todayResetInstant)) {
                return "daily";
            }
        }

        return null;
    }

    /**
     * Check if a session is expired (for the background watcher).
     *
     * @param updatedAt last update time
     * @param now       current time
     * @return true if expired
     */
    public boolean isExpired(Instant updatedAt, Instant now) {
        return shouldReset(updatedAt, updatedAt, now) != null;
    }
}