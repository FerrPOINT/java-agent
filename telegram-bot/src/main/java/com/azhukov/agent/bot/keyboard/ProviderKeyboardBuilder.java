package com.azhukov.agent.bot.keyboard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B2.3: Builds inline keyboard for provider → model drill-down selection.
 * <p>
 * First shows a list of providers, then when a provider is selected
 * (callback {@code pp:<slug>}), shows models for that provider.
 */
@Component
public class ProviderKeyboardBuilder {

    /**
     * Build the provider selection keyboard.
     *
     * @param providers map of provider slug → display name
     * @return list of rows for the inline keyboard
     */
    public List<List<KeyboardButton>> buildProviders(Map<String, String> providers) {
        List<List<KeyboardButton>> rows = new ArrayList<>();
        if (providers == null || providers.isEmpty()) {
            return rows;
        }

        // Provider buttons — 2 per row
        List<KeyboardButton> currentRow = new ArrayList<>();
        for (Map.Entry<String, String> entry : providers.entrySet()) {
            String slug = entry.getKey();
            String name = entry.getValue();
            currentRow.add(new KeyboardButton(name, "pp:" + slug));
            if (currentRow.size() == 2) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        return rows;
    }

    /**
     * Build the model selection keyboard for a specific provider.
     *
     * @param models list of model names for the provider
     * @return list of rows for the inline keyboard
     */
    public List<List<KeyboardButton>> buildModels(List<String> models) {
        List<List<KeyboardButton>> rows = new ArrayList<>();
        if (models == null || models.isEmpty()) {
            return rows;
        }

        // Model buttons — one per row
        for (String model : models) {
            rows.add(List.of(new KeyboardButton(model, "mp:" + model)));
        }

        // Back button
        rows.add(List.of(new KeyboardButton("◀ Back to providers", "pp:back")));

        return rows;
    }

    /**
     * Build a default provider list.
     *
     * @return a map of provider slug → display name
     */
    public Map<String, String> defaultProviders() {
        Map<String, String> providers = new LinkedHashMap<>();
        providers.put("openai", "OpenAI");
        providers.put("anthropic", "Anthropic");
        providers.put("google", "Google");
        providers.put("meta", "Meta");
        providers.put("mistral", "Mistral");
        providers.put("moonshot", "Moonshot (Kimi)");
        return providers;
    }
}