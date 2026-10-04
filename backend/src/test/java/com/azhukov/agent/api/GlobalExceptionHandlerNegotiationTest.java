package com.azhukov.agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Checks Accept field equivalence and media-range precedence with native Boot MVC error responses. */
@WebMvcTest(controllers = GlobalExceptionHandlerNegotiationTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {GlobalExceptionHandler.class,
    GlobalExceptionHandlerNegotiationTest.ProbeController.class, GlobalExceptionHandlerNegotiationTest.Config.class})
class GlobalExceptionHandlerNegotiationTest {
    @Autowired private MockMvc mvc;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "application/json;q=0.1 | text/event-stream;q=0.9 | true",
        "text/event-stream;q=0.1 | application/json;q=0.9 | false",
        "text/event-stream;q=0.9 | application/json;q=0.1 | true",
        "application/json;q=0.9 | text/event-stream;q=0.1 | false"
    })
    void separateAndCombinedAcceptFieldsChooseTheSameErrorFormat(String first, String second, boolean sse)
            throws Exception {
        assertErrors(sse, first, second);
        assertErrors(sse, first + ", " + second);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "application/json;q=0.1, */*;q=0.9, text/event-stream;q=0.5 | true",
        "application/json;q=0, */*;q=1, text/event-stream;q=0.5 | true",
        "application/*;q=0.9, application/json;q=0.1, text/event-stream;q=0.5 | true",
        "application/json;q=0.9, */*;q=1, text/event-stream;q=0.5 | false",
        "application/json;q=0.1, text/*;q=1, text/event-stream;q=0.05 | false",
        "application/json;q=0.1, */*;q=1, text/event-stream;q=0 | false",
        "text/event-stream;q=0.5, application/json;q=0.5 | false",
        "*/* | false"
    })
    void mostSpecificRangeSetsQualityBeforeComparingFormats(String accept, boolean sse) throws Exception {
        assertErrors(sse, accept);
    }

    private void assertErrors(boolean sse, String... accept) throws Exception {
        String[] paths = {"/negotiation/agent", "/negotiation/internal", "/negotiation/binding?id=invalid"};
        int[] statuses = {404, 500, 400};
        String[] types = {"agent", "internal", "bad_request"};
        String[] messages = {"Session not found", "Internal server error", "Invalid or missing request parameter"};
        for (int i = 0; i < paths.length; i++) {
            var response = mvc.perform(get(paths[i]).header("Accept", (Object[]) accept))
                .andReturn().getResponse();
            assertThat(response.getStatus()).as(paths[i]).isEqualTo(statuses[i]);
            assertThat(response.getContentType()).as(paths[i]).startsWith(
                sse ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE);
            String body = response.getContentAsString();
            if (sse) {
                assertThat(body).startsWith("event:error\ndata:").endsWith("\n\n");
                body = body.lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring(5);
            }
            var payload = new ObjectMapper().readTree(body);
            assertThat(payload.path("type").asText()).isEqualTo(types[i]);
            assertThat(payload.path("error").asText()).isEqualTo(messages[i]);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/negotiation/agent")
        void agent() { throw new AgentException(HttpStatus.NOT_FOUND, "Session not found"); }

        @GetMapping("/negotiation/internal")
        void internal() { throw new RuntimeException("private diagnostic"); }

        @GetMapping("/negotiation/binding")
        String binding(@RequestParam("id") UUID id) { return id.toString(); }
    }
}
