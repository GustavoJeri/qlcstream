package dev.qlcstream.system;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import dev.qlcstream.library.LocalStorageSettings;
import dev.qlcstream.library.StoragePaths;

@Service
class StorageDiagnostics {
    private final Path root;
    private final LocalStorageSettings settings;
    private final StorageEngineProbe engine;

    StorageDiagnostics(@Value("${qlc-stream.storage.media-root:/data}") String root,
            LocalStorageSettings settings, StorageEngineProbe engine) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.settings = settings;
        this.engine = engine;
    }

    synchronized Result check() {
        String backend;
        try { backend = checkBackend(settings.downloadDirectory()); }
        catch (RuntimeException unavailable) { backend = "configuration_unavailable"; }
        String mapping = engine.mapping();
        var status = !backend.equals("read_write_verified") || mapping.equals("mapping_incompatible") ? "blocked" : "inconclusive";
        return new Result(status, backend, mapping, false, Instant.now());
    }

    String checkBackend(String configured) {
        try {
            var relative = StoragePaths.relative(configured);
            var realRoot = root.toRealPath();
            if (!Files.isDirectory(realRoot)) return "not_directory";
            var destination = realRoot.resolve(relative).normalize();
            if (!destination.startsWith(realRoot)) return "outside_root";
            // Resolve existing links before any write. Never create missing directories.
            var realDestination = destination.toRealPath();
            if (!realDestination.startsWith(realRoot)) return "outside_root";
            if (!Files.isDirectory(realDestination)) return "not_directory";
            if (!Files.isReadable(realRoot) || !Files.isExecutable(realRoot)
                    || !Files.isReadable(realDestination) || !Files.isWritable(realDestination)
                    || !Files.isExecutable(realDestination)) return "permission_denied";
            probe(realDestination);
            return "read_write_verified";
        } catch (InvalidPathException invalid) { return "outside_root"; }
        catch (IllegalArgumentException invalid) { return "outside_root"; }
        catch (NoSuchFileException missing) { return "missing_directory"; }
        catch (AccessDeniedException | SecurityException denied) { return "permission_denied"; }
        catch (IOException failure) { return "io_error"; }
    }

    void probe(Path directory) throws IOException {
        var name = Path.of(".qlc-storage-check-" + UUID.randomUUID());
        var payload = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // SecureDirectoryStream pins the directory on Linux even if a parent is renamed.
        try (var stream = Files.newDirectoryStream(directory)) {
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                var options = Set.<OpenOption>of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                try (var file = secure.newByteChannel(name, options)) {
                    try { verify(file, payload); }
                    finally { secure.deleteFile(name); }
                }
            } else {
                // DELETE_ON_CLOSE binds cleanup to our exclusive handle; no name-based
                // deletion of an unrelated file, including on Windows and musl providers.
                var options = Set.<OpenOption>of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE, LinkOption.NOFOLLOW_LINKS);
                try (var file = Files.newByteChannel(directory.resolve(name), options)) {
                    verify(file, payload);
                }
            }
        }
    }

    private static void verify(java.nio.channels.SeekableByteChannel file, byte[] payload) throws IOException {
        var written = ByteBuffer.wrap(payload);
        while (written.hasRemaining()) file.write(written);
        file.position(0);
        var read = ByteBuffer.allocate(payload.length);
        while (read.hasRemaining()) if (file.read(read) < 0) throw new IOException("Incomplete probe");
        if (!java.util.Arrays.equals(payload, read.array())) throw new IOException("Probe mismatch");
    }

    record Result(String status, String backend, String mapping, boolean sharedDirectoryVerified, Instant checkedAt) {}
}
