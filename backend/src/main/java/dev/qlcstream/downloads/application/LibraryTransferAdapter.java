package dev.qlcstream.downloads.application;

import org.springframework.stereotype.Service;
import dev.qlcstream.library.LibraryTransferControl;
import dev.qlcstream.downloads.infrastructure.qbittorrent.QbittorrentClient.QbittorrentUnavailableException;

@Service
class LibraryTransferAdapter implements LibraryTransferControl {
    private final DownloadControlService controls;
    LibraryTransferAdapter(DownloadControlService controls) { this.controls = controls; }
    @Override public boolean cancelForFileDeletion(java.util.UUID downloadId) {
        try { return controls.cancelForFileDeletion(downloadId); }
        catch (QbittorrentUnavailableException | IllegalArgumentException | IllegalStateException
                | java.util.NoSuchElementException failure) { throw new LibraryTransferControl.Unavailable(); }
    }
}
