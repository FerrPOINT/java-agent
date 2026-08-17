package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.batch.PhotoBatchDebouncer;
import com.azhukov.agent.bot.batch.TextBatchDebouncer;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.EditCaptureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * c5: Unit tests for {@link UpdateDispatcher} — event-type routing extracted
 * from {@link BotMessageProcessor#accept}. Uses a recording fake for the
 * {@link UpdateDispatcher.Handlers} callback and mocks for the batch debouncers
 * and edit-capture service.
 */
class UpdateDispatcherTest {

    private BotProperties properties;
    private EditCaptureService editCaptureService;
    private TextBatchDebouncer textBatchDebouncer;
    private PhotoBatchDebouncer photoBatchDebouncer;
    private UpdateDispatcher dispatcher;

    /** Records the last handler invocation so tests can assert routing. */
    private static class RecordingHandlers implements UpdateDispatcher.Handlers {
        String lastCall;
        UpdateEvent lastEvent;
        long errorChatId;
        String errorMsg;
        int callbackCount, commandCount, textOrMediaCount, editedCount, editCaptureCount;

        @Override public void handleCallbackQuery(UpdateEvent e) { lastCall = "callback"; lastEvent = e; callbackCount++; }
        @Override public void handleCommand(UpdateEvent e) { lastCall = "command"; lastEvent = e; commandCount++; }
        @Override public void handleTextOrMedia(UpdateEvent e) { lastCall = "textOrMedia"; lastEvent = e; textOrMediaCount++; }
        @Override public void handleEditedMessage(UpdateEvent e) { lastCall = "edited"; lastEvent = e; editedCount++; }
        @Override public void handleEditCapture(UpdateEvent e) { lastCall = "editCapture"; lastEvent = e; editCaptureCount++; }
        @Override public void sendError(long chatId, String message) { lastCall = "error"; errorChatId = chatId; errorMsg = message; }
    }

    private RecordingHandlers handlers;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        editCaptureService = mock(EditCaptureService.class);
        textBatchDebouncer = mock(TextBatchDebouncer.class);
        photoBatchDebouncer = mock(PhotoBatchDebouncer.class);
        dispatcher = new UpdateDispatcher(properties, editCaptureService, textBatchDebouncer, photoBatchDebouncer);
        handlers = new RecordingHandlers();
        // Defaults: no active capture, not buffered
        when(editCaptureService.getCapture(anyLong())).thenReturn(null);
        when(textBatchDebouncer.offer(any())).thenReturn(false);
        when(photoBatchDebouncer.offer(any())).thenReturn(false);
    }

    private UpdateEvent event(UpdateEvent.Type type, long chatId) {
        return new UpdateEvent(1, type, chatId, 200L, "u", "t", null, null, null,
            null, null, null, false, null, null, 10, null, 0);
    }

    private UpdateEvent textEvent(long chatId) {
        return new UpdateEvent(1, UpdateEvent.Type.TEXT, chatId, 200L, "u", "hi",
            null, null, null, null, null, null, false, null, null, 10, null, 0);
    }

    private UpdateEvent commandEvent(long chatId) {
        return new UpdateEvent(1, UpdateEvent.Type.COMMAND, chatId, 200L, "u", "/help",
            null, null, null, null, null, null, true, "help", "", 10, null, 0);
    }

    private UpdateEvent photoEvent(long chatId, String mediaGroupId) {
        return new UpdateEvent(1, UpdateEvent.Type.PHOTO, chatId, 200L, "u", null,
            "cap", "fid", "photo", null, null, null, false, null, null, 10, mediaGroupId, 0);
    }

    @Test
    void nullEvent_isNoop() {
        dispatcher.dispatch(null, handlers);
        assertThat(handlers.lastCall).isNull();
    }

    @Test
    void callbackQuery_routedToHandleCallbackQuery() {
        dispatcher.dispatch(event(UpdateEvent.Type.CALLBACK_QUERY, 100L), handlers);
        assertThat(handlers.lastCall).isEqualTo("callback");
        assertThat(handlers.callbackCount).isEqualTo(1);
        verifyNoInteractions(textBatchDebouncer, photoBatchDebouncer);
    }

    @Test
    void command_routedToHandleCommand() {
        dispatcher.dispatch(commandEvent(100L), handlers);
        assertThat(handlers.lastCall).isEqualTo("command");
        assertThat(handlers.commandCount).isEqualTo(1);
    }

    @Test
    void text_routedToHandleTextOrMedia_whenNotCapturedAndNotBatched() {
        dispatcher.dispatch(textEvent(100L), handlers);
        assertThat(handlers.lastCall).isEqualTo("textOrMedia");
        assertThat(handlers.textOrMediaCount).isEqualTo(1);
    }

    @Test
    void text_routedToEditCapture_whenCaptureActive() {
        when(editCaptureService.getCapture(100L)).thenReturn(mock(EditCaptureService.CaptureContext.class));
        dispatcher.dispatch(textEvent(100L), handlers);
        assertThat(handlers.lastCall).isEqualTo("editCapture");
        assertThat(handlers.editCaptureCount).isEqualTo(1);
        assertThat(handlers.textOrMediaCount).isZero();
    }

    @Test
    void text_buffered_whenOffered() {
        // textBatch is always initialized (non-null), so offerTextBatch is always called.
        when(textBatchDebouncer.offer(any())).thenReturn(true);
        dispatcher.dispatch(textEvent(100L), handlers);
        assertThat(handlers.lastCall).isNull(); // buffered, no handler invoked
        verify(textBatchDebouncer).offer(any());
    }

    @Test
    void text_notBuffered_whenOfferReturnsFalse() {
        // Debouncer declines to buffer → fall through to handleTextOrMedia
        when(textBatchDebouncer.offer(any())).thenReturn(false);
        dispatcher.dispatch(textEvent(100L), handlers);
        assertThat(handlers.lastCall).isEqualTo("textOrMedia");
        verify(textBatchDebouncer).offer(any());
    }

    @Test
    void photo_routedToHandleTextOrMedia_whenNoMediaGroupId() {
        dispatcher.dispatch(photoEvent(100L, null), handlers);
        assertThat(handlers.lastCall).isEqualTo("textOrMedia");
    }

    @Test
    void photo_buffered_whenMediaGroupIdPresentAndOffered() {
        when(photoBatchDebouncer.offer(any())).thenReturn(true);
        dispatcher.dispatch(photoEvent(100L, "group1"), handlers);
        assertThat(handlers.lastCall).isNull(); // buffered
        verify(photoBatchDebouncer).offer(any());
    }

    @Test
    void photo_routedToTextOrMedia_whenMediaGroupIdBlank() {
        dispatcher.dispatch(photoEvent(100L, "  "), handlers);
        assertThat(handlers.lastCall).isEqualTo("textOrMedia");
        verify(photoBatchDebouncer, never()).offer(any());
    }

    @Test
    void mediaTypes_routedToHandleTextOrMedia() {
        for (UpdateEvent.Type t : new UpdateEvent.Type[]{
            UpdateEvent.Type.DOCUMENT, UpdateEvent.Type.VOICE,
            UpdateEvent.Type.STICKER, UpdateEvent.Type.ANIMATION,
            UpdateEvent.Type.LOCATION}) {
            handlers = new RecordingHandlers();
            dispatcher.dispatch(event(t, 100L), handlers);
            assertThat(handlers.lastCall).as("type %s", t).isEqualTo("textOrMedia");
        }
    }

    @Test
    void editedMessage_routedToHandleEditedMessage() {
        dispatcher.dispatch(event(UpdateEvent.Type.EDITED_MESSAGE, 100L), handlers);
        assertThat(handlers.lastCall).isEqualTo("edited");
    }

    @Test
    void unknownEvent_isIgnored() {
        dispatcher.dispatch(event(UpdateEvent.Type.UNKNOWN, 100L), handlers);
        assertThat(handlers.lastCall).isNull();
    }

    @Test
    void exceptionInHandler_invokesSendError() {
        UpdateDispatcher.Handlers throwing = mock(UpdateDispatcher.Handlers.class);
        doThrow(new RuntimeException("boom")).when(throwing).handleCommand(any());
        dispatcher.dispatch(commandEvent(100L), throwing);
        verify(throwing).sendError(eq(100L), contains("error"));
    }
}