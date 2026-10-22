package com.azhukov.agent.core.security;

/**
 * Thread-local holder for the current user's identity.
 * Populated by ApiKeyAuthFilter (or dev-mode default) and consumed
 * by service-layer methods that need userId scoping.
 *
 * In multi-user mode, the userId comes from the authenticated API key.
 * In single-user/dev mode, it defaults to {@code AgentProperties.DEFAULT_USER_ID}.
 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_ROLE = new ThreadLocal<>();
    private static final ThreadLocal<com.pdlc.auth.HumanApplicationAccess> CENTRAL_ACCESS = new ThreadLocal<>();

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    private UserContext() {}

    /** Set the current user's ID and role for this thread. */
    public static void set(String userId, String role) {
        CENTRAL_ACCESS.remove();
        CURRENT_USER_ID.set(userId);
        CURRENT_ROLE.set(role != null ? role : ROLE_USER);
    }

    /** Binds the live Auth decision to this request without retaining the bearer. */
    public static void setCentral(com.pdlc.auth.HumanApplicationAccess access) {
        set(access.subject(), access.role());
        CENTRAL_ACCESS.set(access);
    }

    /** Exposes the same original deadline for explicit asynchronous propagation. */
    public static com.pdlc.auth.HumanApplicationAccess centralAccess() {
        return CENTRAL_ACCESS.get();
    }

    /** An expired decision cannot become a standalone role through asynchronous propagation. */
    public static void requireCurrentAccess() {
        var access = CENTRAL_ACCESS.get();
        if (access != null && !access.permits(java.util.Set.of("java-agent"), false)) {
            throw new org.springframework.security.access.AccessDeniedException("Current application access required");
        }
    }

    /** Get the current user's ID, or null if not set. */
    public static String getUserId() {
        requireCurrentAccess();
        return CURRENT_USER_ID.get();
    }

    /** Get the current user's role, or null if not set. */
    public static String getRole() {
        requireCurrentAccess();
        return CURRENT_ROLE.get();
    }

    /** Whether the current user has admin role. */
    public static boolean isAdmin() {
        var access = CENTRAL_ACCESS.get();
        return ROLE_ADMIN.equals(CURRENT_ROLE.get()) && (access == null
                || access.permits(java.util.Set.of("java-agent"), true));
    }

    /**
     * Returns the userId to use for scoping queries.
     * Admins get null (global access); regular users get their own userId.
     * If UserContext is not set (no auth filter), returns null (admin/all access).
     */
    public static String scopeUserId() {
        if (getUserId() == null) return null; // no auth context → full access
        return isAdmin() ? null : getUserId();
    }

    /**
     * Resolves an API-supplied userId without allowing a non-admin key to
     * impersonate another user. Trusted admin gateways retain the requested id
     * so Telegram can propagate the actual sender identity.
     */
    public static String effectiveUserId(String requestedUserId) {
        if (CENTRAL_ACCESS.get() != null) return getUserId();
        if (isAdmin()) return requestedUserId != null ? requestedUserId : getUserId();
        return getUserId() != null ? getUserId() : requestedUserId;
    }

    /** A queued task retains the exact proof and never captures a refreshed role/TTL. */
    public static Runnable wrap(Runnable work) {
        var captured = Snapshot.capture();
        return () -> captured.run(work);
    }

    public static <T> java.util.function.Supplier<T> wrapSupplier(java.util.function.Supplier<T> work) {
        var captured = Snapshot.capture();
        return () -> {
            var previous = Snapshot.capture();
            captured.install();
            try { return work.get(); }
            finally { previous.install(); }
        };
    }

    /** Bound a blocking stream wait to the same original proof; no TTL is created. */
    public static long accessWaitMillis(long maximum) {
        if (maximum <= 0) throw new IllegalArgumentException("Positive wait required");
        requireCurrentAccess();
        var access = CENTRAL_ACCESS.get();
        if (access == null) return maximum;
        long nanos = access.deadlineNanos() - System.nanoTime();
        long wallMillis = java.time.Duration.between(java.time.Instant.now(), access.expiresAt()).toMillis();
        return Math.max(1, Math.min(maximum, Math.min(wallMillis,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos))));
    }

    private record Snapshot(String user, String role, com.pdlc.auth.HumanApplicationAccess access) {
        static Snapshot capture() { return new Snapshot(CURRENT_USER_ID.get(), CURRENT_ROLE.get(), CENTRAL_ACCESS.get()); }
        void install() {
            clear();
            if (access != null) setCentral(access);
            else if (user != null || role != null) set(user, role);
        }
        void run(Runnable work) {
            var previous = capture();
            install();
            try { work.run(); }
            finally { previous.install(); }
        }
    }

    /** Clear the context for this thread. */
    public static void clear() {
        CENTRAL_ACCESS.remove();
        CURRENT_USER_ID.remove();
        CURRENT_ROLE.remove();
    }
}