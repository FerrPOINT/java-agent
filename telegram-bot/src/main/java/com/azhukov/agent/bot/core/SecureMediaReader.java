package com.azhukov.agent.bot.core;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Opens media without following symlinks in any path component. */
final class SecureMediaReader {
    private SecureMediaReader() {}

    static byte[] read(SecureDirectoryStream<Path> root, Path relative) throws IOException {
        if (relative.isAbsolute() || relative.toString().isEmpty()) throw new IOException("Invalid media path");
        List<DirectoryStream<Path>> opened = new ArrayList<>();
        try {
            SecureDirectoryStream<Path> current = root;
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                current = current.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
                opened.add(current);
            }
            try (var channel = current.newByteChannel(relative.getFileName(),
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 var input = Channels.newInputStream(channel)) {
                return input.readAllBytes();
            }
        } finally {
            for (int i = opened.size() - 1; i >= 0; i--) opened.get(i).close();
        }
    }
}
