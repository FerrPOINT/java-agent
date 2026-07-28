package com.azhukov.agent.gateway.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.InstanceOfAssertFactories.BYTE_ARRAY;

/**
 * Comprehensive tests for gateway model objects: enums ({@link Platform}, {@link MessageType})
 * and records ({@link SessionSource}, {@link MessageEvent}, {@link PlatformConfig}, {@link SendResult}).
 */
@DisplayName("Gateway Model Objects")
class GatewayModelObjectsTest {

    // ============================= Platform =============================

    @Nested
    @DisplayName("Platform enum")
    class PlatformTest {

        @Test
        @DisplayName("should contain exactly 4 constants in expected order")
        void shouldContainExactlyFourConstants() {
            assertThat(Platform.values())
                    .containsExactly(Platform.TELEGRAM, Platform.DISCORD, Platform.WEB, Platform.UNKNOWN);
        }

        @ParameterizedTest(name = "valueOf(\"{0}\") should resolve")
        @EnumSource(Platform.class)
        @DisplayName("valueOf should resolve each constant")
        void valueOfShouldResolve(Platform platform) {
            assertThat(Platform.valueOf(platform.name())).isEqualTo(platform);
        }

        @Test
        @DisplayName("TELEGRAM and UNKNOWN should be distinct")
        void telegramAndUnknownShouldBeDistinct() {
            assertThat(Platform.TELEGRAM).isNotEqualTo(Platform.UNKNOWN);
        }
    }

    // ============================= MessageType =============================

    @Nested
    @DisplayName("MessageType enum")
    class MessageTypeTest {

        @Test
        @DisplayName("should contain exactly 5 constants in expected order")
        void shouldContainExactlyFiveConstants() {
            assertThat(MessageType.values())
                    .containsExactly(
                            MessageType.TEXT,
                            MessageType.IMAGE,
                            MessageType.DOCUMENT,
                            MessageType.COMMAND,
                            MessageType.CALLBACK);
        }

        @ParameterizedTest(name = "valueOf(\"{0}\") should resolve")
        @EnumSource(MessageType.class)
        @DisplayName("valueOf should resolve each constant")
        void valueOfShouldResolve(MessageType type) {
            assertThat(MessageType.valueOf(type.name())).isEqualTo(type);
        }
    }

    // ============================= SessionSource =============================

    @Nested
    @DisplayName("SessionSource record")
    class SessionSourceTest {

        @Test
        @DisplayName("should create record and expose all components")
        void shouldCreateAndExposeComponents() {
            SessionSource src = new SessionSource(
                    Platform.TELEGRAM, "chat-42", "user-1", "alice", "Alice Wonder");

            assertThat(src.platform()).isEqualTo(Platform.TELEGRAM);
            assertThat(src.chatId()).isEqualTo("chat-42");
            assertThat(src.userId()).isEqualTo("user-1");
            assertThat(src.username()).isEqualTo("alice");
            assertThat(src.displayName()).isEqualTo("Alice Wonder");
        }

        @Test
        @DisplayName("equals and hashCode should be consistent")
        void equalsAndHashCodeShouldBeConsistent() {
            SessionSource a = new SessionSource(Platform.WEB, "c1", "u1", "bob", "Bob");
            SessionSource b = new SessionSource(Platform.WEB, "c1", "u1", "bob", "Bob");
            SessionSource c = new SessionSource(Platform.WEB, "c2", "u1", "bob", "Bob");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(a).isNotEqualTo(null);
            assertThat(a).isNotEqualTo("not-a-SessionSource");
        }

        @Test
        @DisplayName("should allow null string fields")
        void shouldAllowNullStringFields() {
            SessionSource src = new SessionSource(Platform.UNKNOWN, null, null, null, null);

            assertThat(src.platform()).isEqualTo(Platform.UNKNOWN);
            assertThat(src.chatId()).isNull();
            assertThat(src.userId()).isNull();
            assertThat(src.username()).isNull();
            assertThat(src.displayName()).isNull();
        }

        @Test
        @DisplayName("toString should contain class name and component names")
        void toStringShouldContainClassNameAndComponents() {
            SessionSource src = new SessionSource(Platform.DISCORD, "ch", "u", "nm", "DN");
            String s = src.toString();
            assertThat(s).contains("SessionSource");
            assertThat(s).contains("platform=DISCORD");
            assertThat(s).contains("chatId=ch");
        }
    }

    // ============================= MessageEvent =============================

    @Nested
    @DisplayName("MessageEvent record")
    class MessageEventTest {

        @Test
        @DisplayName("should create record and expose all components")
        void shouldCreateAndExposeComponents() {
            Instant now = Instant.now();
            byte[] data = {1, 2, 3};
            SessionSource source = new SessionSource(Platform.TELEGRAM, "c1", "u1", "alice", "Alice");
            MessageEvent.Attachment att = new MessageEvent.Attachment(
                    "https://example.com/file.png", "image/png", "file.png", data);

            MessageEvent event = new MessageEvent(
                    "evt-1",
                    source,
                    MessageType.TEXT,
                    "Hello",
                    List.of(att),
                    Map.of("key", "value"),
                    now);

            assertThat(event.eventId()).isEqualTo("evt-1");
            assertThat(event.source()).isEqualTo(source);
            assertThat(event.type()).isEqualTo(MessageType.TEXT);
            assertThat(event.text()).isEqualTo("Hello");
            assertThat(event.attachments()).hasSize(1).contains(att);
            assertThat(event.metadata()).containsEntry("key", "value");
            assertThat(event.receivedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("should accept null attachments list")
        void shouldAcceptNullAttachments() {
            MessageEvent event = new MessageEvent(
                    "evt-2", null, MessageType.IMAGE, null, null, null, null);

            assertThat(event.attachments()).isNull();
            assertThat(event.metadata()).isNull();
            assertThat(event.source()).isNull();
            assertThat(event.receivedAt()).isNull();
        }

        @Test
        @DisplayName("equals and hashCode should be consistent")
        void equalsAndHashCodeShouldBeConsistent() {
            SessionSource source = new SessionSource(Platform.WEB, "c", "u", "n", "DN");
            Instant now = Instant.now();
            MessageEvent a = new MessageEvent("e1", source, MessageType.TEXT, "hi", List.of(), Map.of(), now);
            MessageEvent b = new MessageEvent("e1", source, MessageType.TEXT, "hi", List.of(), Map.of(), now);
            MessageEvent c = new MessageEvent("e2", source, MessageType.TEXT, "hi", List.of(), Map.of(), now);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }

        @Test
        @DisplayName("toString should contain class name and key components")
        void toStringShouldContainClassName() {
            MessageEvent event = new MessageEvent(
                    "evt-99", null, MessageType.COMMAND, "/start", List.of(), Map.of(), Instant.now());
            String s = event.toString();
            assertThat(s).contains("MessageEvent");
            assertThat(s).contains("evt-99");
            assertThat(s).contains("COMMAND");
        }
    }

    @Nested
    @DisplayName("MessageEvent.Attachment record")
    class AttachmentTest {

        @Test
        @DisplayName("should create record and expose all components including byte data")
        void shouldCreateAndExposeComponents() {
            byte[] data = {10, 20, 30, 40};
            MessageEvent.Attachment att =
                    new MessageEvent.Attachment("https://example.com/x.pdf", "application/pdf", "x.pdf", data);

            assertThat(att.url()).isEqualTo("https://example.com/x.pdf");
            assertThat(att.mimeType()).isEqualTo("application/pdf");
            assertThat(att.fileName()).isEqualTo("x.pdf");
            assertThat(att.data()).containsExactly(10, 20, 30, 40);
        }

        @Test
        @DisplayName("should accept null data array")
        void shouldAcceptNullData() {
            MessageEvent.Attachment att =
                    new MessageEvent.Attachment(null, null, null, null);

            assertThat(att.url()).isNull();
            assertThat(att.mimeType()).isNull();
            assertThat(att.fileName()).isNull();
            assertThat(att.data()).isNull();
        }

        @Test
        @DisplayName("byte[] equality: records with same content but different array refs are NOT equal")
        void byteEqualityUsesReferenceNotContent() {
            byte[] data1 = {1, 2, 3};
            byte[] data2 = {1, 2, 3}; // same content, different array instance

            MessageEvent.Attachment a = new MessageEvent.Attachment("u", "m", "f", data1);
            MessageEvent.Attachment b = new MessageEvent.Attachment("u", "m", "f", data2);

            // Java records use byte[].equals which is reference equality, so different arrays are NOT equal
            assertThat(a).isNotEqualTo(b);
            // Same reference should be equal
            MessageEvent.Attachment c = new MessageEvent.Attachment("u", "m", "f", data1);
            assertThat(a).isEqualTo(c);
        }

        @Test
        @DisplayName("toString should contain class name and fileName")
        void toStringShouldContainClassName() {
            MessageEvent.Attachment att =
                    new MessageEvent.Attachment("url", "mime", "doc.txt", new byte[]{});
            String s = att.toString();
            assertThat(s).contains("Attachment");
            assertThat(s).contains("doc.txt");
        }
    }

    // ============================= PlatformConfig =============================

    @Nested
    @DisplayName("PlatformConfig record")
    class PlatformConfigTest {

        @Test
        @DisplayName("should create record and expose all components")
        void shouldCreateAndExposeComponents() {
            Map<String, String> secrets = Map.of("token", "secret123");
            Map<String, String> options = Map.of("timeout", "5000");

            PlatformConfig config = new PlatformConfig(Platform.TELEGRAM, true, secrets, options);

            assertThat(config.platform()).isEqualTo(Platform.TELEGRAM);
            assertThat(config.enabled()).isTrue();
            assertThat(config.secrets()).containsEntry("token", "secret123");
            assertThat(config.options()).containsEntry("timeout", "5000");
        }

        @Test
        @DisplayName("equals and hashCode should be consistent")
        void equalsAndHashCodeShouldBeConsistent() {
            Map<String, String> secrets = Map.of("k", "v");
            Map<String, String> options = Map.of("o", "1");
            PlatformConfig a = new PlatformConfig(Platform.DISCORD, false, secrets, options);
            PlatformConfig b = new PlatformConfig(Platform.DISCORD, false, secrets, options);
            PlatformConfig c = new PlatformConfig(Platform.DISCORD, true, secrets, options);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
        }

        @Test
        @DisplayName("should accept null maps")
        void shouldAcceptNullMaps() {
            PlatformConfig config = new PlatformConfig(Platform.WEB, false, null, null);

            assertThat(config.secrets()).isNull();
            assertThat(config.options()).isNull();
            assertThat(config.enabled()).isFalse();
        }

        @Test
        @DisplayName("disabled config with empty maps should have correct state")
        void disabledConfigWithEmptyMaps() {
            PlatformConfig config = new PlatformConfig(Platform.UNKNOWN, false, Map.of(), Map.of());

            assertThat(config.enabled()).isFalse();
            assertThat(config.secrets()).isEmpty();
            assertThat(config.options()).isEmpty();
        }

        @Test
        @DisplayName("toString should contain class name and platform")
        void toStringShouldContainClassName() {
            PlatformConfig config = new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of());
            String s = config.toString();
            assertThat(s).contains("PlatformConfig");
            assertThat(s).contains("TELEGRAM");
        }
    }

    // ============================= SendResult =============================

    @Nested
    @DisplayName("SendResult record")
    class SendResultTest {

        @Test
        @DisplayName("should create successful result and expose all components")
        void shouldCreateSuccessfulResult() {
            SendResult result = new SendResult(true, "msg-001", null);

            assertThat(result.success()).isTrue();
            assertThat(result.messageId()).isEqualTo("msg-001");
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("should create failed result with error message")
        void shouldCreateFailedResult() {
            SendResult result = new SendResult(false, null, "Network timeout");

            assertThat(result.success()).isFalse();
            assertThat(result.messageId()).isNull();
            assertThat(result.error()).isEqualTo("Network timeout");
        }

        @Test
        @DisplayName("equals and hashCode should be consistent")
        void equalsAndHashCodeShouldBeConsistent() {
            SendResult a = new SendResult(true, "m1", null);
            SendResult b = new SendResult(true, "m1", null);
            SendResult c = new SendResult(false, "m1", null);
            SendResult d = new SendResult(true, "m2", null);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(a).isNotEqualTo(d);
            assertThat(a).isNotEqualTo(null);
            assertThat(a).isNotEqualTo("not a SendResult");
        }

        @Test
        @DisplayName("toString should contain class name and key fields")
        void toStringShouldContainClassName() {
            SendResult result = new SendResult(false, null, "error-msg");
            String s = result.toString();
            assertThat(s).contains("SendResult");
            assertThat(s).contains("error-msg");
            assertThat(s).contains("success=false");
        }

        @Test
        @DisplayName("should accept all-null fields except success")
        void shouldAcceptAllNullFields() {
            SendResult result = new SendResult(false, null, null);

            assertThat(result.success()).isFalse();
            assertThat(result.messageId()).isNull();
            assertThat(result.error()).isNull();
        }
    }
}