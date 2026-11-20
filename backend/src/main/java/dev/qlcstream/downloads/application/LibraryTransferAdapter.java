package dev.qlcstream.downloads.application;

import org.springframework.stereotype.Service;
import dev.qlcstream.library.LibraryTransferControl;
import dev.qlcstream.downloads.infrastructure.qbittorrent.QbittorrentClient.QbittorrentUnavailableException;
import dev.qlcstream.downloads.infrastructure.web.DownloadUpdatePublisher;

@Service
class LibraryTransferAdapter implements LibraryTransferControl {
    private final DownloadControlService controls;
    private final DownloadUpdatePublisher updates;

    LibraryTransferAdapter(DownloadControlService controls, DownloadUpdatePublisher updates) {
        this.controls = controls;
        this.updates = updates;
    }

    @Override public boolean cancelForFileDeletion(java.util.UUID downloadId) {
        try {
            return controls.cancelForFileDeletion(downloadId);
        } catch (QbittorrentUnavailableException | IllegalArgumentException | IllegalStateException
                | java.util.NoSuchElementException failure) {
            throw new LibraryTransferControl.Unavailable();
        } finally {
            updates.publish();
        }
    }
}
