package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DohIpDiscoveryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseDohResponseExtractsARecords() throws Exception {
        String json = """
            {
              "Status": 0,
              "Answer": [
                {"name": "api.telegram.org", "type": 1, "TTL": 300, "data": "149.154.167.220"},
                {"name": "api.telegram.org", "type": 1, "TTL": 300, "data": "149.154.167.221"},
                {"name": "api.telegram.org", "type": 28, "TTL": 300, "data": "2001:b28:f0d0:2::12"}
              ]
            }
            """;

        DohIpDiscovery discovery = new DohIpDiscovery(objectMapper, null);
        List<String> ips = discovery.parseDohResponse(json);

        // Should only extract A records (type=1), not AAAA (type=28)
        assertThat(ips).containsExactly("149.154.167.220", "149.154.167.221");
    }

    @Test
    void parseDohResponseEmptyAnswer() throws Exception {
        String json = """
            {"Status": 0, "Answer": []}
            """;

        DohIpDiscovery discovery = new DohIpDiscovery(objectMapper, null);
        List<String> ips = discovery.parseDohResponse(json);
        assertThat(ips).isEmpty();
    }

    @Test
    void parseDohResponseNullAnswer() throws Exception {
        String json = """
            {"Status": 0}
            """;

        DohIpDiscovery discovery = new DohIpDiscovery(objectMapper, null);
        List<String> ips = discovery.parseDohResponse(json);
        assertThat(ips).isEmpty();
    }

    @Test
    void seedFallbackIpsAreValid() {
        // The seed IPs should all be valid public IPs
        List<String> validated = FallbackIpValidator.normalize(DohIpDiscovery.SEED_FALLBACK_IPS);
        assertThat(validated).isNotEmpty();
        assertThat(validated).containsExactlyElementsOf(DohIpDiscovery.SEED_FALLBACK_IPS);
    }

    @Test
    void dohProvidersConfigured() {
        // Verify providers are properly configured
        assertThat(DohIpDiscovery.TELEGRAM_API_HOST).isEqualTo("api.telegram.org");
    }
}