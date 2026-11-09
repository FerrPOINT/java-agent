package com.azhukov.agent.core.security;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.TimeUnit;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.Set;

/** Opens an authorized absolute path without following replacements by symlink. */
public final class SecureFileAccess {
    private SecureFileAccess() {}

    public static SecureDirectoryStream<Path> openDirectory(Path absolutePath) throws IOException {
        if (!absolutePath.isAbsolute() || !absolutePath.equals(absolutePath.normalize())) {
            throw new IOException("Expected an absolute normalized directory path");
        }
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

    public static SecureDirectoryStream<Path> openOrCreateDirectory(Path path) throws IOException {
        try {
            return openDirectory(path);
        } catch (NoSuchFileException missing) {
            // SecureDirectoryStream has no mkdir operation. Python is already a runtime
            // dependency and exposes mkdirat via dir_fd without resolving pathnames twice.
            var command = new ProcessBuilder("/usr/bin/python3", "-I", "-c", """
                import os, sys
                flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
                parent = os.open("/", flags)
                for name in sys.argv[1].split("/")[1:]:
                    try:
                        child = os.open(name, flags, dir_fd=parent)
                    except FileNotFoundError:
                        try:
                            os.mkdir(name, dir_fd=parent)
                        except FileExistsError:
                            pass
                        child = os.open(name, flags, dir_fd=parent)
                    os.close(parent)
                    parent = child
                """, path.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            command.environment().clear();
            var child = command.start();
            try {
                if (!child.waitFor(5, TimeUnit.SECONDS) || child.exitValue() != 0) {
                    throw new IOException("Could not create directories safely: " + path);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Directory creation interrupted", interrupted);
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
            return openDirectory(path);
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
