package com.azhukov.agent.bot.session;

/**
 * Controls when sessions reset (lose context).
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code DAILY}: Reset at a specific hour each day</li>
 *   <li>{@code IDLE}: Reset after N minutes of inactivity</li>
 *   <li>{@code BOTH}: Whichever triggers first (daily boundary OR idle timeout)</li>
 *   <li>{@code NONE}: Never auto-reset (context managed only by compression)</li>
 * </ul>
 *
 * <p>Mirrors the Python {@code SessionResetPolicy} in
 * {@code gateway/config.py}.
 */
public enum SessionResetMode {
    DAILY,
    IDLE,
    BOTH,
    NONE
}