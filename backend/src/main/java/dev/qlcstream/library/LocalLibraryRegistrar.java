package dev.qlcstream.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LocalLibraryRegistrar {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mkv", "mp4", "avi", "mov", "m4v", "webm");

    @org.springframework.beans.factory.annotation.Value("${qlc-stream.storage.media-root:/data}")
    private String mediaRoot = "/data";

    private final JdbcTemplate jdbc;

    public LocalLibraryRegistrar(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void registerCompletedDownload(long movieId, UUID downloadId, String relativeDirectory) {
        var directory = Path.of(mediaRoot).resolve(relativeDirectory);
        try (var files = Files.walk(directory)) {
            files.filter(Files::isRegularFile).filter(this::isVideo).forEach(file -> register(movieId, downloadId, file));
        } catch (IOException ignored) {
            // The qBittorrent state is authoritative; the next synchronization retries file discovery.
        }
    }

    public void registerCompletedSeries(long seriesTmdbId, String seriesTitle, String posterPath, Integer seasonNumber,
            Integer episodeNumber, UUID downloadId, String relativeDirectory) {
        jdbc.update("""
                INSERT INTO planned_library_series (tmdb_id, name, poster_path)
                VALUES (?, ?, ?)
                ON CONFLICT (tmdb_id) DO NOTHING
                """, seriesTmdbId, seriesTitle, posterPath);
        var directory = Path.of(mediaRoot).resolve(relativeDirectory);
        try (var files = Files.walk(directory)) {
            files.filter(Files::isRegularFile).filter(this::isVideo)
                    .forEach(file -> registerSeries(seriesTmdbId, seriesTitle, posterPath, seasonNumber, episodeNumber, downloadId, file));
        } catch (IOException ignored) {
            // The qBittorrent state is authoritative; the next synchronization retries file discovery.
        }
    }

    public boolean hasPresentVideo(UUID downloadId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM local_file
                    WHERE download_id = ? AND file_kind = 'VIDEO' AND availability = 'PRESENT'
                )
                """, Boolean.class, downloadId));
    }

    private boolean isVideo(Path file) {
        var name = file.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot >= 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private void registerSeries(long seriesTmdbId, String seriesTitle, String posterPath, Integer seasonNumber,
            Integer episodeNumber, UUID downloadId, Path file) {
        try {
            var relativePath = Path.of(mediaRoot).relativize(file).toString();
            jdbc.update("""
                    INSERT INTO local_file (movie_id, download_id, storage_root_id, relative_path, file_kind, size_bytes,
                                            series_tmdb_id, series_title, series_poster_path, season_number, episode_number)
                    VALUES (NULL, ?, 1, ?, 'VIDEO', ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (storage_root_id, relative_path) DO UPDATE
                    SET availability = 'PRESENT', last_verified_at = now()
                    """, downloadId, relativePath, Files.size(file), seriesTmdbId, seriesTitle, posterPath, seasonNumber, episodeNumber);
        } catch (IOException ignored) {
            // A file can disappear between directory discovery and registration.
        }
    }

    private void register(long movieId, UUID downloadId, Path file) {
        try {
            var relativePath = Path.of(mediaRoot).relativize(file).toString();
            jdbc.update("""
                    INSERT INTO local_file (movie_id, download_id, storage_root_id, relative_path, file_kind, size_bytes)
                    VALUES (?, ?, 1, ?, 'VIDEO', ?)
                    ON CONFLICT (storage_root_id, relative_path) DO UPDATE
                    SET availability = 'PRESENT', last_verified_at = now()
                    """, movieId, downloadId, relativePath, Files.size(file));
        } catch (IOException ignored) {
            // A file can disappear between directory discovery and registration.
        }
    }
}
