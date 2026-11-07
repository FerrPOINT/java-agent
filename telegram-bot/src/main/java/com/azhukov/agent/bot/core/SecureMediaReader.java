package com.azhukov.agent.bot.core;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Reads files relative to the directory that the bot opened at startup. */
final class SecureMediaReader implements AutoCloseable {
    private final Path path;
    private final SecureDirectoryStream<Path> directory;
    private final Object fileKey;

    private SecureMediaReader(Path path, SecureDirectoryStream<Path> directory, Object fileKey) {
        this.path = path;
        this.directory = directory;
        this.fileKey = fileKey;
    }

    static SecureMediaReader open(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        try (var stream = Files.newDirectoryStream(absolute.getRoot())) {
            if (!(stream instanceof SecureDirectoryStream<Path> root)) {
                throw new IOException("Secure directory streams unavailable");
            }
            var directory = openDirectory(root, absolute, 0);
            try {
                var key = directory.getFileAttributeView(BasicFileAttributeView.class)
                    .readAttributes().fileKey();
                if (key == null) throw new IOException("Media root identity unavailable");
                return new SecureMediaReader(absolute, directory, key);
            } catch (IOException | RuntimeException e) {
                directory.close();
                throw e;
            }
        }
    }

    private static SecureDirectoryStream<Path> openDirectory(
            SecureDirectoryStream<Path> parent, Path path, int index) throws IOException {
        if (index == path.getNameCount()) {
            return parent.newDirectoryStream(Path.of("."), LinkOption.NOFOLLOW_LINKS);
        }
        try (var child = parent.newDirectoryStream(path.getName(index), LinkOption.NOFOLLOW_LINKS)) {
            return openDirectory(child, path, index + 1);
        }
    }

    byte[] read(Path absolute) throws IOException {
        if (!absolute.startsWith(path) || absolute.equals(path)) {
            throw new IOException("Path outside the pinned media directory");
        }
        var current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!current.isDirectory() || !fileKey.equals(current.fileKey())) {
            throw new IOException("Media root was replaced");
        }
        return readFile(directory, path.relativize(absolute), 0);
    }

    private static byte[] readFile(SecureDirectoryStream<Path> parent, Path path, int index) throws IOException {
        Path name = path.getName(index);
        if (index < path.getNameCount() - 1) {
            try (var child = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                return readFile(child, path, index + 1);
            }
        }
        var attributes = parent.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
            .readAttributes();
        if (!attributes.isRegularFile()) throw new IOException("Media path is not a regular file");
        try (var channel = parent.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            return input.readAllBytes();
        }
    }

    @Override
    public void close() throws IOException {
        directory.close();
    }
}
