package dev.qlcstream.downloads.application;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.qlcstream.downloads.infrastructure.web.DownloadUpdatePublisher;
import dev.qlcstream.library.LibraryTransferControl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryTransferAdapterTest {
    @Test void cancelForFileDeletionPublishesUpdateOnSuccessAndFailure() {
        var controls = mock(DownloadControlService.class);
        var updates = mock(DownloadUpdatePublisher.class);
        var adapter = new LibraryTransferAdapter(controls, updates);
        var id = UUID.randomUUID();

        when(controls.cancelForFileDeletion(id)).thenReturn(true);
        assertTrue(adapter.cancelForFileDeletion(id));
        verify(updates).publish();

        var failingId = UUID.randomUUID();
        when(controls.cancelForFileDeletion(failingId)).thenThrow(new IllegalArgumentException("missing"));
        assertThrows(LibraryTransferControl.Unavailable.class, () -> adapter.cancelForFileDeletion(failingId));
        verify(updates, org.mockito.Mockito.times(2)).publish();
    }
}
