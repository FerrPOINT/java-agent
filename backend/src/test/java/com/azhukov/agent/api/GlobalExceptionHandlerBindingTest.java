package com.azhukov.agent.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies actual MVC argument binding and redaction through the advice, including SSE errors. */
class GlobalExceptionHandlerBindingTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new BindingController())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/binding/not-a-uuid", "/binding/not-a-uuid/messages",
        "/binding?limit=invalid", "/binding?offset=invalid", "/required"
    })
    void malformedOrMissingParametersReturnSafe400(String path) throws Exception {
        MvcResult result = mvc.perform(get(path))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("bad_request"))
            .andExpect(jsonPath("$.error").value("Invalid or missing request parameter"))
            .andReturn();
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain("java.util.UUID", "NumberFormatException", "not-a-uuid");
    }

    @Test
    void validParametersStillReachController() throws Exception {
        mvc.perform(get("/binding/550e8400-e29b-41d4-a716-446655440000"))
            .andExpect(status().isOk());
        mvc.perform(get("/binding?limit=2&offset=1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.limit").value(2));
    }

    @Test
    void unexpectedExceptionDoesNotExposeDiagnostics() throws Exception {
        mvc.perform(get("/failure"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.type").value("internal"))
            .andExpect(jsonPath("$.error").value("Internal server error"));
    }

    @Test
    void unexpectedStreamingExceptionEmitsSafeTerminalError() throws Exception {
        MvcResult result = mvc.perform(get("/failure").accept(MediaType.TEXT_EVENT_STREAM))
            .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getContentAsString())
            .contains("event:error", "Internal server error")
            .doesNotContain("private-diagnostic", "private-token");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Test
    void streamingBindingErrorEmitsSafe400() throws Exception {
        MvcResult result = mvc.perform(get("/binding/not-a-uuid").accept(MediaType.TEXT_EVENT_STREAM))
            .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
            .contains("event:error", "bad_request", "Invalid or missing request parameter")
            .doesNotContain("java.util.UUID", "not-a-uuid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "*/*", "application/json, text/event-stream;q=0.2",
        "text/event-stream;q=0, */*;q=0.8"})
    void jsonClientsKeepJsonErrors(String accept) throws Exception {
        mvc.perform(get("/failure").header("Accept", accept))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.type").value("internal"))
            .andExpect(jsonPath("$.error").value("Internal server error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "application/json;q=0, */*;q=0.8, text/event-stream;q=0.5",
        "application/json;q=0.4, application/*;q=1, text/event-stream;q=0.8",
        "text/*"
    })
    void specificJsonQualityOverridesWildcardBeforeChoosingTerminalStream(String accept) throws Exception {
        MvcResult result = mvc.perform(get("/failure").header("Accept", accept)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(result.getResponse().getContentAsString()).contains("event:error", "Internal server error");
    }

    @Test
    void domainStreamingErrorKeepsItsStatusAndMessage() throws Exception {
        MvcResult result = mvc.perform(get("/agent-failure").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("event:error", "agent", "Session not found");
    }

    @RestController
    static class BindingController {
        @GetMapping({"/binding/{id}", "/binding/{id}/messages"})
        Map<String, Object> session(@PathVariable("id") UUID id) {
            return Map.of("id", id);
        }

        @GetMapping("/binding")
        Map<String, Object> list(@RequestParam(name = "limit", defaultValue = "10") int limit,
                                 @RequestParam(name = "offset", defaultValue = "0") int offset) {
            return Map.of("limit", limit, "offset", offset);
        }

        @GetMapping("/required")
        Map<String, Object> required(@RequestParam("limit") int limit) {
            return Map.of("limit", limit);
        }

        @GetMapping("/failure")
        void failure() {
            throw new RuntimeException("private-diagnostic credential=private-token");
        }

        @GetMapping("/agent-failure")
        void agentFailure() {
            throw new AgentException(org.springframework.http.HttpStatus.NOT_FOUND, "Session not found");
        }
    }
}
