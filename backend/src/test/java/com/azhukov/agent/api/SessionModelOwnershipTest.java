package com.azhukov.agent.api;

import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.SessionQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionModelOwnershipTest {
    @Test
    void rejectedOwnerCheckKeepsTheForbiddenContract() throws Exception {
        UUID id = UUID.randomUUID();
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        doThrow(new SecurityException("Session does not belong to the current user"))
                .when(runtime).switchModel(id, "noop", null);
        var controller = new SessionCrudController(mock(SessionQueryService.class), runtime,
                mock(AgentStreamingService.class), null);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper())).build();
        mvc.perform(post("/api/v2/sessions/" + id + "/model")
                .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"noop\"}"))
                .andExpect(status().isForbidden());
    }
}
