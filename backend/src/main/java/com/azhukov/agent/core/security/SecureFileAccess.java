package com.azhukov.agent.core.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Descriptor-relative Linux file access for paths already authorized by the caller. */
public final class SecureFileAccess {
    private static final int AT_FDCWD = -100;
    private static final int O_NONBLOCK = 00004000;
    private static final int O_CLOEXEC = 02000000;
    private static final int RENAME_NOREPLACE = 1;

    private SecureFileAccess() {}

    public static Directory openDirectory(Path path) throws IOException {
        return openDirectory(path, false);
    }

    public static Directory openOrCreateDirectory(Path path) throws IOException {
        return openDirectory(path, true);
    }

    private static Directory openDirectory(Path path, boolean create) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IOException("Expected an absolute normalized directory path");
        }
        var linux = Linux.current();
        return openDirectories(linux.open(AT_FDCWD, "/", linux.directory | linux.noFollow | O_CLOEXEC),
            path, create);
    }

    private static Directory openDirectories(Descriptor current, Path path, boolean create) throws IOException {
        var linux = current.linux;
        int flags = linux.directory | linux.noFollow | O_CLOEXEC;
        try {
            for (Path component : path) {
                Descriptor next;
                String name = Directory.componentName(component);
                try {
                    next = linux.open(current.fd, name, flags);
                } catch (NoSuchFileException missing) {
                    if (!create) throw missing;
                    linux.mkdir(current.fd, name);
                    next = linux.open(current.fd, name, flags);
                }
                var previous = current;
                current = next;
                previous.close();
            }
            var entries = Files.newDirectoryStream(current.procPath());
            if (!(entries instanceof SecureDirectoryStream<Path> secure)) {
                entries.close();
                throw new IOException("The filesystem does not support secure directory access");
            }
            return new Directory(current, secure);
        } catch (IOException | RuntimeException failure) {
            current.close();
            throw failure;
        }
    }

    public static SeekableByteChannel openRead(Path path) throws IOException {
        try (var directory = openDirectory(path.getParent())) {
            return directory.openRead(path.getFileName());
        }
    }

    public static final class Directory implements AutoCloseable {
        private final Descriptor descriptor;
        private final SecureDirectoryStream<Path> entries;

        private Directory(Descriptor descriptor, SecureDirectoryStream<Path> entries) {
            this.descriptor = descriptor;
            this.entries = entries;
        }

        public SecureDirectoryStream<Path> entries() {
            return entries;
        }

        public SeekableByteChannel openRead(Path name) throws IOException {
            var linux = descriptor.linux;
            try (var file = linux.open(descriptor.fd, componentName(name), O_NONBLOCK | O_CLOEXEC | linux.noFollow)) {
                Path pinned = file.procPath();
                if (!Files.readAttributes(pinned, BasicFileAttributes.class).isRegularFile()) {
                    throw new IOException("Not a regular file: " + name);
                }
                // The open descriptor pins the inode until Java has opened its own channel.
                return Files.newByteChannel(pinned);
            }
        }

        public Directory openDirectory(Path path, boolean create) throws IOException {
            if (path.isAbsolute() || path.toString().isEmpty()) {
                throw new IOException("Expected a relative directory path");
            }
            var linux = descriptor.linux;
            return openDirectories(linux.open(descriptor.fd, ".", linux.directory | linux.noFollow | O_CLOEXEC),
                path, create);
        }

        public BasicFileAttributes attributes(Path name) throws IOException {
            componentName(name);
            return entries.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
        }

        public void replaceFile(Path name, byte[] bytes) throws IOException {
            replaceFile(name, bytes, () -> {});
        }

        public void replaceFile(Path name, byte[] bytes, BeforeReplace check) throws IOException {
            componentName(name);
            var permissions = PosixFilePermissions.fromString("rw-------");
            try {
                var original = entries.getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
                if (!original.isRegularFile()) throw new IOException("Not a regular file: " + name);
                permissions = original.permissions();
            } catch (NoSuchFileException missing) {
                // A new file starts private.
            }

            Path temp = Path.of(".java-agent-write-" + UUID.randomUUID() + ".tmp");
            boolean created = false;
            try {
                try (var channel = entries.newByteChannel(temp,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                    created = true;
                    var buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                }
                entries.getFileAttributeView(temp, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .setPermissions(permissions);
                check.run();
                entries.move(temp, entries, name);
            } catch (IOException | RuntimeException failure) {
                if (created) {
                    try {
                        entries.deleteFile(temp);
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }

        @FunctionalInterface
        public interface BeforeReplace {
            void run() throws IOException;
        }

        public void moveWithoutReplace(Path name, Directory target, Path targetName) throws IOException {
            descriptor.linux.moveWithoutReplace(descriptor.fd, componentName(name), target.descriptor.fd,
                componentName(targetName));
        }

        private static String componentName(Path name) throws IOException {
            if (name == null || name.isAbsolute() || name.getNameCount() != 1
                    || name.toString().isEmpty() || ".".equals(name.toString()) || "..".equals(name.toString())) {
                throw new IOException("Expected a file name relative to the open directory");
            }
            return name.toString();
        }

        @Override
        public void close() throws IOException {
            try {
                entries.close();
            } finally {
                descriptor.close();
            }
        }
    }

    private record Descriptor(Linux linux, int fd) implements AutoCloseable {
        Path procPath() {
            return Path.of("/proc/self/fd", Integer.toString(fd));
        }

        @Override
        public void close() {
            linux.close(fd);
        }
    }

    private static final class Linux {
        private static final Linux INSTANCE = detect();
        private final int directory;
        private final int noFollow;
        private final MethodHandle open;
        private final MethodHandle mkdir;
        private final MethodHandle renameNoReplace;
        private final MethodHandle close;
        private final java.lang.foreign.StructLayout callState = Linker.Option.captureStateLayout();
        private final long errno = callState.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));

        private Linux(int directory, int noFollow) {
            this.directory = directory;
            this.noFollow = noFollow;
            var linker = Linker.nativeLinker();
            var libc = linker.defaultLookup();
            open = linker.downcallHandle(libc.find("openat").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
                Linker.Option.firstVariadicArg(3), Linker.Option.captureCallState("errno"));
            mkdir = linker.downcallHandle(libc.find("mkdirat").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
            renameNoReplace = libc.find("renameat2").map(symbol -> linker.downcallHandle(symbol,
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
                Linker.Option.captureCallState("errno"))).orElse(null);
            close = linker.downcallHandle(libc.find("close").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        }

        private static Linux detect() {
            if (!"Linux".equals(System.getProperty("os.name"))) return null;
            return switch (System.getProperty("os.arch")) {
                case "amd64", "x86_64" -> new Linux(00200000, 00400000);
                case "aarch64" -> new Linux(00040000, 00100000);
                default -> null;
            };
        }

        static Linux current() throws IOException {
            if (INSTANCE == null) throw new IOException("Secure file access requires Linux on x86-64 or AArch64");
            return INSTANCE;
        }

        Descriptor open(int parent, String name, int flags) throws IOException {
            try (var arena = Arena.ofConfined()) {
                var state = arena.allocate(callState);
                int fd = (int) open.invokeExact(state, parent, arena.allocateFrom(name), flags);
                if (fd >= 0) return new Descriptor(this, fd);
                if (state.get(JAVA_INT, errno) == 2) throw new NoSuchFileException(name);
                throw new IOException("Could not safely open " + name);
            } catch (IOException | RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IOException("Secure file open failed", failure);
            }
        }

        void mkdir(int parent, String name) throws IOException {
            try (var arena = Arena.ofConfined()) {
                // The following openat validates the result, including concurrent creation.
                int ignored = (int) mkdir.invokeExact(parent, arena.allocateFrom(name), 0777);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IOException("Secure directory creation failed", failure);
            }
        }

        void moveWithoutReplace(int sourceFd, String source, int targetFd, String target) throws IOException {
            if (renameNoReplace == null) throw new IOException("Atomic move without replacement is unavailable");
            try (var arena = Arena.ofConfined()) {
                var state = arena.allocate(callState);
                int result = (int) renameNoReplace.invokeExact(state, sourceFd, arena.allocateFrom(source),
                    targetFd, arena.allocateFrom(target), RENAME_NOREPLACE);
                if (result == 0) return;
                if (state.get(JAVA_INT, errno) == 17) throw new FileAlreadyExistsException(target);
                throw new IOException("Could not safely move " + source + " to " + target);
            } catch (IOException | RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IOException("Secure move failed", failure);
            }
        }

        void close(int fd) {
            try {
                int ignored = (int) close.invokeExact(fd);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Secure descriptor close failed", failure);
            }
        }
    }
}
