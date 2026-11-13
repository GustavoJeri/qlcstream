package dev.qlcstream.downloads.infrastructure.qbittorrent;

import java.time.Instant;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.qlcstream.downloads.infrastructure.persistence.SpringDataDownloadRepository;
import dev.qlcstream.library.LocalLibraryRegistrar;
import dev.qlcstream.downloads.infrastructure.web.DownloadUpdatePublisher;

@Component
public class QbittorrentDownloadSynchronizer {

    private static final Set<String> FINAL_STATUSES = Set.of("COMPLETED", "CANCELED", "REMOVED");

    private final SpringDataDownloadRepository downloads;
    private final QbittorrentClient qbittorrent;
    private final LocalLibraryRegistrar library;
    private final DownloadUpdatePublisher updates;
    private volatile SyncStatus syncStatus = new SyncStatus("waiting", null, false);
    private Instant nextPreferencesCheck = Instant.EPOCH;

    public record SyncStatus(String state, Instant lastSuccessfulAt, boolean queueingEnabled) { }

    public SyncStatus syncStatus() {
        return syncStatus;
    }

    public QbittorrentDownloadSynchronizer(SpringDataDownloadRepository downloads, QbittorrentClient qbittorrent,
            LocalLibraryRegistrar library, DownloadUpdatePublisher updates) {
        this.downloads = downloads;
        this.qbittorrent = qbittorrent;
        this.library = library;
        this.updates = updates;
    }

    @Scheduled(fixedDelayString = "${qlc-stream.qbittorrent.sync-delay:PT5S}")
    @Transactional
    public void synchronize() {
        var before = syncStatus;
        java.util.List<QbittorrentClient.TorrentSnapshot> torrents;
        try {
            torrents = qbittorrent.torrents();
        } catch (QbittorrentClient.QbittorrentUnavailableException exception) {
            syncStatus = new SyncStatus("unavailable", before.lastSuccessfulAt(), false);
            nextPreferencesCheck = Instant.EPOCH;
            if (!before.state().equals("unavailable")) updates.publish();
            return;
        }
        var now = Instant.now();
        var queueingEnabled = before.queueingEnabled();
        if (!now.isBefore(nextPreferencesCheck)) {
            queueingEnabled = qbittorrent.queueingEnabled();
            nextPreferencesCheck = now.plusSeconds(30);
        }
        var torrentsByHash = torrents.stream()
                .collect(Collectors.toMap(QbittorrentClient.TorrentSnapshot::hash, Function.identity(), (first, ignored) -> first));
        var torrentsByPath = torrents.stream()
                .collect(Collectors.toMap(torrent -> normalizePath(torrent.savePath()), Function.identity(), (first, ignored) -> first));
        var changed = false;
        for (var download : downloads.findByStatusNotIn(FINAL_STATUSES)) {
            var torrent = download.infoHash() == null ? null : torrentsByHash.get(download.infoHash());
            if (torrent == null) torrent = torrentsByPath.get(normalizePath("/downloads/" + download.relativeDirectory()));
            if (torrent != null) {
                var status = statusFor(torrent.state(), torrent.progress());
                var previousStatus = download.status();
                changed |= download.synchronize(torrent.hash(), torrent.state(), torrent.progress(), torrent.size(), torrent.downloaded(),
                        torrent.downloadSpeedBps(), torrent.eta(), status);
                if ((status.equals("SEEDING") || status.equals("COMPLETED")) && !isCompleted(previousStatus)) {
                    updates.publishNotification("COMPLETED", "Download concluído", download.releaseTitle());
                } else if (status.equals("ERROR") && !previousStatus.equals("ERROR")) {
                    updates.publishNotification("ERROR", "Não foi possível concluir o download", download.releaseTitle());
                }
                if (status.equals("SEEDING") || status.equals("COMPLETED")) {
                    if (download.movieId() != null) {
                        library.registerCompletedDownload(download.movieId(), download.id(), download.relativeDirectory());
                    } else if (download.seriesTmdbId() != null) {
                        library.registerCompletedSeries(download.seriesTmdbId(), download.seriesTitle(), download.seriesPosterPath(),
                                download.seasonNumber(), download.episodeNumber(), download.id(), download.relativeDirectory());
                    }
                    // File discovery can fail temporarily (mount unavailable, permissions, etc.).
                    // Retry on the next cycle; never delete a torrent based on this scan.
                }
            } else if ((download.infoHash() != null && !download.infoHash().isBlank())
                    || download.wasSubmittedBefore(now.minusSeconds(300))) {
                if (download.markMissingFromEngine()) {
                    changed = true;
                    updates.publishNotification("ERROR", "Download não encontrado", download.releaseTitle());
                }
            }
        }
        var confirmed = new SyncStatus("available", now, queueingEnabled);
        boolean notify = changed || !before.state().equals(confirmed.state())
                || before.queueingEnabled() != confirmed.queueingEnabled();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    confirmSync(confirmed, notify);
                }
            });
        } else {
            confirmSync(confirmed, notify);
        }
    }

    private void confirmSync(SyncStatus confirmed, boolean notify) {
        syncStatus = confirmed;
        if (notify) updates.publish();
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/').replaceAll("/+$", "");
    }

    private static boolean isCompleted(String status) {
        return status.equals("SEEDING") || status.equals("COMPLETED");
    }

    private static String statusFor(String state, double progress) {
        return switch (state) {
            case "metaDL", "forcedMetaDL" -> "METADATA";
            case "downloading", "forcedDL" -> "DOWNLOADING";
            case "stalledDL" -> "STALLED";
            case "checkingDL", "checkingUP", "checkingResumeData", "moving" -> "CHECKING";
            case "pausedDL", "pausedUP", "stoppedDL", "stoppedUP" -> "PAUSED";
            case "queuedUP" -> progress >= 1 ? "SEEDING" : "QUEUED";
            case "uploading", "forcedUP", "stalledUP" -> "SEEDING";
            case "error", "missingFiles", "unknown" -> "ERROR";
            default -> "QUEUED";
        };
    }
}
