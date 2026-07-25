package com.azhukov.agent.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.MethodParameter;

import jakarta.validation.Valid;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void agentExceptionReturnsConfiguredStatusAndMessage() throws Exception {
        mockMvc.perform(get("/test/agent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("agent"))
                .andExpect(jsonPath("$.error").value("agent error"));
    }

    @Test
    void runtimeExceptionReturnsInternalServerError() throws Exception {
        mockMvc.perform(get("/test/runtime"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("internal"))
                .andExpect(jsonPath("$.error").value("Internal error: boom"));
    }

    @Test
    void validationExceptionReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void responseBodyContainsExpectedJsonField() throws Exception {
        mockMvc.perform(get("/test/agent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error").value("agent error"));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/agent")
        public String agent() {
            throw new AgentException(HttpStatus.NOT_FOUND, "agent error");
        }

        @GetMapping("/test/runtime")
        public String runtime() {
            throw new RuntimeException("boom");
        }

        @GetMapping("/test/validation")
        public String validation() throws Exception {
            throw validationException();
        }

        private static MethodArgumentNotValidException validationException() throws Exception {
            java.lang.reflect.Method method = TestController.class.getDeclaredMethod("validatedMethod", DummyDto.class);
            MethodParameter parameter = new MethodParameter(method, 0);
            BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new DummyDto(), "dummy");
            bindingResult.reject("NotBlank", "must not be blank");
            return new MethodArgumentNotValidException(parameter, bindingResult);
        }

        @SuppressWarnings("unused")
        public String validatedMethod(@Valid @RequestBody DummyDto dto) {
            return "ok";
        }
    }

    static class DummyDto {
        @SuppressWarnings("unused")
        private String field;
    }
}
