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

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    private UserContext() {}

    /** Set the current user's ID and role for this thread. */
    public static void set(String userId, String role) {
        CURRENT_USER_ID.set(userId);
        CURRENT_ROLE.set(role != null ? role : ROLE_USER);
    }

    /** Get the current user's ID, or null if not set. */
    public static String getUserId() {
        return CURRENT_USER_ID.get();
    }

    /** Get the current user's role, or null if not set. */
    public static String getRole() {
        return CURRENT_ROLE.get();
    }

    /** Whether the current user has admin role. */
    public static boolean isAdmin() {
        return ROLE_ADMIN.equals(CURRENT_ROLE.get());
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
        if (isAdmin()) return requestedUserId != null ? requestedUserId : getUserId();
        return getUserId() != null ? getUserId() : requestedUserId;
    }

    /** Clear the context for this thread. */
    public static void clear() {
        CURRENT_USER_ID.remove();
        CURRENT_ROLE.remove();
    }
}