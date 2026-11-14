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
        var torrents = qbittorrent.torrents();
        var requestedHash = magnetHash(acquisitionRef);
        var matches = torrents.stream().filter(torrent -> requestedHash != null
                && requestedHash.equalsIgnoreCase(torrent.hash())).toList();
        if (matches.size() > 1) throw new IllegalStateException("A identidade desta fonte é ambígua no motor.");
        if (requestedHash == null && torrents.stream().anyMatch(torrent -> releaseTitle.equals(torrent.name()))) {
            throw new IllegalStateException("Há uma transferência com o mesmo nome. A identidade não pôde ser confirmada; confira o motor antes de repetir.");
        }
        var existingTorrent = matches.stream().findFirst();
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
            var linked = downloads.findByRelativeDirectory(directory);
            if (linked.isPresent()) {
                throw new IllegalStateException("Esta transferência já está vinculada a outro registro. Use os controles em Downloads.");
            }
            row = createRow.apply(id, directory);
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

    private static String magnetHash(String reference) {
        if (!reference.regionMatches(true, 0, "magnet:?", 0, 8)) return null;
        String hash = null;
        for (var parameter : reference.substring(8).split("&")) {
            var pair = parameter.split("=", 2);
            if (pair.length != 2 || !pair[0].equalsIgnoreCase("xt")) continue;
            var value = java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
            if (!value.regionMatches(true, 0, "urn:btih:", 0, 9)) continue;
            var candidate = value.substring(9);
            if (candidate.matches("(?i)[a-z2-7]{32}")) candidate = base32Hash(candidate);
            if (!candidate.matches("(?i)[0-9a-f]{40}"))
                throw new IllegalArgumentException("O magnet contém um hash inválido.");
            if (hash != null && !hash.equalsIgnoreCase(candidate))
                throw new IllegalArgumentException("O magnet contém identidades conflitantes.");
            hash = candidate;
        }
        return hash;
    }

    private static String base32Hash(String value) {
        var bytes = new java.io.ByteArrayOutputStream(20);
        int buffer = 0, bits = 0;
        for (var character : value.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(character);
            bits += 5;
            if (bits >= 8) { bits -= 8; bytes.write((buffer >> bits) & 255); }
        }
        return java.util.HexFormat.of().formatHex(bytes.toByteArray());
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
