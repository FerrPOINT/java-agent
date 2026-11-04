package com.azhukov.agent.bot.core;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
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

    static byte[] read(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        List<DirectoryStream<Path>> opened = new ArrayList<>();
        try {
            DirectoryStream<Path> root = Files.newDirectoryStream(absolute.getRoot());
            opened.add(root);
            if (!(root instanceof SecureDirectoryStream<Path> current)) {
                throw new IOException("Secure directory access unavailable");
            }
            if (absolute.getNameCount() == 0) throw new IOException("Not a file");
            for (int i = 0; i < absolute.getNameCount() - 1; i++) {
                current = current.newDirectoryStream(absolute.getName(i), LinkOption.NOFOLLOW_LINKS);
                opened.add(current);
            }
            try (var channel = current.newByteChannel(absolute.getFileName(),
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 var input = Channels.newInputStream(channel)) {
                return input.readAllBytes();
            }
        } finally {
            for (int i = opened.size() - 1; i >= 0; i--) opened.get(i).close();
        }
    }
}
