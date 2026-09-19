package com.azhukov.agent.service.imagegen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Keyless image generation via pollinations.ai — the dev/default provider.
 * No API key required: the prompt is URL-encoded into the image endpoint and
 * the rendered JPEG/PNG bytes are returned directly.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "agent.image-gen.enabled", havingValue = "true")
public class PollinationsImageGenProvider implements ImageGenProvider {

    private final HttpClient httpClient;

    public PollinationsImageGenProvider() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Override
    public String name() {
        return "pollinations";
    }

    @Override
    public byte[] generate(String prompt, String aspectRatio) {
        try {
            int[] dimensions = mapAspectRatio(aspectRatio);
            String url = "https://image.pollinations.ai/prompt/"
                + URLEncoder.encode(prompt, StandardCharsets.UTF_8)
                + "?width=" + dimensions[0]
                + "&height=" + dimensions[1]
                + "&nologo=true";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "java-agent")
                .GET()
                .timeout(Duration.ofSeconds(120))
                .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.error("Pollinations image generation failed: status={}", response.statusCode());
                throw new RuntimeException("Image generation failed: HTTP " + response.statusCode());
            }
            byte[] image = response.body();
            if (image == null || image.length == 0) {
                throw new RuntimeException("Image generation returned empty data");
            }
            log.debug("Pollinations generated {} bytes for aspectRatio={}", image.length, aspectRatio);
            return image;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Pollinations image generation error: {}", e.getMessage(), e);
            throw new RuntimeException("Image generation failed: " + e.getMessage(), e);
        }
    }

    static int[] mapAspectRatio(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) {
            return new int[]{1024, 576};
        }
        return switch (aspectRatio.trim()) {
            case "9:16", "portrait" -> new int[]{576, 1024};
            case "1:1", "square" -> new int[]{1024, 1024};
            default -> new int[]{1024, 576}; // landscape
        };
    }
}
