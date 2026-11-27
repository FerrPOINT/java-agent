package com.azhukov.agent.core.security;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Profile selected by an authenticated request or an application-owned job. */
public final class ProfileDatabaseScope {

    public static final String HISTORY_OVERVIEW = "!history_overview";
    private static final Pattern PROFILE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ProfileDatabaseScope() {}

    public static String current() {
        return CURRENT.get();
    }

    public static boolean validProfile(String value) {
        return value != null && PROFILE.matcher(value).matches();
    }

    public static Binding enter(String profile) {
        if (!validProfile(profile)) {
            throw new IllegalArgumentException("Invalid database profile");
        }
        return enterScope(profile);
    }

    /** For profile dashboard read projections and startup discovery. */
    public static Binding enterHistoryOverview() {
        return enterScope(HISTORY_OVERVIEW);
    }

    public static Binding enterAttachmentExpiry() {
        return enterScope("!attachment_expiry");
    }

    public static <T> T call(String profile, Supplier<T> work) {
        try (var scope = enter(profile)) {
            return work.get();
        }
    }

    public static void run(String profile, Runnable work) {
        call(profile, () -> {
            work.run();
            return null;
        });
    }

    /** Retain the original profile when server-owned work moves to another thread. */
    public static Runnable carry(Runnable work) {
        return carrySupplier(() -> {
            work.run();
            return null;
        })::get;
    }

    public static <T> Supplier<T> carrySupplier(Supplier<T> work) {
        String profile = current();
        return () -> {
            try (var scope = enterScope(profile)) {
                return work.get();
            }
        };
    }

    private static Binding enterScope(String profile) {
        String previous = current();
        if (previous != null && !Objects.equals(previous, profile)) {
            throw new IllegalStateException("A profile operation cannot change its database scope");
        }
        set(profile);
        return () -> set(previous);
    }

    private static void set(String profile) {
        if (profile == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(profile);
        }
    }

    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }
}
