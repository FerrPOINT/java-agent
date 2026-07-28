package com.azhukov.agent.service.imagegen;

/**
 * Provider interface for image generation.
 */
public interface ImageGenProvider {

    /**
     * Generate an image from a text prompt.
     *
     * @param prompt      the text prompt describing the desired image
     * @param aspectRatio the desired aspect ratio (e.g. "1:1", "16:9", "9:16")
     * @return the generated image as raw bytes (PNG/JPEG)
     */
    byte[] generate(String prompt, String aspectRatio);
}