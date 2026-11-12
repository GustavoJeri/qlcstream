package dev.qlcstream.downloads.infrastructure.qbittorrent;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
        var torrents = qbittorrent.torrents();
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
                    || download.wasSubmittedBefore(java.time.Instant.now().minusSeconds(300))) {
                if (download.markMissingFromEngine()) {
                    changed = true;
                    updates.publishNotification("ERROR", "Download não encontrado", download.releaseTitle());
                }
            }
        }
        if (changed) updates.publish();
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
