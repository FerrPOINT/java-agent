package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromiumPlatformTest {

    @Test
    void detectsLinux() {
        ChromiumPlatform.Platform p = ChromiumPlatform.Platform.LINUX_X64;
        assertThat(p.snapshotFolder()).isEqualTo("Linux_x64");
        assertThat(p.executableName()).isEqualTo("chrome");
    }

    @Test
    void archiveNameForLinux() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.LINUX_X64, "r1"))
            .isEqualTo("chrome-linux.zip");
    }

    @Test
    void archiveNameForMacArm() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.MAC_ARM64, "r1"))
            .isEqualTo("chrome-mac.zip");
    }

    @Test
    void detectDoesNotReturnUnsupportedOnLinux() {
        // We run on linux; detect should return LINUX_X64 or at least not unsupported
        assertThat(ChromiumPlatform.detect()).isNotEqualTo(ChromiumPlatform.Platform.UNSUPPORTED);
    }
}
