package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SsrfSafeHttpClient {

    private final UrlSafetyHandler safety;
    private final SecretRedactor redactor;
    private final String userAgent;

    public SsrfSafeHttpClient(UrlSafetyHandler safety, SecretRedactor redactor, AgentProperties properties) {
        this.safety = safety;
        this.redactor = redactor;
        this.userAgent = properties.getCore().getHttpUserAgent();
    }

    /**
     * Creates a fresh RestClient with a per-request timeout so concurrent calls
     * don't race to mutate a shared SimpleClientHttpRequestFactory.
     */
    private RestClient createClient(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(Math.max(1, timeoutSeconds) * 1000);
        return RestClient.builder()
            .baseUrl("")
            .defaultHeader("User-Agent", userAgent)
            .requestFactory(factory)
            .build();
    }

    public String fetch(String url, int timeoutSeconds) {
        String error = safety.checkUrl(url);
        if (error != null) {
            throw new SecurityException(error);
        }
        RestClient client = createClient(timeoutSeconds);
        String result = client.get()
            .uri(url)
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
        RestClient client = createClient(timeoutSeconds);
        String result = client.post()
            .uri(url)
            .body(body)
            .retrieve()
            .body(String.class);
        return redactor.redact(result == null ? "" : result);
    }
}