package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SsrfSafeHttpClient {

    private final UrlSafetyHandler safety;
    private final SecretRedactor redactor;
    private final RestClient restClient;
    private final SimpleClientHttpRequestFactory requestFactory;

    public SsrfSafeHttpClient(UrlSafetyHandler safety, SecretRedactor redactor, AgentProperties properties) {
        this.safety = safety;
        this.redactor = redactor;
        this.requestFactory = new SimpleClientHttpRequestFactory();
        this.requestFactory.setConnectTimeout(5_000); // 5s default connect timeout
        this.requestFactory.setReadTimeout(30_000);   // 30s default read timeout
        this.restClient = RestClient.builder()
            .baseUrl("")
            .defaultHeader("User-Agent", properties.getCore().getHttpUserAgent())
            .requestFactory(requestFactory)
            .build();
    }

    public String fetch(String url, int timeoutSeconds) {
        String error = safety.checkUrl(url);
        if (error != null) {
            throw new SecurityException(error);
        }
        // Apply per-request read timeout on the shared request factory
        requestFactory.setReadTimeout(timeoutSeconds * 1000);
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
        // Apply per-request read timeout on the shared request factory
        requestFactory.setReadTimeout(timeoutSeconds * 1000);
        String result = restClient.post()
            .uri(url)
            .header("User-Agent", "AzhukovAgent/1.0")
            .body(body)
            .retrieve()
            .body(String.class);
        return redactor.redact(result == null ? "" : result);
    }
}