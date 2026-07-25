package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class SsrfSafeHttpClient {

    private final UrlSafetyHandler safety;
    private final SecretRedactor redactor;
    private final RestClient restClient;

    public SsrfSafeHttpClient(UrlSafetyHandler safety, SecretRedactor redactor, AgentProperties properties) {
        this.safety = safety;
        this.redactor = redactor;
        this.restClient = RestClient.builder()
            .baseUrl("")
            .defaultHeader("User-Agent", properties.getCore().getHttpUserAgent())
            .build();
    }

    public String fetch(String url, int timeoutSeconds) {
        String error = safety.checkUrl(url);
        if (error != null) {
            throw new SecurityException(error);
        }
        String result = restClient.get()
            .uri(url)
            .header("User-Agent", "AzhukovAgent/1.0")
            .retrieve()
            .body(String.class);
        if (result == null) result = "";
        return redactor.redact(result);
    }

    public String post(String url, String body, int timeoutSeconds) {
        String error = safety.checkUrl(url);
        if (error != null) {
            throw new SecurityException(error);
        }
        String result = restClient.post()
            .uri(url)
            .header("User-Agent", "AzhukovAgent/1.0")
            .body(body)
            .retrieve()
            .body(String.class);
        return redactor.redact(result == null ? "" : result);
    }
}
