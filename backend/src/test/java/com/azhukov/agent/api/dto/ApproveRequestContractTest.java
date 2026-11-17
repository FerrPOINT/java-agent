package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApproveRequestContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void currentRequestIdIsTheOnlyExplicitSelector() throws Exception {
        var id = UUID.randomUUID();
        var request = json.readValue("{\"requestId\":\"" + id + "\"}", ApproveRequest.class);
        assertThat(request.requestId()).isEqualTo(id);
        assertThat(request.isAll()).isFalse();
    }

    @Test
    void allAndOmittedSelectorsRetainTheirCurrentMeaning() throws Exception {
        assertThat(json.readValue("{\"all\":true}", ApproveRequest.class).isAll()).isTrue();
        var omitted = json.readValue("{}", ApproveRequest.class);
        assertThat(omitted.isAll()).isFalse();
        assertThat(omitted.requestId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"scope\":\"session\"}", "{\"scope\":\"always\"}", "{\"sessionId\":null}", "{\"requestId\":\"not-a-uuid\"}"})
    void obsoleteOrMalformedSelectorsAreRejected(String body) {
        assertThatThrownBy(() -> json.readValue(body, ApproveRequest.class))
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
