package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * WP-11 (docs/35): the bot registers inbound Telegram media as backend
 * attachment artifacts before the chat request references the ids.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "rawtypes"})
class AttachmentApiClientTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec postSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private AttachmentApiClient client;

    @BeforeEach
    void setUp() {
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object[].class))).thenReturn(postSpec);
        when(postSpec.contentType(any())).thenReturn(postSpec);
        when(postSpec.body(any(Object.class))).thenReturn(postSpec);
        when(postSpec.retrieve()).thenReturn(responseSpec);

        client = new AttachmentApiClient(restClient, new ObjectMapper());
    }

    @Test
    void claimsExactScopedArtifactAndConsumesBinaryResponse() {
        var builder = RestClient.builder().baseUrl("http://backend");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var api = new AttachmentApiClient(builder.build(), new ObjectMapper());
        var session = java.util.UUID.randomUUID();
        String uri = "http://backend/api/v1/attachments/att_test/outbound?ownerId=101&sessionId=" + session;
        var type = org.springframework.http.MediaType.APPLICATION_PDF;
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(uri))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                org.springframework.http.HttpMethod.POST))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                .withSuccess(new byte[] {1, 2}, type)
                .header("Content-Disposition", "inline; filename=\"report.pdf\"")
                .header("X-Outbound-Claim", "2026-01-01T12:00:00Z"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(uri))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                .withNoContent());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(uri))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                .withStatus(org.springframework.http.HttpStatus.CONFLICT));

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
            "http://backend/api/v1/attachments/att_test/delivered?ownerId=101&sessionId=" + session + "&messageId=12"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                org.springframework.http.HttpMethod.POST))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent());

        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(uri))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.DELETE))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("X-Outbound-Claim", "2026-01-01T12:00:00Z"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent());

        var artifact = api.claimOutbound("att_test", "101", session).orElseThrow();
        assertThat(artifact.fileName()).isEqualTo("report.pdf");
        assertThat(artifact.mimeType()).isEqualTo("application/pdf");
        assertThat(artifact.bytes()).containsExactly(1, 2);
        assertThat(api.claimOutbound("att_test", "101", session)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> api.claimOutbound("att_test", "101", session))
            .isInstanceOf(AttachmentApiClient.OutboundUnavailable.class);
        api.markOutboundDelivered("att_test", "101", session, 12);
        api.releaseOutbound(artifact, "101", session);
        server.verify();
    }

    private static final byte[] DATA = {1, 2};
    private static final String ID = "att_0123456789abcdef0123456789abcdef";

    private String receipt(boolean duplicate) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(DATA));
        return "{\"id\":\"" + ID + "\",\"contentHash\":\"" + hash
                + "\",\"duplicate\":" + duplicate + ",\"rejection\":null}";
    }

    @Test
    void registrationConfirmsExactBytesAndDuplicateStatus() throws Exception {
        for (boolean duplicate : new boolean[] {false, true}) {
            when(responseSpec.body(String.class)).thenReturn(receipt(duplicate));
            var result = client.register(DATA, "photo.jpg", "image/jpeg", "photo", "telegram", "123", null, "456");
            assertThat(result.id()).isEqualTo(ID);
            assertThat(result.duplicate()).isEqualTo(duplicate);
        }
    }

    @Test
    void malformedOrMismatchedAcknowledgementRemainsUnknownWithoutRetry() throws Exception {
        for (String body : new String[] {"not-json{{", "{}", receipt(false).replace(ID, "att_bad"),
                receipt(false).replace("contentHash", "wrongHash"), receipt(false).replace("false", "\"false\""),
                receipt(false).replaceAll("(?<=\"contentHash\":\")[a-f0-9]{64}", "0".repeat(64))}) {
            when(responseSpec.body(String.class)).thenReturn(body);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.register(DATA, "photo.jpg", "image/jpeg",
                    "photo", "telegram", "123", null, null))
                .isInstanceOfSatisfying(AttachmentApiClient.AdmissionFailure.class,
                        failure -> assertThat(failure.outcomeUnknown()).isTrue());
        }
        org.mockito.Mockito.verify(restClient, org.mockito.Mockito.times(6)).post();
    }

    @Test
    void networkFailureIsUnknownAndDoesNotRetry() {
        when(responseSpec.body(String.class)).thenThrow(new org.springframework.web.client.ResourceAccessException("lost response"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.register(DATA, "photo.jpg", "image/jpeg",
                "photo", "telegram", "123", null, null))
            .isInstanceOfSatisfying(AttachmentApiClient.AdmissionFailure.class,
                    failure -> assertThat(failure.outcomeUnknown()).isTrue());
        org.mockito.Mockito.verify(restClient).post();
    }

    @Test
    void rejectedAndUncertainHttpOutcomesRemainDistinct() {
        for (int status : new int[] {403, 408, 500}) {
            org.mockito.Mockito.doThrow(new org.springframework.web.client.RestClientResponseException(
                    "fixture", org.springframework.http.HttpStatusCode.valueOf(status), "fixture",
                    org.springframework.http.HttpHeaders.EMPTY, new byte[0], null))
                .when(responseSpec).body(String.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.register(DATA, "photo.jpg", "image/jpeg",
                    "photo", "telegram", "123", null, null))
                .isInstanceOfSatisfying(AttachmentApiClient.AdmissionFailure.class,
                        failure -> assertThat(failure.outcomeUnknown()).isEqualTo(status != 403));
        }
    }

    @Test
    void emptyInputIsRejectedBeforeUpload() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.register(new byte[0], "x", "image/jpeg",
                "photo", "telegram", "123", null, null))
            .isInstanceOfSatisfying(AttachmentApiClient.AdmissionFailure.class,
                    failure -> assertThat(failure.outcomeUnknown()).isFalse());
        org.mockito.Mockito.verifyNoInteractions(restClient);
    }
}
