package com.azhukov.agent.tools.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

class ChromiumPlatformTest {

    @Test
    void detectsLinuxAmd64() {
        ChromiumPlatform.Platform platform = ChromiumPlatform.detect();
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) {
            assertThat(platform).isEqualTo(ChromiumPlatform.Platform.LINUX_X64);
        } else if (os.contains("mac") || os.contains("darwin")) {
            String arch = System.getProperty("os.arch").toLowerCase();
            assertThat(platform).isIn(ChromiumPlatform.Platform.MAC_ARM64, ChromiumPlatform.Platform.MAC_X64);
        } else if (os.contains("win")) {
            assertThat(platform).isIn(ChromiumPlatform.Platform.WIN_X64, ChromiumPlatform.Platform.WIN_X86);
        }
        assertThat(platform).isNotEqualTo(ChromiumPlatform.Platform.UNSUPPORTED);
    }

    @Test
    void linuxArchiveNameIsChromeLinuxZip() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.LINUX_X64, "1667635"))
            .isEqualTo("chrome-linux.zip");
    }

    @Test
    void macArchiveNameIsChromeMacZip() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.MAC_ARM64, "123"))
            .isEqualTo("chrome-mac.zip");
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.MAC_X64, "123"))
            .isEqualTo("chrome-mac.zip");
    }

    @Test
    void windowsArchiveNameIsChromeWinZip() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.WIN_X64, "123"))
            .isEqualTo("chrome-win.zip");
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.WIN_X86, "123"))
            .isEqualTo("chrome-win.zip");
    }
}
