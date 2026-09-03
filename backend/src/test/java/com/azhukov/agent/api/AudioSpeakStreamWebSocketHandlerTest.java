package com.azhukov.agent.api;

import com.azhukov.agent.service.tts.TtsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AudioSpeakStreamWebSocketHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sendsHermesFallbackFrameAndClosesWhenTtsUnavailable() throws Exception {
        TtsService ttsService = mock(TtsService.class);
        when(ttsService.isAvailable()).thenReturn(false);
        AudioSpeakStreamWebSocketHandler handler = new AudioSpeakStreamWebSocketHandler(ttsService);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<WebSocketMessage<?>> message = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(message.capture());
        assertThat(message.getValue()).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) message.getValue()).getPayload()).isEqualTo("{\"type\":\"fallback\"}");
        verify(session).close(CloseStatus.NORMAL);
    }

    @Test
    void doesNotWriteToAlreadyClosedSession() throws Exception {
        TtsService ttsService = mock(TtsService.class);
        AudioSpeakStreamWebSocketHandler handler = new AudioSpeakStreamWebSocketHandler(ttsService);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(session);

        verify(session, never()).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(session, never()).close(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sendsStartFrameWhenTtsIsAvailable() throws Exception {
        TtsService ttsService = mock(TtsService.class);
        when(ttsService.isAvailable()).thenReturn(true);
        AudioSpeakStreamWebSocketHandler handler = new AudioSpeakStreamWebSocketHandler(ttsService);
        WebSocketSession session = openSession();

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<WebSocketMessage<?>> message = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(message.capture());
        JsonNode start = MAPPER.readTree(((TextMessage) message.getValue()).getPayload());
        assertThat(start.get("type").asText()).isEqualTo("start");
        assertThat(start.get("sample_rate").asInt()).isEqualTo(24_000);
        assertThat(start.get("channels").asInt()).isEqualTo(1);
        assertThat(start.get("mime_type").asText()).isEqualTo("audio/mpeg");
        assertThat(start.get("encoding").asText()).isEqualTo("mp3");
        verify(session, never()).close(any());
    }

    @Test
    void streamsBinaryAudioFramesAndEndAfterDoneFrame() throws Exception {
        TtsService ttsService = mock(TtsService.class);
        when(ttsService.isAvailable()).thenReturn(true);
        when(ttsService.synthesize("Hello there.", "nova")).thenReturn(new byte[]{1, 2, 3});
        AudioSpeakStreamWebSocketHandler handler = new AudioSpeakStreamWebSocketHandler(ttsService);
        WebSocketSession session = openSession();

        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage("{\"text\":\"Hello there.\",\"voice\":\"nova\",\"done\":true}"));

        ArgumentCaptor<WebSocketMessage<?>> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.times(3)).sendMessage(messages.capture());
        List<WebSocketMessage<?>> sent = messages.getAllValues();
        assertThat(MAPPER.readTree(((TextMessage) sent.get(0)).getPayload()).get("type").asText()).isEqualTo("start");
        assertThat(sent.get(1)).isInstanceOf(BinaryMessage.class);
        assertThat(((BinaryMessage) sent.get(1)).getPayload().array()).containsExactly(1, 2, 3);
        assertThat(MAPPER.readTree(((TextMessage) sent.get(2)).getPayload()).get("type").asText()).isEqualTo("end");
        verify(ttsService).synthesize("Hello there.", "nova");
        verify(session).close(CloseStatus.NORMAL);
    }

    @Test
    void longTextIsSplitAcrossProviderRequestsWithoutLosingContent() {
        String text = "Alpha beta. Gamma delta epsilon. Zeta eta theta iota kappa.";

        List<String> pieces = AudioSpeakStreamWebSocketHandler.splitTextForSpeakStream(text, 30);

        assertThat(pieces).isNotEmpty();
        assertThat(pieces).allSatisfy(piece -> assertThat(piece.length()).isLessThanOrEqualTo(30));
        String joined = String.join(" ", pieces);
        for (String word : text.replace(".", "").split("\\s+")) {
            assertThat(joined).contains(word);
        }
    }

    @Test
    void invalidJsonFailsClosedWithoutCallingTts() throws Exception {
        TtsService ttsService = mock(TtsService.class);
        AudioSpeakStreamWebSocketHandler handler = new AudioSpeakStreamWebSocketHandler(ttsService);
        WebSocketSession session = openSession();

        handler.handleMessage(session, new TextMessage("{"));

        ArgumentCaptor<WebSocketMessage<?>> message = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(message.capture());
        JsonNode error = MAPPER.readTree(((TextMessage) message.getValue()).getPayload());
        assertThat(error.get("type").asText()).isEqualTo("error");
        assertThat(error.get("error").asText()).isEqualTo("Invalid JSON in speak-stream frame");
        verify(ttsService, never()).synthesize(any(), any());
        verify(session).close(CloseStatus.BAD_DATA);
    }

    @Test
    void stopFrameClosesWithoutCallingTts() throws Exception {
        TtsService ttsService = mock(TtsService.class);
        AudioSpeakStreamWebSocketHandler handler = new AudioSpeakStreamWebSocketHandler(ttsService);
        WebSocketSession session = openSession();

        handler.handleMessage(session, new TextMessage("{\"stop\":true}"));

        verify(ttsService, never()).synthesize(any(), any());
        verify(session).close(CloseStatus.NORMAL);
    }

    private static WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        lenient().when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>(Map.of()));
        return session;
    }
}
