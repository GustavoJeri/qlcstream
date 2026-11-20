package dev.qlcstream.downloads.application;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.qlcstream.downloads.infrastructure.web.DownloadUpdatePublisher;
import dev.qlcstream.library.LibraryTransferControl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        verify(updates, times(2)).publish();
    }

    @Test void closedEmitterDoesNotBreakDeletionOrMaskUnavailableFailure() {
        var controls = mock(DownloadControlService.class);
        var publisher = new DownloadUpdatePublisher();
        var closedDirectly = publisher.subscribe();
        closedDirectly.complete();
        assertDoesNotThrow(publisher::publish);
        assertDoesNotThrow(() -> publisher.publishNotification("COMPLETED", "title", "message"));

        var closed = publisher.subscribe();
        closed.complete();

        var adapter = new LibraryTransferAdapter(controls, publisher);
        var okId = UUID.randomUUID();
        when(controls.cancelForFileDeletion(okId)).thenReturn(true);
        assertTrue(adapter.cancelForFileDeletion(okId));

        var closedAgain = publisher.subscribe();
        closedAgain.complete();
        var failingId = UUID.randomUUID();
        when(controls.cancelForFileDeletion(failingId)).thenThrow(new IllegalStateException("engine down"));
        assertThrows(LibraryTransferControl.Unavailable.class, () -> adapter.cancelForFileDeletion(failingId));
    }

    @Test void publisherRuntimeFailureNeverMasksOriginalOutcome() {
        var controls = mock(DownloadControlService.class);
        var updates = mock(DownloadUpdatePublisher.class);
        doThrow(new IllegalStateException("emitter already completed")).when(updates).publish();
        var adapter = new LibraryTransferAdapter(controls, updates);

        var okId = UUID.randomUUID();
        when(controls.cancelForFileDeletion(okId)).thenReturn(true);
        assertTrue(adapter.cancelForFileDeletion(okId));

        var failingId = UUID.randomUUID();
        when(controls.cancelForFileDeletion(failingId)).thenThrow(new IllegalArgumentException("unavailable"));
        assertThrows(LibraryTransferControl.Unavailable.class, () -> adapter.cancelForFileDeletion(failingId));
    }
}
