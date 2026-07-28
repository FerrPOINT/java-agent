package com.azhukov.agent.tools.browser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromiumPlatformTest {

    @Test
    void detectsLinux() {
        ChromiumPlatform.Platform p = ChromiumPlatform.detect();
        assertThat(p).isEqualTo(ChromiumPlatform.Platform.LINUX_X64);
    }

    @Test
    void archiveNameForPlatforms() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.LINUX_X64, "123")).isEqualTo("chrome-linux.zip");
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.MAC_ARM64, "123")).isEqualTo("chrome-mac.zip");
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.WIN_X64, "123")).isEqualTo("chrome-win.zip");
    }

    @Test
    void archiveNameThrowsForUnsupported() {
        assertThatThrownBy(() -> ChromiumPlatform.archiveName(ChromiumPlatform.Platform.UNSUPPORTED, "123"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformFolders() {
        assertThat(ChromiumPlatform.Platform.LINUX_X64.snapshotFolder()).isEqualTo("Linux_x64");
        assertThat(ChromiumPlatform.Platform.MAC_ARM64.archiveFolder()).isEqualTo("chrome-mac");
        assertThat(ChromiumPlatform.Platform.WIN_X64.executableName()).isEqualTo("chrome.exe");
    }
}
