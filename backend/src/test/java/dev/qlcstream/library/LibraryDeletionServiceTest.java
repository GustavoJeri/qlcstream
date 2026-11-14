package dev.qlcstream.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class LibraryDeletionServiceTest {
    @TempDir Path root;
    static class Database extends JdbcTemplate {
        boolean requested, failFinalWrite;
        String availability = "PRESENT";
        final UUID download = UUID.randomUUID();
        @Override public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... args) {
            try {
                var rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(true);
                when(rs.getString(1)).thenReturn("owned.mp4");
                when(rs.getObject(2, UUID.class)).thenReturn(download);
                when(rs.getString(3)).thenReturn(availability);
                when(rs.getBoolean(4)).thenReturn(requested);
                return extractor.extractData(rs);
            } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
        }
        @Override public int update(String sql, Object... args) {
            if (sql.contains("deletion_requested")) requested = true;
            else if (failFinalWrite) throw new DataAccessResourceFailureException("synthetic failure");
            else availability = "DELETED";
            return 1;
        }
    }
    LibraryDeletionService service(Database db, LibraryTransferControl control) {
        var transaction = new TransactionTemplate() {
            @Override public void executeWithoutResult(Consumer<TransactionStatus> action) { action.accept(null); }
        };
        return new LibraryDeletionService(db, transaction, control, root.toString());
    }
    void secureProvider() throws Exception {
        try (var stream = Files.newDirectoryStream(root)) {
            assumeTrue(stream instanceof SecureDirectoryStream<?> || Files.isRegularFile(Path.of("/app/library-file-delete.py")));
        }
    }
    @Test void pendingEngineCancellationKeepsTheFileAndDurableIntent() throws Exception {
        secureProvider();
        var file = Files.writeString(root.resolve("owned.mp4"), "owned");
        var db = new Database(); var controls = mock(LibraryTransferControl.class);
        var error = assertThrows(LibraryDeletionService.Failure.class, () -> service(db, controls).delete(1));
        assertTrue(error.getMessage().contains("arquivo foi mantido"));
        assertTrue(db.requested); assertEquals("owned", Files.readString(file));
        assertEquals("PRESENT", db.availability);
    }
    @Test void retriesDatabaseFailureAfterDeletingBytesWithoutResubmittingTorrent() throws Exception {
        secureProvider();
        var file = Files.writeString(root.resolve("owned.mp4"), "owned");
        var db = new Database(); db.failFinalWrite = true;
        var controls = mock(LibraryTransferControl.class);
        when(controls.cancelForFileDeletion(db.download)).thenReturn(true);
        var service = service(db, controls);
        var error = assertThrows(LibraryDeletionService.Failure.class, () -> service.delete(1));
        assertTrue(error.getMessage().startsWith("O arquivo foi removido"));
        assertFalse(Files.exists(file)); assertTrue(db.requested); assertEquals("PRESENT", db.availability);
        db.failFinalWrite = false;
        service.delete(1); service.delete(1);
        assertEquals("DELETED", db.availability);
        verify(controls, times(2)).cancelForFileDeletion(db.download);
    }
}
