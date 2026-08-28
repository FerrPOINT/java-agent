package com.azhukov.agent.bot.formatting;

import com.azhukov.agent.bot.streaming.SilenceMarkerUtils;
import org.springframework.stereotype.Component;

/**
 * Filters blank responses and the exact interactive silence protocol markers.
 *
 * <p>Hermes gateway/response_filters.py accepts only {@code [SILENT]},
 * {@code SILENT}, {@code NO_REPLY}, and {@code NO REPLY} (with its canonical
 * punctuation handling) as intentional silence. The legacy Java-only
 * {@code ***} sentinel suppressed legitimate Markdown separator responses and
 * had drifted from both the streaming and autonomous-delivery paths.
 */
@Component
public class ResponseFilter {

    /**
     * Check whether content should be withheld from interactive Telegram delivery.
     * Empty output cannot form a Telegram message; non-empty text is suppressed
     * only when it matches Hermes' exact silence protocol.
     */
    public boolean shouldFilter(String content) {
        return content == null || content.isBlank()
            || SilenceMarkerUtils.isSilenceMarker(content);
    }

    /** Return content unless it is blank or an exact silence marker. */
    public String filter(String content) {
        return shouldFilter(content) ? null : content;
    }
}