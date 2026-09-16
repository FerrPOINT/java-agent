package com.azhukov.agent.api;

import com.azhukov.agent.service.DeliveryWorkItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-1: the internal delivery consumer port must fail closed on bad claims and
 * return the full immutable work item on a successful claim.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryWorkItemControllerTest {

    @Mock
    private DeliveryWorkItemService deliveryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new DeliveryWorkItemController(deliveryService))
            .build();
    }

    @Test
    void claimReturnsFullWorkItem() throws Exception {
        var entity = new com.azhukov.agent.persistence.entity.DeliveryWorkItemEntity();
        entity.setId(UUID.randomUUID());
        entity.setSourceType(DeliveryWorkItemService.SOURCE_CRON_EXECUTION);
        entity.setSourceId("42");
        entity.setProfile("default");
        entity.setTargetKind("platform");
        entity.setPlatform("telegram");
        entity.setChatId("100");
        entity.setThreadId("7");
        entity.setPayloadText("report body");
        entity.setState("pending");
        entity.setAttempts(0);
        entity.setAvailableAt(Instant.now());
        entity.setCreatedAt(Instant.now());
        when(deliveryService.claimNext(eq("telegram-bot"), any()))
            .thenReturn(Optional.of(new DeliveryWorkItemService.ClaimedWorkItem(entity, "token-1")));

        mockMvc.perform(post("/api/v1/agent/delivery/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"consumerId\":\"telegram-bot\",\"profiles\":[\"default\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.claimed").value(true))
            .andExpect(jsonPath("$.id").value(entity.getId().toString()))
            .andExpect(jsonPath("$.claim_token").value("token-1"))
            .andExpect(jsonPath("$.source_type").value("cron_execution"))
            .andExpect(jsonPath("$.platform").value("telegram"))
            .andExpect(jsonPath("$.chat_id").value("100"))
            .andExpect(jsonPath("$.thread_id").value("7"))
            .andExpect(jsonPath("$.payload").value("report body"));
    }

    @Test
    void claimWithoutConsumerIdIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/agent/delivery/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profiles\":[\"default\"]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ackWithForeignTokenFailsClosedWith409() throws Exception {
        when(deliveryService.markDelivered(any(), eq("attacker"), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/agent/delivery/ack")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + UUID.randomUUID() + "\",\"claim_token\":\"attacker\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.acked").value(false));
    }

    @Test
    void releaseRoutesCategoryAndDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(deliveryService.releaseKnownFailure(eq(id), eq("tok"), eq("transport_error"), eq("boom")))
            .thenReturn(true);

        mockMvc.perform(post("/api/v1/agent/delivery/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\",\"claim_token\":\"tok\",\"category\":\"transport_error\",\"detail\":\"boom\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.released").value(true));

        ArgumentCaptor<String> category = ArgumentCaptor.forClass(String.class);
        verify(deliveryService).releaseKnownFailure(eq(id), eq("tok"), category.capture(), any());
        assertThat(category.getValue()).isEqualTo("transport_error");
    }

    @Test
    void unknownAndDropRequireIdAndToken() throws Exception {
        for (String action : List.of("unknown", "drop")) {
            mockMvc.perform(post("/api/v1/agent/delivery/" + action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isBadRequest());
        }
        verify(deliveryService, never()).markUnknown(any(), any(), any(), any());
        verify(deliveryService, never()).drop(any(), any(), any(), any());
    }
}
