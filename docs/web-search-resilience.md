# Resilient Web Search

`web_search` uses a provider-level fallback chain. It never contains rules for
specific companies, domains, repositories, or example query terms.

## General web queries

1. Java Agent SearXNG is the primary provider.
2. Results are normalized, URL-safety checked, and evaluated against meaningful
   tokens from the original query.
3. An empty, failed, or irrelevant primary response tries configured keyless
   providers in `agent.web.search-provider-order`.
4. The shipped order is `searxng,tavily,parallel,exa`. Tavily's documented keyless
   access mode needs no account; Parallel and Exa use public MCP endpoints. Their
   random process-level identifiers, where required, are never derived from a user or query.
5. A provider returning 429 or 5xx enters a bounded cooldown. Search proceeds
   to later providers instead of repeatedly sending the same failing request.

Keyless tiers are convenience fallbacks, not a promise of unlimited capacity.
Set `TAVILY_API_KEY` only after an operator has completed the provider's normal
onboarding; the same Tavily adapter then uses keyed mode. The application never
creates external accounts, bypasses CAPTCHA, or scrapes protected search pages.

## News queries

Explicit news intent remains separate: SearXNG candidates are relevance-filtered
and Google News RSS is used only if those candidates are absent or unusable.
General keyless providers do not replace the news policy.

## Runtime visibility

`GET /api/v1/agent/config` exposes the configured provider order, keyless
fallback flags, whether a Tavily key is configured, and provider cooldown
settings. It never exposes keys or provider credentials.

## Controls

- `AGENT_WEB_KEYLESS_FALLBACK_ENABLED=false` disables all keyless general-web fallbacks.
- `AGENT_WEB_PARALLEL_KEYLESS_ENABLED=false` keeps Tavily enabled but disables Parallel.
- `AGENT_WEB_SEARCH_PROVIDER_ORDER=searxng,parallel,tavily` changes fallback order.
- `AGENT_WEB_SEARCH_PROVIDER_COOLDOWN_SECONDS=60` controls retry cooldown after 429/5xx.
- `TAVILY_API_KEY` enables Tavily's keyed mode without a code change.
