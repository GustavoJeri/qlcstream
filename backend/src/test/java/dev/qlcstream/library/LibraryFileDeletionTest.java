package dev.qlcstream.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryFileDeletionTest {
    @TempDir Path temporary;
    void secureProvider() throws IOException {
        try (var stream = Files.newDirectoryStream(temporary)) {
            assumeTrue(stream instanceof SecureDirectoryStream<?> || Files.isRegularFile(Path.of("/app/library-file-delete.py")));
        }
    }
    @Test void deletesOnlyTheSelectedFileAndAllowsMissingRetries() throws Exception {
        secureProvider();
        var root = Files.createDirectory(temporary.resolve("media"));
        var folder = Files.createDirectory(root.resolve("season"));
        var selected = Files.writeString(folder.resolve("ação com espaços.mp4"), "owned");
        var sibling = Files.writeString(folder.resolve("episode2.mp4"), "other owned bytes");
        for (var repeat = 0; repeat < 2; repeat++) {
            try (var operation = LibraryFileDeletion.open(root, "season/ação com espaços.mp4")) { operation.delete(); }
        }
        assertFalse(Files.exists(selected)); assertEquals("other owned bytes", Files.readString(sibling));
        var posixName = Files.writeString(folder.resolve("Film: Part II.mp4"), "owned");
        try (var operation = LibraryFileDeletion.open(root, "season/Film: Part II.mp4")) { operation.delete(); }
        assertFalse(Files.exists(posixName));
        try (var operation = LibraryFileDeletion.open(root, "absent/episode.mp4")) { operation.delete(); }
    }
    @Test void rejectsExternalParentAndLeafLinks() throws Exception {
        secureProvider();
        var root = Files.createDirectory(temporary.resolve("media"));
        var outside = Files.createDirectory(temporary.resolve("outside"));
        var marker = Files.writeString(outside.resolve("owned.mp4"), "external marker");
        Files.createSymbolicLink(root.resolve("linked"), outside);
        Files.createSymbolicLink(root.resolve("leaf.mp4"), marker);
        assertThrows(IOException.class, () -> LibraryFileDeletion.open(root, "linked/owned.mp4"));
        assertThrows(IOException.class, () -> LibraryFileDeletion.open(root, "leaf.mp4"));
        assertEquals("external marker", Files.readString(marker));
    }
    @Test void parentReplacementCannotRedirectTheDeletion() throws Exception {
        secureProvider();
        var root = Files.createDirectory(temporary.resolve("media"));
        var folder = Files.createDirectory(root.resolve("season"));
        Files.writeString(folder.resolve("owned.mp4"), "authorized");
        var outside = Files.createDirectory(temporary.resolve("outside"));
        var marker = Files.writeString(outside.resolve("owned.mp4"), "external marker");
        try (var operation = LibraryFileDeletion.open(root, "season/owned.mp4")) {
            Files.move(folder, root.resolve("pinned"));
            Files.createSymbolicLink(folder, outside);
            try (var stream = Files.newDirectoryStream(root)) {
                if (stream instanceof SecureDirectoryStream<?>) operation.delete();
                else assertThrows(IOException.class, operation::delete);
            }
        }
        assertEquals("external marker", Files.readString(marker));
        try (var stream = Files.newDirectoryStream(root)) {
            assertEquals(!(stream instanceof SecureDirectoryStream<?>), Files.exists(root.resolve("pinned/owned.mp4")));
        }
    }
    @Test void rejectsAbsoluteTraversalEmptyAndDirectoryTargets() throws Exception {
        secureProvider();
        var root = Files.createDirectory(temporary.resolve("media"));
        Files.createDirectory(root.resolve("season"));
        for (var path : new String[]{"", ".", "../owned.mp4", "season/../../owned.mp4", "/owned.mp4",
                "C:/owned.mp4", "\\\\server\\owned.mp4", "season"}) {
            assertThrows(IOException.class, () -> LibraryFileDeletion.open(root, path), path);
        }
        assertTrue(Files.isDirectory(root.resolve("season")));
    }
    @Test void unsupportedProvidersKeepExistingBytes() throws Exception {
        try (var stream = Files.newDirectoryStream(temporary)) {
            assumeTrue(!(stream instanceof SecureDirectoryStream<?>) && !Files.isRegularFile(Path.of("/app/library-file-delete.py")));
        }
        var marker = Files.writeString(temporary.resolve("owned.mp4"), "owned bytes");
        assertThrows(UnsupportedOperationException.class, () -> LibraryFileDeletion.open(temporary, "owned.mp4"));
        assertEquals("owned bytes", Files.readString(marker));
    }
}
