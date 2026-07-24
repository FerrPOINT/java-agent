package com.azhukov.agent.tools.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public final class ChromiumPlatform {

    private static final Logger log = LoggerFactory.getLogger(ChromiumPlatform.class);

    private ChromiumPlatform() {}

    public enum Platform {
        LINUX_X64("Linux_x64", "chrome-linux", "chrome"),
        MAC_ARM64("Mac_Arm", "chrome-mac", "Chromium.app/Contents/MacOS/Chromium"),
        MAC_X64("Mac", "chrome-mac", "Chromium.app/Contents/MacOS/Chromium"),
        WIN_X64("Win_x64", "chrome-win", "chrome.exe"),
        WIN_X86("Win", "chrome-win", "chrome.exe"),
        UNSUPPORTED(null, null, null);

        private final String snapshotFolder;
        private final String archiveFolder;
        private final String executableName;

        Platform(String snapshotFolder, String archiveFolder, String executableName) {
            this.snapshotFolder = snapshotFolder;
            this.archiveFolder = archiveFolder;
            this.executableName = executableName;
        }

        public String snapshotFolder() { return snapshotFolder; }
        public String archiveFolder() { return archiveFolder; }
        public String executableName() { return executableName; }
    }

    public static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        log.info("Detecting platform: os={}, arch={}", os, arch);
        if (os.contains("linux")) {
            return Platform.LINUX_X64;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arch.contains("aarch64") || arch.contains("arm64") ? Platform.MAC_ARM64 : Platform.MAC_X64;
        }
        if (os.contains("win")) {
            return arch.contains("64") ? Platform.WIN_X64 : Platform.WIN_X86;
        }
        return Platform.UNSUPPORTED;
    }

    public static String archiveName(Platform platform, String revision) {
        return switch (platform) {
            case LINUX_X64 -> "chrome-linux.zip";
            case MAC_ARM64, MAC_X64 -> "chrome-mac.zip";
            case WIN_X64, WIN_X86 -> "chrome-win.zip";
            case UNSUPPORTED -> throw new IllegalArgumentException("Unsupported platform: " + platform);
        };
    }
}
