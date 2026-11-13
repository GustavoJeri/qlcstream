package dev.qlcstream.downloads.domain;

import java.time.Instant;
import java.util.UUID;

public record DownloadSummary(
        UUID id,
        long movieTmdbId,
        String movieTitle,
        String posterPath,
        String mediaType,
        String releaseTitle,
        Integer resolutionHeight,
        String sourceType,
        String dynamicRange,
        DownloadStatus status,
        double progress,
        Long totalBytes,
        long downloadedBytes,
        long downloadSpeedBps,
        Long etaSeconds,
        Instant createdAt,
        String engineState,
        boolean canRetry) {
}
