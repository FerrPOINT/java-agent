package com.azhukov.agent.core.security;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.Set;

/** Opens an authorized absolute path without following replacements by symlink. */
public final class SecureFileAccess {
    private SecureFileAccess() {}

    public static SecureDirectoryStream<Path> openDirectory(Path absolutePath) throws IOException {
        var root = Files.newDirectoryStream(absolutePath.getRoot());
        if (!(root instanceof SecureDirectoryStream<Path> secureRoot)) {
            root.close();
            throw new IOException("The filesystem does not support secure directory access");
        }
        var current = secureRoot;
        try {
            for (Path component : absolutePath) {
                var next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                var previous = current;
                current = next;
                previous.close();
            }
            return current;
        } catch (IOException | RuntimeException error) {
            try {
                current.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    public static SeekableByteChannel openRead(Path authorizedPath) throws IOException {
        Path name = authorizedPath.getFileName();
        if (name == null) {
            throw new IOException("Not a regular file");
        }
        try (var directory = openDirectory(authorizedPath.getParent())) {
            var attributes = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.readAttributes().isRegularFile()) {
                throw new IOException("Not a regular file");
            }
            return directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        }
    }
}
