package dev.qlcstream.downloads.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.qlcstream.downloads.infrastructure.persistence.SpringDataDownloadRepository;
import dev.qlcstream.downloads.infrastructure.qbittorrent.QbittorrentClient;

@Service
public class DownloadControlService {
    private final SpringDataDownloadRepository downloads;
    private final QbittorrentClient qbittorrent;

    public DownloadControlService(SpringDataDownloadRepository downloads, QbittorrentClient qbittorrent) {
        this.downloads = downloads;
        this.qbittorrent = qbittorrent;
    }

    @Transactional
    public void control(UUID id, Action action) {
        var download = downloads.findById(id).orElseThrow(() -> new IllegalArgumentException("Download não encontrado."));
        if ("REMOVED".equals(download.status()) || "CANCELED".equals(download.status())) return;
        var hash = download.infoHash();
        if ((hash == null || hash.isBlank()) && action == Action.CANCEL) {
            // Remove only a torrent owned by this request; another request may share its release.
            var expectedPath = "/downloads/" + download.relativeDirectory();
            qbittorrent.torrents().stream()
                    .filter(torrent -> expectedPath.equals(torrent.savePath().replaceAll("/+$", "")))
                    .findFirst().ifPresent(torrent -> qbittorrent.remove(torrent.hash()));
            download.markRemoved();
            return;
        }
        if (hash == null || hash.isBlank()) throw new IllegalStateException("Esta tentativa não está vinculada a um torrent. Cancele-a e selecione a fonte novamente.");
        switch (action) {
            case PAUSE -> { qbittorrent.pause(hash); download.markStatus("PAUSED"); }
            case RESUME -> {
                if ("ERROR".equals(download.status())) qbittorrent.recheck(hash);
                qbittorrent.resume(hash);
                download.markStatus("QUEUED");
            }
            case CANCEL -> { qbittorrent.remove(hash); download.markRemoved(); }
        }
    }

    public enum Action { PAUSE, RESUME, CANCEL }
}
