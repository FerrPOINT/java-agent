package com.azhukov.agent.core.model;

import com.azhukov.agent.core.model.IdentityDeletionProtocol.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IdentityDeletionProtocolTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void wire_has_exact_base_names_and_strict_request_shape()throws Exception {
        Request request=new Request(1,UUID.randomUUID(),new Identity("https://auth.example.test",UUID.randomUUID().toString()),UUID.randomUUID());
        var json=mapper.readTree(mapper.writeValueAsBytes(request));
        assertThat(json.has("schema_version")).isTrue();assertThat(json.has("operation_id")).isTrue();assertThat(json.has("inventory_revision")).isTrue();
        Request decoded=mapper.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(mapper.writeValueAsBytes(request));
        assertThat(decoded).isEqualTo(request);
        ((com.fasterxml.jackson.databind.node.ObjectNode)json).put("credential","synthetic-forbidden-field");
        assertThatThrownBy(()->mapper.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json)).isInstanceOf(java.io.IOException.class);
    }
    @Test void malformed_subject_or_issuer_never_becomes_a_valid_owner_request() {
        for(String subject:new String[]{"", "native-user",new UUID(0,0).toString(),"1","00000000-0000-4000-8000-00000000000A"}) {
            assertThat(new Request(1,UUID.randomUUID(),new Identity("https://auth.example.test",subject),UUID.randomUUID()).valid()).isFalse();
        }
        assertThat(new Request(1,UUID.randomUUID(),new Identity("https://user:password@example.test",UUID.randomUUID().toString()),UUID.randomUUID()).valid()).isFalse();
    }
    @Test void original_monotonic_deadline_is_independent_of_a_future_wall_expiry() {
        Window expired=new Window(System.nanoTime()-1,Instant.now().plusSeconds(300));
        assertThatThrownBy(expired::requireCurrent).isInstanceOfSatisfying(Failure.class,failure->assertThat(failure.status()).isEqualTo(401));
    }
    @Test void commit_clock_uses_the_database_anchor_without_renewing_original_life() {
        Instant anchor=Instant.now().minusSeconds(3600);
        Window original=new Window(System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos(),Instant.now().plusSeconds(3600));
        assertThat(original.commitExpiresAt(anchor)).isAfter(anchor).isBeforeOrEqualTo(anchor.plusSeconds(10));
        Instant originalExpiry=Instant.now().plusSeconds(5);
        Window wallLimited=new Window(System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos(),originalExpiry);
        assertThat(wallLimited.commitExpiresAt(Instant.now().plusSeconds(3600))).isEqualTo(originalExpiry);
    }
}
