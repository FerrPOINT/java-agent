package com.azhukov.agent.bot.keyboard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * B2.2: Builds paginated inline keyboards for model selection.
 * <p>
 * Lists models 8 per page with prev/next navigation buttons.
 * Callback data format: {@code mp:<model>} for model selection,
 * {@code mpp:<page>} for pagination.
 */
@Component
public class ModelKeyboardBuilder {

    private static final int MODELS_PER_PAGE = 8;

    /**
     * Build a paginated inline keyboard for model selection.
     *
     * @param models    list of available model names
     * @param page      current page (0-indexed)
     * @return list of rows for the inline keyboard
     */
    public List<List<KeyboardButton>> build(List<String> models, int page) {
        List<List<KeyboardButton>> rows = new ArrayList<>();
        if (models == null || models.isEmpty()) {
            return rows;
        }

        int totalPages = (models.size() + MODELS_PER_PAGE - 1) / MODELS_PER_PAGE;
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        int start = page * MODELS_PER_PAGE;
        int end = Math.min(start + MODELS_PER_PAGE, models.size());

        // Model buttons — one per row
        for (int i = start; i < end; i++) {
            String model = models.get(i);
            rows.add(List.of(new KeyboardButton(model, "mp:" + model)));
        }

        // Navigation row
        List<KeyboardButton> navRow = new ArrayList<>();
        if (page > 0) {
            navRow.add(new KeyboardButton("◀ Prev", "mpp:" + (page - 1)));
        }
        if (page < totalPages - 1) {
            navRow.add(new KeyboardButton("Next ▶", "mpp:" + (page + 1)));
        }
        if (!navRow.isEmpty()) {
            rows.add(navRow);
        }

        return rows;
    }

    /**
     * Get the total number of pages for the given model list.
     *
     * @param models list of available model names
     * @return total pages
     */
    public int totalPages(List<String> models) {
        if (models == null || models.isEmpty()) return 0;
        return (models.size() + MODELS_PER_PAGE - 1) / MODELS_PER_PAGE;
    }
}