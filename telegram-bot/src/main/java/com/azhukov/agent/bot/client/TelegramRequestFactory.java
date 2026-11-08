package com.azhukov.agent.bot.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * Custom request factory for Telegram API connections.
 *
 * <p>Sets a permissive {@code HostnameVerifier} on HTTPS connections so that
 * fallback IP connections (which connect to an IP but present a certificate
 * for {@code api.telegram.org}) are accepted. The certificate chain is still
 * verified by the default trust manager — only hostname verification is relaxed.
 *
 * <p>This is the Java equivalent of the Python SNI hostname rewriting in
 * {@code gateway/platforms/telegram_network.py}.
 */
@Slf4j
public class TelegramRequestFactory extends SimpleClientHttpRequestFactory {

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        if (connection instanceof HttpsURLConnection https) {
            // Accept api.telegram.org certificate even when connecting via fallback IP.
            // The certificate chain is still validated by the default trust manager.
            https.setHostnameVerifier((hostname, session) -> {
                log.debug("Accepting SSL hostname '{}' for Telegram fallback connection", hostname);
                return true;
            });
        }
    }
}