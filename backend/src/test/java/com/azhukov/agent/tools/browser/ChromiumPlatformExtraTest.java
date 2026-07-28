package com.azhukov.agent.tools.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChromiumPlatformExtraTest {

    @Test
    @DisplayName("archiveName() for LINUX_X64 returns 'chrome-linux.zip'")
    void archiveNameForLinuxX64() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.LINUX_X64, "rev123"))
            .isEqualTo("chrome-linux.zip");
    }

    @Test
    @DisplayName("archiveName() for MAC_ARM64 returns 'chrome-mac.zip'")
    void archiveNameForMacArm64() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.MAC_ARM64, "rev123"))
            .isEqualTo("chrome-mac.zip");
    }

    @Test
    @DisplayName("archiveName() for WIN_X64 returns 'chrome-win.zip'")
    void archiveNameForWinX64() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.WIN_X64, "rev123"))
            .isEqualTo("chrome-win.zip");
    }

    @Test
    @DisplayName("archiveName() for UNSUPPORTED throws IllegalArgumentException")
    void archiveNameForUnsupportedThrows() {
        assertThatThrownBy(() -> ChromiumPlatform.archiveName(ChromiumPlatform.Platform.UNSUPPORTED, "rev123"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported platform");
    }

    @Test
    @DisplayName("LINUX_X64 has correct snapshotFolder, archiveFolder, executableName")
    void linuxX64PlatformValues() {
        ChromiumPlatform.Platform p = ChromiumPlatform.Platform.LINUX_X64;
        assertThat(p.snapshotFolder()).isEqualTo("Linux_x64");
        assertThat(p.archiveFolder()).isEqualTo("chrome-linux");
        assertThat(p.executableName()).isEqualTo("chrome");
    }

    @Test
    @DisplayName("MAC_ARM64 has correct snapshotFolder, archiveFolder, executableName")
    void macArm64PlatformValues() {
        ChromiumPlatform.Platform p = ChromiumPlatform.Platform.MAC_ARM64;
        assertThat(p.snapshotFolder()).isEqualTo("Mac_Arm");
        assertThat(p.archiveFolder()).isEqualTo("chrome-mac");
        assertThat(p.executableName()).isEqualTo("Chromium.app/Contents/MacOS/Chromium");
    }

    @Test
    @DisplayName("MAC_X64 has correct snapshotFolder, archiveFolder, executableName")
    void macX64PlatformValues() {
        ChromiumPlatform.Platform p = ChromiumPlatform.Platform.MAC_X64;
        assertThat(p.snapshotFolder()).isEqualTo("Mac");
        assertThat(p.archiveFolder()).isEqualTo("chrome-mac");
        assertThat(p.executableName()).isEqualTo("Chromium.app/Contents/MacOS/Chromium");
    }

    @Test
    @DisplayName("WIN_X64 has correct snapshotFolder, archiveFolder, executableName")
    void winX64PlatformValues() {
        ChromiumPlatform.Platform p = ChromiumPlatform.Platform.WIN_X64;
        assertThat(p.snapshotFolder()).isEqualTo("Win_x64");
        assertThat(p.archiveFolder()).isEqualTo("chrome-win");
        assertThat(p.executableName()).isEqualTo("chrome.exe");
    }

    @Test
    @DisplayName("WIN_X86 has correct snapshotFolder, archiveFolder, executableName")
    void winX86PlatformValues() {
        ChromiumPlatform.Platform p = ChromiumPlatform.Platform.WIN_X86;
        assertThat(p.snapshotFolder()).isEqualTo("Win");
        assertThat(p.archiveFolder()).isEqualTo("chrome-win");
        assertThat(p.executableName()).isEqualTo("chrome.exe");
    }

    @Test
    @DisplayName("UNSUPPORTED has null snapshotFolder, archiveFolder, executableName")
    void unsupportedPlatformValues() {
        ChromiumPlatform.Platform p = ChromiumPlatform.Platform.UNSUPPORTED;
        assertThat(p.snapshotFolder()).isNull();
        assertThat(p.archiveFolder()).isNull();
        assertThat(p.executableName()).isNull();
    }

    @Test
    @DisplayName("detect() returns a non-UNSUPPORTED platform on Linux")
    void detectReturnsNonUnsupportedOnLinux() {
        ChromiumPlatform.Platform p = ChromiumPlatform.detect();
        // On the test system (Linux), detect() should return LINUX_X64
        assertThat(p).isNotEqualTo(ChromiumPlatform.Platform.UNSUPPORTED);
    }

    @Test
    @DisplayName("archiveName() for MAC_X64 returns 'chrome-mac.zip'")
    void archiveNameForMacX64() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.MAC_X64, "rev123"))
            .isEqualTo("chrome-mac.zip");
    }

    @Test
    @DisplayName("archiveName() for WIN_X86 returns 'chrome-win.zip'")
    void archiveNameForWinX86() {
        assertThat(ChromiumPlatform.archiveName(ChromiumPlatform.Platform.WIN_X86, "rev123"))
            .isEqualTo("chrome-win.zip");
    }
}