package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EditCaptureService} (P35).
 */
class EditCaptureServiceTest {

    @Test
    void startCapture_andGetCapture_returnsContext() {
        EditCaptureService service = new EditCaptureService();
        long chatId = 100L;
        EditCaptureService.CaptureContext ctx =
            new EditCaptureService.CaptureContext(42, System.currentTimeMillis());

        service.startCapture(chatId, ctx);

        EditCaptureService.CaptureContext retrieved = service.getCapture(chatId);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.approvalId()).isEqualTo(42);
        assertThat(retrieved.startedAt()).isEqualTo(ctx.startedAt());
    }

    @Test
    void getCapture_noCapture_returnsNull() {
        EditCaptureService service = new EditCaptureService();
        assertThat(service.getCapture(999L)).isNull();
    }

    @Test
    void endCapture_removesActiveCapture() {
        EditCaptureService service = new EditCaptureService();
        long chatId = 200L;
        EditCaptureService.CaptureContext ctx =
            new EditCaptureService.CaptureContext(7, System.currentTimeMillis());

        service.startCapture(chatId, ctx);
        assertThat(service.getCapture(chatId)).isNotNull();

        service.endCapture(chatId);

        assertThat(service.getCapture(chatId)).isNull();
    }

    @Test
    void endCapture_noActiveCapture_noError() {
        EditCaptureService service = new EditCaptureService();
        // Should not throw even when no capture is active
        service.endCapture(123L);
        assertThat(service.getCapture(123L)).isNull();
    }

    @Test
    void startCapture_overwritesPreviousCapture() {
        EditCaptureService service = new EditCaptureService();
        long chatId = 300L;
        EditCaptureService.CaptureContext ctx1 =
            new EditCaptureService.CaptureContext(1, 1000L);
        EditCaptureService.CaptureContext ctx2 =
            new EditCaptureService.CaptureContext(2, 2000L);

        service.startCapture(chatId, ctx1);
        service.startCapture(chatId, ctx2);

        EditCaptureService.CaptureContext retrieved = service.getCapture(chatId);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.approvalId()).isEqualTo(2);
        assertThat(retrieved.startedAt()).isEqualTo(2000L);
    }

    @Test
    void captures_areIsolatedPerChat() {
        EditCaptureService service = new EditCaptureService();
        long chat1 = 10L;
        long chat2 = 20L;

        service.startCapture(chat1, new EditCaptureService.CaptureContext(11, 1000L));
        service.startCapture(chat2, new EditCaptureService.CaptureContext(22, 2000L));

        assertThat(service.getCapture(chat1).approvalId()).isEqualTo(11);
        assertThat(service.getCapture(chat2).approvalId()).isEqualTo(22);

        service.endCapture(chat1);
        assertThat(service.getCapture(chat1)).isNull();
        assertThat(service.getCapture(chat2)).isNotNull();
        assertThat(service.getCapture(chat2).approvalId()).isEqualTo(22);
    }

    @Test
    void captureContext_record_properties() {
        EditCaptureService.CaptureContext ctx =
            new EditCaptureService.CaptureContext(99, 1234567890L);

        assertThat(ctx.approvalId()).isEqualTo(99);
        assertThat(ctx.startedAt()).isEqualTo(1234567890L);
        // Record equality
        assertThat(ctx).isEqualTo(new EditCaptureService.CaptureContext(99, 1234567890L));
        assertThat(ctx).isNotEqualTo(new EditCaptureService.CaptureContext(98, 1234567890L));
    }
}