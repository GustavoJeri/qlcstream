package dev.qlcstream.downloads.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import dev.qlcstream.downloads.application.DownloadSubmissionService;
import dev.qlcstream.downloads.application.DownloadControlService;
import dev.qlcstream.downloads.application.port.in.BrowseDownloadsUseCase;
import dev.qlcstream.downloads.domain.DownloadSummary;
import dev.qlcstream.downloads.infrastructure.qbittorrent.QbittorrentDownloadSynchronizer;
import dev.qlcstream.downloads.infrastructure.qbittorrent.QbittorrentDownloadSynchronizer.SyncStatus;

@RestController
@RequestMapping("/api/downloads")
public class DownloadsController {

    private final BrowseDownloadsUseCase browseDownloads;
    private final DownloadSubmissionService submissions;
    private final DownloadControlService controls;
    private final DownloadUpdatePublisher updates;
    private final QbittorrentDownloadSynchronizer synchronizer;

    public DownloadsController(BrowseDownloadsUseCase browseDownloads, DownloadSubmissionService submissions,
            DownloadControlService controls, DownloadUpdatePublisher updates, QbittorrentDownloadSynchronizer synchronizer) {
        this.browseDownloads = browseDownloads;
        this.submissions = submissions;
        this.controls = controls;
        this.updates = updates;
        this.synchronizer = synchronizer;
    }

    @GetMapping
    List<DownloadResponse> active() {
        return browseDownloads.active().stream().map(DownloadResponse::from).toList();
    }

    @GetMapping("/queue")
    QueueResponse queue() {
        var sync = synchronizer.syncStatus();
        return new QueueResponse(active(), sync);
    }

    record QueueResponse(List<DownloadResponse> items, SyncStatus sync) { }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() { return updates.subscribe(); }

    @PostMapping("/{id}/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void control(@PathVariable UUID id, @PathVariable DownloadControlService.Action action) {
        controls.control(id, action);
        updates.publish();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SubmittedResponse submit(@Valid @RequestBody CreateDownloadRequest request) {
        var submitted = submissions.submit(new DownloadSubmissionService.DownloadRequest(request.movieTmdbId(), request.releaseTitle(),
                request.acquisitionRef(), request.indexerName(), request.resolutionHeight(), request.sourceType(), request.dynamicRange()));
        return new SubmittedResponse(submitted.id(), submitted.status());
    }

    @PostMapping("/series")
    @ResponseStatus(HttpStatus.CREATED)
    SubmittedResponse submitSeries(@Valid @RequestBody CreateSeriesDownloadRequest request) {
        var submitted = submissions.submitSeries(new DownloadSubmissionService.SeriesDownloadRequest(request.seriesTmdbId(), request.seriesTitle(), request.posterPath(), request.seasonNumber(), request.episodeNumber(), request.releaseTitle(), request.acquisitionRef(), request.indexerName(), request.resolutionHeight(), request.sourceType(), request.dynamicRange()));
        return new SubmittedResponse(submitted.id(), submitted.status());
    }

    record CreateSeriesDownloadRequest(@Positive long seriesTmdbId, @NotBlank String seriesTitle, String posterPath, @Positive Integer seasonNumber, @Positive Integer episodeNumber, @NotBlank String releaseTitle, @NotBlank String acquisitionRef, String indexerName, @Positive Integer resolutionHeight, String sourceType, String dynamicRange) {
    }

    record CreateDownloadRequest(@Positive long movieTmdbId, @NotBlank String releaseTitle, @NotBlank String acquisitionRef,
            String indexerName, @Positive Integer resolutionHeight, String sourceType, String dynamicRange) {
    }

    record SubmittedResponse(UUID id, String status) {
    }

    record DownloadResponse(UUID id, long movieTmdbId, String movieTitle, String posterPath, String mediaType, String releaseTitle,
            Integer resolutionHeight, String sourceType, String dynamicRange, String status, double progress,
            Long totalBytes, long downloadedBytes, long downloadSpeedBps, Long etaSeconds, Instant createdAt, String engineState, boolean canRetry) {
        static DownloadResponse from(DownloadSummary download) {
            return new DownloadResponse(download.id(), download.movieTmdbId(), download.movieTitle(), download.posterPath(), download.mediaType(),
                    download.releaseTitle(), download.resolutionHeight(), download.sourceType(), download.dynamicRange(),
                    download.status().name(), download.progress(), download.totalBytes(), download.downloadedBytes(),
                    download.downloadSpeedBps(), download.etaSeconds(), download.createdAt(), download.engineState(), download.canRetry());
        }
    }
}
