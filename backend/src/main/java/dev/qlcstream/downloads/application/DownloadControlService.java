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
        if (hash == null || hash.isBlank()) {
            // A tentativa só pode controlar o torrent que usa a pasta reservada para ela.
            hash = qbittorrent.hashForSavePath("/downloads/" + download.relativeDirectory());
            if (hash == null && action == Action.CANCEL) {
                // URL retrieval may still be pending. Keep a durable cancellation intent.
                download.markStatus("CANCELED");
                return;
            }
            if (hash == null) throw new IllegalStateException("Esta tentativa ainda não está vinculada a um torrent. Aguarde ou cancele-a e selecione a fonte novamente.");
        }
        switch (action) {
            case PAUSE -> { qbittorrent.pause(hash); download.markStatus("PAUSED"); }
            case RESUME -> {
                if ("ERROR".equals(download.status())) qbittorrent.recheck(hash);
                qbittorrent.resume(hash);
                download.markStatus("QUEUED");
            }
            case RETRY -> {
                qbittorrent.recheck(hash);
                qbittorrent.resume(hash);
                download.markStatus("CHECKING");
            }
            case PRIORITY_UP -> qbittorrent.increasePriority(hash);
            case PRIORITY_DOWN -> qbittorrent.decreasePriority(hash);
            case CANCEL -> {
                qbittorrent.remove(hash);
                download.bindHash(hash);
                download.markStatus("CANCELED");
            }
        }
    }

    @Transactional
    public boolean cancelForFileDeletion(UUID id) {
        control(id, Action.CANCEL);
        var download = downloads.findById(id).orElseThrow();
        if ("REMOVED".equals(download.status())) return true;
        var hash = download.infoHash();
        if (hash == null || hash.isBlank()) return false;
        if (qbittorrent.torrents().stream().anyMatch(torrent -> hash.equals(torrent.hash()))) return false;
        download.markRemoved();
        return true;
    }

    public enum Action { PAUSE, RESUME, RETRY, PRIORITY_UP, PRIORITY_DOWN, CANCEL }
}
