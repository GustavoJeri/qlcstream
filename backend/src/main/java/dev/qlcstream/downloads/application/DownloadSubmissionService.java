package dev.qlcstream.downloads.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.qlcstream.downloads.infrastructure.persistence.DownloadRow;
import dev.qlcstream.downloads.infrastructure.persistence.SpringDataDownloadRepository;
import dev.qlcstream.downloads.infrastructure.qbittorrent.QbittorrentClient;
import dev.qlcstream.library.LocalStorageSettings;

@Service
public class DownloadSubmissionService {

    private final SpringDataDownloadRepository downloads;
    private final QbittorrentClient qbittorrent;
    private final LocalStorageSettings storageSettings;

    public DownloadSubmissionService(SpringDataDownloadRepository downloads, QbittorrentClient qbittorrent,
            LocalStorageSettings storageSettings) {
        this.downloads = downloads;
        this.qbittorrent = qbittorrent;
        this.storageSettings = storageSettings;
    }

    @Transactional
    public SubmittedDownload submit(DownloadRequest request) {
        var movieId = downloads.findMovieIdByTmdbId(request.movieTmdbId());
        if (movieId == null) {
            throw new MovieNotAvailableException();
        }

        downloads.lockSubmissions();
        var existing = downloads.findExistingRelease(request.acquisitionRef(), request.releaseTitle(), "MOVIE", movieId, null);
        if (existing.isPresent()) {
            var download = existing.get();
            return new SubmittedDownload(download.id(), download.status());
        }
        return submitNew(request.acquisitionRef(), request.releaseTitle(), (id, directory) ->
                DownloadRow.queued(movieId, id, request.releaseTitle(), request.acquisitionRef(), request.indexerName(),
                        request.resolutionHeight(), request.sourceType(), request.dynamicRange(), directory));
    }

    @Transactional
    public SubmittedDownload submitSeries(SeriesDownloadRequest request) {
        downloads.lockSubmissions();
        var existing = downloads.findExistingRelease(request.acquisitionRef(), request.releaseTitle(), "SERIES", null, request.seriesTmdbId());
        if (existing.isPresent()) {
            var download = existing.get();
            return new SubmittedDownload(download.id(), download.status());
        }
        return submitNew(request.acquisitionRef(), request.releaseTitle(), (id, directory) ->
                DownloadRow.queuedSeries(request.seriesTmdbId(), request.seriesTitle(), request.posterPath(),
                        request.seasonNumber(), request.episodeNumber(), id, request.releaseTitle(), request.acquisitionRef(),
                        request.indexerName(), request.resolutionHeight(), request.sourceType(), request.dynamicRange(), directory));
    }

    private SubmittedDownload submitNew(String acquisitionRef, String releaseTitle,
            java.util.function.BiFunction<UUID, String, DownloadRow> createRow) {
        // A previous request may have reached the engine before its database transaction failed.
        // Reattach that transfer instead of downloading again or ignoring a duplicate conflict.
        var existingTorrent = qbittorrent.torrents().stream().filter(torrent ->
                releaseTitle.equals(torrent.name()) || acquisitionRef.toLowerCase(java.util.Locale.ROOT)
                        .contains("urn:btih:" + torrent.hash().toLowerCase(java.util.Locale.ROOT)))
                .findFirst();
        var id = UUID.randomUUID();
        String directory;
        DownloadRow row;
        if (existingTorrent.isPresent()) {
            var torrent = existingTorrent.get();
            var path = torrent.savePath().replace('\\', '/').replaceAll("/+$", "");
            if (!path.startsWith("/downloads/") || java.util.Arrays.asList(path.split("/")).contains("..")) {
                throw new IllegalStateException("Esta fonte já está no qBittorrent fora da pasta de mídia do aplicativo.");
            }
            directory = path.substring("/downloads/".length());
            row = downloads.findByRelativeDirectory(directory).orElseGet(() -> createRow.apply(id, directory));
            row.synchronize(torrent.hash(), torrent.state(), torrent.progress(), torrent.size(), torrent.downloaded(),
                    torrent.downloadSpeedBps(), torrent.eta(), "QUEUED");
        } else {
            directory = storageSettings.downloadDirectory() + "/" + id;
            qbittorrent.add(acquisitionRef, "/downloads/" + directory);
            row = createRow.apply(id, directory);
        }
        downloads.save(row);
        return new SubmittedDownload(row.id(), row.status());
    }

    public record SeriesDownloadRequest(long seriesTmdbId, String seriesTitle, String posterPath, Integer seasonNumber, Integer episodeNumber, String releaseTitle, String acquisitionRef, String indexerName, Integer resolutionHeight, String sourceType, String dynamicRange) {
    }

    public record DownloadRequest(long movieTmdbId, String releaseTitle, String acquisitionRef, String indexerName,
            Integer resolutionHeight, String sourceType, String dynamicRange) {
    }

    public record SubmittedDownload(UUID id, String status) {
    }

    public static class MovieNotAvailableException extends RuntimeException {
    }
}
