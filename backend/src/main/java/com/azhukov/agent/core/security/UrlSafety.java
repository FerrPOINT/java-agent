package com.azhukov.agent.core.security;

public interface UrlSafety {

    boolean isUrlAllowed(String url);

    boolean isHostBlocked(String host);
}
