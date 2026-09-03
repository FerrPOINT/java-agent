package com.azhukov.agent.tools.browser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromiumPlatformTest {

    private static ChromiumPlatform.Platform expectedCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("linux")) {
            return ChromiumPlatform.Platform.LINUX_X64;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arch.contains("aarch64") || arch.contains("arm64")
                ? ChromiumPlatform.Platform.MAC_ARM64
                : ChromiumPlatform.Platform.MAC_X64;
        }
        if (os.contains("win")) {
            return arch.contains("64")
                ? ChromiumPlatform.Platform.WIN_X64
                : ChromiumPlatform.Platform.WIN_X86;
        }
        return ChromiumPlatform.Platform.UNSUPPORTED;
    }

    @Test
    void detectsCurrentPlatform() {
        ChromiumPlatform.Platform p = ChromiumPlatform.detect();
        assertThat(p).isEqualTo(expectedCurrentPlatform());
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
