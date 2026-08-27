package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P-10: shared image-retirement policy (Hermes b7544dba01/7ff2fe8bc9/dff84f1890). */
class ImageRetirementPolicyTest {

    private static final String DATA_URI =
        "data:image/png;base64," + "A".repeat(400);

    private Message toolWithImage(String id, int turn) {
        return new Message(Role.TOOL, "shot " + DATA_URI, null, null, id, turn, 1, null);
    }

    @Test
    void newestThreeKept_olderRetired() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            msgs.add(Message.user("q" + i));
            msgs.add(toolWithImage("c" + i, i));
        }
        int pruned = ImageRetirementPolicy.retireStaleToolResultImages(msgs);
        assertThat(pruned).isEqualTo(3);
        // newest 3 intact
        for (int i = 3; i < 6; i++) {
            Message t = msgs.get(i * 2 + 1);
            assertThat(t.content()).contains("data:image/png;base64");
            assertThat(t.imageCount()).isEqualTo(1);
        }
        // older 3 retired
        for (int i = 0; i < 3; i++) {
            Message t = msgs.get(i * 2 + 1);
            assertThat(t.content()).startsWith("[screenshot removed]");
            assertThat(t.content()).doesNotContain("base64");
            assertThat(t.imageCount()).isEqualTo(0);
        }
    }

    @Test
    void userUploadsNeverTouched() {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            msgs.add(Message.userWithImages("photo " + DATA_URI, 2));
        }
        assertThat(ImageRetirementPolicy.retireStaleToolResultImages(msgs)).isEqualTo(0);
        assertThat(msgs.get(0).imageCount()).isEqualTo(2);
    }

    @Test
    void fewerThanKeepNewestAllIntact() {
        List<Message> msgs = new ArrayList<>(List.of(toolWithImage("a", 0), toolWithImage("b", 1)));
        assertThat(ImageRetirementPolicy.retireStaleToolResultImages(msgs)).isEqualTo(0);
    }

    @Test
    void dataUriWithoutImageCountStillRetired() {
        Message m = new Message(Role.TOOL, "x " + DATA_URI + " y", null, null, "c", 0, 0, null);
        List<Message> msgs = new ArrayList<>(List.of(m, toolWithImage("keep", 1)));
        // keep=1: newest kept; the count-less data-URI one retired via content scan
        int pruned = ImageRetirementPolicy.retireStaleToolResultImages(msgs, 1);
        assertThat(pruned).isEqualTo(1);
        assertThat(msgs.get(0).content()).startsWith("[screenshot removed]");
        assertThat(msgs.get(0).content()).contains("1 image");
    }

    @Test
    void keepNewestZeroRetiresAll() {
        List<Message> msgs = new ArrayList<>(List.of(toolWithImage("a", 0), toolWithImage("b", 1)));
        assertThat(ImageRetirementPolicy.retireStaleToolResultImages(msgs, 0)).isEqualTo(2);
    }

    @Test
    void emptyOrNullSafe() {
        assertThat(ImageRetirementPolicy.retireStaleToolResultImages(null)).isZero();
        assertThat(ImageRetirementPolicy.retireStaleToolResultImages(new ArrayList<>())).isZero();
    }

    @Test
    void textOnlyToolResultsUntouched() {
        Message m = Message.toolResult("c", "plain text result", 0);
        List<Message> msgs = new ArrayList<>(List.of(m));
        assertThat(ImageRetirementPolicy.retireStaleToolResultImages(msgs, 0)).isZero();
        assertThat(msgs.get(0).content()).isEqualTo("plain text result");
    }
}
