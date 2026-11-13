package dev.qlcstream.system;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.qlcstream.library.LocalStorageSettings;
import dev.qlcstream.library.StoragePaths;

class StorageDiagnosticsTest {
    @TempDir Path root;

    StorageDiagnostics diagnostics(String directory, String mapping) {
        var settings = mock(LocalStorageSettings.class);
        when(settings.downloadDirectory()).thenReturn(directory);
        var engine = mock(StorageEngineProbe.class);
        when(engine.mapping()).thenReturn(mapping);
        return new StorageDiagnostics(root.toString(), settings, engine);
    }

    @Test void provesReadWriteAndOnlyRemovesOwnProbe() throws Exception {
        var directory = Files.createDirectory(root.resolve("incoming"));
        var media = Files.writeString(directory.resolve("previous.mp4"), "previous content");
        var unrelated = Files.writeString(directory.resolve(".qlc-storage-check-existing"), "keep");
        var result = diagnostics("incoming", "compatible_unproven").check();
        assertEquals("read_write_verified", result.backend());
        assertEquals("inconclusive", result.status());
        assertFalse(result.sharedDirectoryVerified());
        assertEquals("previous content", Files.readString(media));
        assertEquals("keep", Files.readString(unrelated));
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }

    @Test void missingFolderIsBlockedWithoutCreatingAnything() throws Exception {
        assertEquals("missing_directory", diagnostics("missing", "compatible_unproven").check().backend());
        assertEquals("blocked", diagnostics("missing", "compatible_unproven").check().status());
        assertFalse(Files.exists(root.resolve("missing")));
        var absentRoot = new StorageDiagnostics(root.resolve("absent").toString(), null, null);
        assertEquals("missing_directory", absentRoot.checkBackend("incoming"));
    }

    @Test void rejectsExternalPortableAndEmptyPaths() {
        for (var path : new String[]{"../outside", "/tmp/outside", "C:/private", "C:private", "\\\\host\\share", ".", "a/..", "a/../../outside", ""}) {
            assertThrows(IllegalArgumentException.class, () -> StoragePaths.relative(path), path);
            assertEquals("outside_root", diagnostics(path, "compatible_unproven").check().backend(), path);
        }
        assertEquals("movies/4k", StoragePaths.relative("movies\\4k").toString().replace('\\', '/'));
    }

    @Test @EnabledOnOs(OS.LINUX) void rejectsSymlinkEscapeBeforeWriting() throws Exception {
        var outside = Files.createTempDirectory(root.getParent(), "storage-outside-");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
            assertEquals("outside_root", diagnostics("escape", "compatible_unproven").check().backend());
            try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        } finally { Files.delete(outside); }
    }

    @Test void nonDirectoryIsBlocked() throws Exception {
        Files.writeString(root.resolve("file"), "keep");
        assertEquals("not_directory", diagnostics("file", "compatible_unproven").check().backend());
    }

    @Test void permissionFailureNeverBecomesReady() throws Exception {
        Files.createDirectory(root.resolve("incoming"));
        var settings = mock(LocalStorageSettings.class);
        when(settings.downloadDirectory()).thenReturn("incoming");
        var engine = mock(StorageEngineProbe.class);
        when(engine.mapping()).thenReturn("compatible_unproven");
        var diagnostic = new StorageDiagnostics(root.toString(), settings, engine) {
            @Override void probe(Path directory) throws java.io.IOException { throw new AccessDeniedException("private path"); }
        };
        assertEquals("permission_denied", diagnostic.check().backend());
        assertEquals("blocked", diagnostic.check().status());
        assertFalse(diagnostic.check().sharedDirectoryVerified());
    }

    @Test void incompatibleEngineDestinationBlocksEvenWritableBackend() throws Exception {
        Files.createDirectory(root.resolve("incoming"));
        var result = diagnostics("incoming", "mapping_incompatible").check();
        assertEquals("read_write_verified", result.backend());
        assertEquals("blocked", result.status());
    }

    @Test void validDestinationChangePreservesEarlierFilesAndRecords() throws Exception {
        var old = Files.createDirectory(root.resolve("old"));
        var media = Files.writeString(old.resolve("existing.mp4"), "test media");
        Files.createDirectory(root.resolve("new"));
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var settings = new LocalStorageSettings(jdbc);
        assertEquals("new", settings.updateDownloadDirectory("new"));
        verify(jdbc).update("UPDATE storage_root SET download_relative_path = ? WHERE id = 1", "new");
        verifyNoMoreInteractions(jdbc);
        assertEquals("read_write_verified", diagnostics("new", "compatible_unproven").check().backend());
        assertEquals("test media", Files.readString(media));
    }

    @Test void mvcReturnsNoPrivatePathsAndDisablesCaching() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new StorageDiagnosticsController(diagnostics("missing", "engine_unavailable"))).build();
        mvc.perform(post("/api/system/storage/check")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.sharedDirectoryVerified").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(root.toString()))));
    }
}
