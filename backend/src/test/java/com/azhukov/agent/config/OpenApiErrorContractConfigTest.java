package com.azhukov.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rev-48: the OpenAPI spec documented no error responses on 143 operations —
 * generated clients had no contract for the canonical
 * {"type":"...","error":"..."} error body. The customizer appends 400/403/500
 * to every operation that documents no 4xx itself.
 */
class OpenApiErrorContractConfigTest {

    private final OpenApiErrorContractConfig config = new OpenApiErrorContractConfig();

    @Test
    void operationWithoutErrorsGetsCanonicalSet() {
        Operation op = new Operation().responses(new ApiResponses());
        config.errorContractCustomizer().customize(op, null);
        assertThat(op.getResponses().keySet()).contains("400", "403", "500");
        assertThat(op.getResponses().get("403").getContent()
            .get("application/json").getSchema().get$ref())
            .isEqualTo("#/components/schemas/ErrorResponse");
    }

    @Test
    void operationWithOwn4xxIsNotDuplicated() {
        Operation op = new Operation().responses(new ApiResponses()
            .addApiResponse("409", new io.swagger.v3.oas.models.responses.ApiResponse()
                .description("conflict")));
        config.errorContractCustomizer().customize(op, null);
        assertThat(op.getResponses().keySet()).containsExactly("409");
    }

    @Test
    void errorSchemaIsRegistered() {
        OpenAPI openApi = new OpenAPI();
        config.errorSchemaCustomizer().customise(openApi);
        assertThat(openApi.getComponents().getSchemas()).containsKey("ErrorResponse");
        assertThat(openApi.getComponents().getSchemas().get("ErrorResponse").getProperties())
            .containsKeys("type", "error");
    }
}
