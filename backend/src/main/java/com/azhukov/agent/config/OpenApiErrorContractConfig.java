package com.azhukov.agent.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents the standard error contract on every operation.
 * <p>
 * The backend returns errors as {@code {"type":"...","error":"..."}}
 * (see GlobalExceptionHandler), but 143 operations had no 4xx/5xx responses
 * documented at all — the OpenAPI spec told clients every call always
 * succeeds. This customizer appends the canonical error responses to any
 * operation that documents none, so generated clients get the contract.
 */
@Configuration
public class OpenApiErrorContractConfig {

    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ErrorResponse";

    @Bean
    OperationCustomizer errorContractCustomizer() {
        return (Operation operation, org.springframework.web.method.HandlerMethod handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            boolean has4xx = responses.keySet().stream().anyMatch(c -> c.startsWith("4"));
            if (!has4xx) {
                responses.addApiResponse("400", errorResponse("Bad request — malformed body, invalid parameters"));
                responses.addApiResponse("403", errorResponse("Forbidden — action outside the caller's ownership scope"));
                responses.addApiResponse("500", errorResponse("Internal error — see server logs"));
            }
            return operation;
        };
    }

    /**
     * Registers the canonical error body schema
     * ({@code {"type":"not_found","error":"..."}}) under the name the
     * error-response customizer references.
     */
    @Bean
    org.springdoc.core.customizers.GlobalOpenApiCustomizer errorSchemaCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new io.swagger.v3.oas.models.Components());
            }
            if (openApi.getComponents().getSchemas() == null
                || !openApi.getComponents().getSchemas().containsKey("ErrorResponse")) {
                io.swagger.v3.oas.models.media.Schema<java.lang.Object> schema =
                    new io.swagger.v3.oas.models.media.Schema<>();
                schema.setType("object");
                schema.addProperty("type", new io.swagger.v3.oas.models.media.StringSchema()
                    .description("Machine-readable error category (not_found, forbidden, bad_request, ...)"));
                schema.addProperty("error", new io.swagger.v3.oas.models.media.StringSchema()
                    .description("Human-readable error message"));
                openApi.getComponents().addSchemas("ErrorResponse", schema);
            }
        };
    }

    private static ApiResponse errorResponse(String description) {
        return new ApiResponse()
            .description(description)
            .content(new io.swagger.v3.oas.models.media.Content()
                .addMediaType("application/json",
                    new io.swagger.v3.oas.models.media.MediaType()
                        .schema(new io.swagger.v3.oas.models.media.Schema<>().$ref(ERROR_SCHEMA_REF))));
    }
}
