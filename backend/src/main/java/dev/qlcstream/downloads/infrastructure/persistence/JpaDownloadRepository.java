package dev.qlcstream.downloads.infrastructure.persistence;

import java.util.List;

import org.springframework.stereotype.Repository;

import dev.qlcstream.downloads.application.port.out.DownloadRepository;
import dev.qlcstream.downloads.domain.DownloadStatus;
import dev.qlcstream.downloads.domain.DownloadSummary;

@Repository
public class JpaDownloadRepository implements DownloadRepository {

    private final SpringDataDownloadRepository repository;

    public JpaDownloadRepository(SpringDataDownloadRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<DownloadSummary> findActive() {
        return repository.findActive().stream().map(row -> new DownloadSummary(
                row.getId(), row.getMovieTmdbId(), row.getMovieTitle(), row.getPosterPath(), row.getMediaType(), row.getReleaseTitle(),
                row.getResolutionHeight(), row.getSourceType(), row.getDynamicRange(), DownloadStatus.valueOf(row.getStatus()),
                row.getProgress(), row.getTotalBytes(), row.getDownloadedBytes(), row.getDownloadSpeedBps(),
                row.getEtaSeconds(), row.getCreatedAt(), row.getEngineState(), row.getCanRetry())).toList();
    }
}
