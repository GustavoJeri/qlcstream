package dev.qlcstream.downloads.infrastructure.persistence;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "download")
public class DownloadRow {
    @Id
    private UUID id;

    @Column(name = "movie_id")
    private Long movieId;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Column(name = "series_tmdb_id")
    private Long seriesTmdbId;

    @Column(name = "series_title")
    private String seriesTitle;

    @Column(name = "series_poster_path")
    private String seriesPosterPath;

    @Column(name = "season_number")
    private Integer seasonNumber;

    @Column(name = "episode_number")
    private Integer episodeNumber;

    @Column(name = "storage_root_id", nullable = false)
    private long storageRootId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(nullable = false)
    private String engine;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "info_hash")
    private String infoHash;

    @Column(name = "indexer_name")
    private String indexerName;

    @Column(name = "release_title", nullable = false)
    private String releaseTitle;

    @Column(name = "acquisition_ref", nullable = false)
    private String acquisitionRef;

    @Column(name = "relative_directory", nullable = false)
    private String relativeDirectory;

    @Column(name = "resolution_height")
    private Integer resolutionHeight;

    @Column(name = "source_type")
    private String sourceType;

    @Column(name = "dynamic_range")
    private String dynamicRange;

    @Column(nullable = false)
    private String status;

    @Column(name = "engine_state")
    private String engineState;

    @Column(precision = 6, scale = 5, nullable = false)
    private BigDecimal progress;

    @Column(name = "total_bytes")
    private Long totalBytes;

    @Column(name = "downloaded_bytes", nullable = false)
    private long downloadedBytes;

    @Column(name = "download_speed_bps", nullable = false)
    private long downloadSpeedBps;

    @Column(name = "eta_seconds")
    private Long etaSeconds;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    protected DownloadRow() {
    }

    public static DownloadRow queued(Long movieId, UUID id, String releaseTitle, String acquisitionRef, String indexerName,
            Integer resolutionHeight, String sourceType, String dynamicRange, String relativeDirectory) {
        var now = Instant.now();
        var row = new DownloadRow();
        row.id = id;
        row.movieId = movieId;
        row.mediaType = "MOVIE";
        row.storageRootId = 1;
        row.idempotencyKey = UUID.randomUUID();
        row.engine = "QBITTORRENT";
        row.indexerName = indexerName;
        row.releaseTitle = releaseTitle;
        row.acquisitionRef = acquisitionRef;
        row.relativeDirectory = relativeDirectory;
        row.resolutionHeight = resolutionHeight;
        row.sourceType = sourceType;
        row.dynamicRange = dynamicRange;
        row.status = "QUEUED";
        row.progress = BigDecimal.ZERO;
        row.downloadedBytes = 0;
        row.downloadSpeedBps = 0;
        row.attemptCount = 1;
        row.createdAt = now;
        row.updatedAt = now;
        return row;
    }

    public static DownloadRow queuedSeries(long seriesTmdbId, String seriesTitle, String posterPath, Integer seasonNumber, Integer episodeNumber, UUID id, String releaseTitle, String acquisitionRef, String indexerName, Integer resolutionHeight, String sourceType, String dynamicRange, String relativeDirectory) {
        var row = queued(null, id, releaseTitle, acquisitionRef, indexerName, resolutionHeight, sourceType, dynamicRange, relativeDirectory);
        row.mediaType = "SERIES"; row.seriesTmdbId = seriesTmdbId; row.seriesTitle = seriesTitle; row.seriesPosterPath = posterPath; row.seasonNumber = seasonNumber; row.episodeNumber = episodeNumber;
        return row;
    }

    public String relativeDirectory() {
        return relativeDirectory;
    }

    public Long movieId() { return movieId; }
    public Long seriesTmdbId() { return seriesTmdbId; }
    public String seriesTitle() { return seriesTitle; }
    public String seriesPosterPath() { return seriesPosterPath; }
    public Integer seasonNumber() { return seasonNumber; }
    public Integer episodeNumber() { return episodeNumber; }

    public UUID id() {
        return id;
    }

    public String infoHash() {
        return infoHash;
    }

    public String status() { return status; }

    public boolean wasSubmittedBefore(Instant cutoff) { return createdAt.isBefore(cutoff); }

    public String releaseTitle() { return releaseTitle; }

    public void markStatus(String value) {
        status = value;
        updatedAt = Instant.now();
    }

    public boolean synchronize(String hash, String state, double currentProgress, long size, long downloaded, long downloadSpeed,
            long eta, String normalizedStatus) {
        var changed = !java.util.Objects.equals(infoHash, hash) || !java.util.Objects.equals(engineState, state)
                || !status.equals(normalizedStatus) || progress.doubleValue() != currentProgress || downloadedBytes != downloaded
                || downloadSpeedBps != downloadSpeed;
        externalId = hash;
        infoHash = hash;
        engineState = state;
        progress = BigDecimal.valueOf(currentProgress).setScale(5, java.math.RoundingMode.HALF_UP);
        totalBytes = size;
        downloadedBytes = downloaded;
        downloadSpeedBps = downloadSpeed;
        etaSeconds = eta >= 8_640_000 ? null : eta;
        status = normalizedStatus;
        updatedAt = Instant.now();
        lastSyncedAt = updatedAt;
        return changed;
    }

    public void markRemoved() {
        status = "REMOVED";
        updatedAt = Instant.now();
        lastSyncedAt = updatedAt;
    }

    public boolean markMissingFromEngine() {
        if ("ERROR".equals(status) && "missing".equals(engineState)) {
            return false;
        }
        status = "ERROR";
        engineState = "missing";
        downloadSpeedBps = 0;
        etaSeconds = null;
        updatedAt = Instant.now();
        lastSyncedAt = updatedAt;
        return true;
    }
}
