package com.azhukov.agent.tools.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** A general-web search source that returns normalized search results. */
public interface WebSearchProvider {

    String name();

    boolean isAvailable();

    List<Map<String, String>> search(String query, int limit) throws IOException;
}
