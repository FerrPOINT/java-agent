package com.azhukov.agent.core.security;

/** Actual native authentication provenance, separate from human roles and central identity. */
public final class NativeIdentityOrigin {
    public enum Kind { DEVELOPMENT, GATEWAY_KEY, USER_API_KEY, GATEWAY_EVENT }
    public record Origin(Kind kind, String principal, boolean mayNameNativeScope) {
        public boolean permits(String key) {
            return key != null && !key.isBlank() && (mayNameNativeScope || key.equals(principal));
        }
    }
    private static final ThreadLocal<Origin> CURRENT = new ThreadLocal<>();
    private NativeIdentityOrigin() {}
    public static Origin current() { return CURRENT.get(); }
    public static void bind(Origin origin) { if (origin == null) CURRENT.remove(); else CURRENT.set(origin); }
    public static void clear() { CURRENT.remove(); }
}
