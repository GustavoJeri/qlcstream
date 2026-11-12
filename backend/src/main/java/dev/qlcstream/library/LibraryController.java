package dev.qlcstream.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/library")
public class LibraryController {

    @org.springframework.beans.factory.annotation.Value("${qlc-stream.storage.media-root:/data}")
    private String mediaRoot = "/data";

    private final JdbcTemplate jdbc;
    private final LocalStorageSettings storageSettings;

    public LibraryController(JdbcTemplate jdbc, LocalStorageSettings storageSettings) {
        this.jdbc = jdbc;
        this.storageSettings = storageSettings;
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable long id) {
        var relativePath = jdbc.query("SELECT relative_path FROM local_file WHERE id = ? AND availability = 'PRESENT'", result ->
                result.next() ? result.getString(1) : null, id);
        if (relativePath == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo não encontrado.");
        var root = Path.of(mediaRoot).toAbsolutePath().normalize();
        var file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Caminho inválido.");
        try {
            Files.deleteIfExists(file);
            jdbc.update("UPDATE local_file SET availability = 'DELETED', last_verified_at = now() WHERE id = ?", id);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Não foi possível excluir o arquivo.");
        }
    }

    @GetMapping("/storage")
    StorageResponse storage() {
        return new StorageResponse(storageSettings.downloadDirectory());
    }

    @PutMapping("/storage")
    StorageResponse updateStorage(@Valid @RequestBody StorageRequest request) {
        return new StorageResponse(storageSettings.updateDownloadDirectory(request.downloadDirectory()));
    }

    @GetMapping("/favorites")
    List<FavoriteResponse> favorites() {
        return jdbc.query("SELECT media_type, tmdb_id, title, poster_path, subtitle, added_at FROM library_favorite ORDER BY added_at DESC",
                (result, row) -> new FavoriteResponse(result.getString("media_type"), result.getLong("tmdb_id"),
                        result.getString("title"), result.getString("poster_path"), result.getString("subtitle"),
                        result.getTimestamp("added_at").toInstant()));
    }

    @PostMapping("/favorites/{mediaType}/{tmdbId}")
    void addFavorite(@PathVariable String mediaType, @PathVariable long tmdbId,
            @Valid @RequestBody FavoriteRequest request) {
        jdbc.update("""
                INSERT INTO library_favorite (media_type, tmdb_id, title, poster_path, subtitle)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (media_type, tmdb_id) DO UPDATE
                SET title = EXCLUDED.title, poster_path = EXCLUDED.poster_path, subtitle = EXCLUDED.subtitle
                """, favoriteType(mediaType), tmdbId, request.title(), request.posterPath(), request.subtitle());
    }

    @DeleteMapping("/favorites/{mediaType}/{tmdbId}")
    void removeFavorite(@PathVariable String mediaType, @PathVariable long tmdbId) {
        jdbc.update("DELETE FROM library_favorite WHERE media_type = ? AND tmdb_id = ?", favoriteType(mediaType), tmdbId);
    }

    @GetMapping("/collections")
    List<CollectionResponse> collections() {
        return jdbc.query("SELECT id, name, created_at FROM library_collection ORDER BY created_at DESC", (result, row) -> {
            var id = result.getLong("id");
            var items = jdbc.query("""
                    SELECT media_type, tmdb_id, title, poster_path, subtitle, added_at
                    FROM library_collection_item WHERE collection_id = ? ORDER BY added_at DESC
                    """, (item, ignored) -> new CollectionItemResponse(item.getString("media_type"), item.getLong("tmdb_id"),
                    item.getString("title"), item.getString("poster_path"), item.getString("subtitle"),
                    item.getTimestamp("added_at").toInstant()), id);
            return new CollectionResponse(id, result.getString("name"), result.getTimestamp("created_at").toInstant(), items);
        });
    }

    @PostMapping("/collections")
    CollectionResponse createCollection(@Valid @RequestBody CollectionRequest request) {
        var name = request.name().trim();
        try {
            var id = jdbc.queryForObject("INSERT INTO library_collection (name) VALUES (?) RETURNING id", Long.class, name);
            return new CollectionResponse(id, name, java.time.Instant.now(), List.of());
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma coleção com esse nome.");
        }
    }

    @DeleteMapping("/collections/{collectionId}")
    void removeCollection(@PathVariable long collectionId) {
        if (jdbc.update("DELETE FROM library_collection WHERE id = ?", collectionId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Coleção não encontrada.");
        }
    }

    @PostMapping("/collections/{collectionId}/items/{mediaType}/{tmdbId}")
    void addCollectionItem(@PathVariable long collectionId, @PathVariable String mediaType, @PathVariable long tmdbId,
            @Valid @RequestBody FavoriteRequest request) {
        jdbc.update("""
                INSERT INTO library_collection_item (collection_id, media_type, tmdb_id, title, poster_path, subtitle)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (collection_id, media_type, tmdb_id) DO UPDATE
                SET title = EXCLUDED.title, poster_path = EXCLUDED.poster_path, subtitle = EXCLUDED.subtitle
                """, collectionId, favoriteType(mediaType), tmdbId, request.title(), request.posterPath(), request.subtitle());
    }

    @DeleteMapping("/collections/{collectionId}/items/{mediaType}/{tmdbId}")
    void removeCollectionItem(@PathVariable long collectionId, @PathVariable String mediaType, @PathVariable long tmdbId) {
        jdbc.update("DELETE FROM library_collection_item WHERE collection_id = ? AND media_type = ? AND tmdb_id = ?",
                collectionId, favoriteType(mediaType), tmdbId);
    }

    private String favoriteType(String value) {
        var type = value.toUpperCase(java.util.Locale.ROOT);
        if (!type.equals("MOVIE") && !type.equals("SERIES")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de mídia inválido.");
        }
        return type;
    }

    @GetMapping("/planned")
    List<PlannedMovieResponse> planned() {
        return jdbc.query("""
                SELECT m.tmdb_id, m.title, m.original_title, m.poster_path, m.release_date, m.vote_average, p.added_at
                FROM planned_library_movie p
                JOIN movie m ON m.id = p.movie_id
                ORDER BY p.added_at DESC
                """, (result, row) -> new PlannedMovieResponse(result.getLong("tmdb_id"), result.getString("title"),
                result.getString("original_title"), result.getString("poster_path"), result.getObject("release_date", java.time.LocalDate.class),
                result.getBigDecimal("vote_average"), result.getTimestamp("added_at").toInstant()));
    }

    @GetMapping("/movie-ids")
    List<Long> libraryMovieIds() {
        return jdbc.queryForList("""
                SELECT m.tmdb_id
                FROM movie m
                WHERE EXISTS (SELECT 1 FROM planned_library_movie p WHERE p.movie_id = m.id)
                   OR EXISTS (
                       SELECT 1 FROM local_file lf
                       WHERE lf.movie_id = m.id AND lf.file_kind = 'VIDEO' AND lf.availability = 'PRESENT'
                   )
                """, Long.class);
    }

    @GetMapping("/planned/{tmdbId}")
    PlannedStatusResponse plannedStatus(@PathVariable long tmdbId) {
        var planned = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM planned_library_movie p
                    JOIN movie m ON m.id = p.movie_id
                    WHERE m.tmdb_id = ?
                )
                """, Boolean.class, tmdbId));
        return new PlannedStatusResponse(planned);
    }

    @PostMapping("/planned/{tmdbId}")
    PlannedStatusResponse addPlanned(@PathVariable long tmdbId) {
        var updated = jdbc.update("""
                INSERT INTO planned_library_movie (movie_id)
                SELECT id FROM movie WHERE tmdb_id = ?
                ON CONFLICT (movie_id) DO NOTHING
                """, tmdbId);
        if (updated == 0) {
            var movieExists = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM movie WHERE tmdb_id = ?)", Boolean.class, tmdbId));
            if (!movieExists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Filme não encontrado.");
        }
        return new PlannedStatusResponse(true);
    }

    @DeleteMapping("/planned/{tmdbId}")
    void removePlanned(@PathVariable long tmdbId) {
        jdbc.update("""
                DELETE FROM planned_library_movie
                WHERE movie_id = (SELECT id FROM movie WHERE tmdb_id = ?)
                """, tmdbId);
    }

    @GetMapping("/planned-series")
    List<PlannedSeriesResponse> plannedSeries() {
        return jdbc.query("""
                SELECT p.tmdb_id, p.name, p.original_name, p.poster_path, p.first_air_date, p.vote_average, p.added_at
                FROM planned_library_series p
                ORDER BY p.added_at DESC
                """, (result, row) -> new PlannedSeriesResponse(result.getLong("tmdb_id"), result.getString("name"),
                result.getString("original_name"), result.getString("poster_path"), result.getObject("first_air_date", java.time.LocalDate.class),
                result.getBigDecimal("vote_average"), result.getTimestamp("added_at").toInstant()));
    }

    @GetMapping("/planned-series/{tmdbId}")
    PlannedStatusResponse plannedSeriesStatus(@PathVariable long tmdbId) {
        return new PlannedStatusResponse(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM planned_library_series WHERE tmdb_id = ?)", Boolean.class, tmdbId)));
    }

    @PostMapping("/planned-series/{tmdbId}")
    PlannedStatusResponse addPlannedSeries(@PathVariable long tmdbId, @Valid @RequestBody PlannedSeriesRequest request) {
        jdbc.update("""
                INSERT INTO planned_library_series (tmdb_id, name, original_name, poster_path, first_air_date, vote_average)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tmdb_id) DO NOTHING
                """, tmdbId, request.name(), request.originalName(), request.posterPath(), request.firstAirDate(), request.voteAverage());
        return new PlannedStatusResponse(true);
    }

    @DeleteMapping("/planned-series/{tmdbId}")
    void removePlannedSeries(@PathVariable long tmdbId) {
        jdbc.update("DELETE FROM planned_library_series WHERE tmdb_id = ?", tmdbId);
    }

    record FavoriteRequest(@NotBlank String title, String posterPath, String subtitle) {
    }

    record FavoriteResponse(String mediaType, long tmdbId, String title, String posterPath, String subtitle,
            java.time.Instant addedAt) {
    }

    record CollectionRequest(@NotBlank String name) {
    }

    record CollectionResponse(long id, String name, java.time.Instant createdAt, List<CollectionItemResponse> items) {
    }

    record CollectionItemResponse(String mediaType, long tmdbId, String title, String posterPath, String subtitle,
            java.time.Instant addedAt) {
    }

    record StorageRequest(@NotBlank String downloadDirectory) {
    }

    record StorageResponse(String downloadDirectory) {
    }

    record PlannedStatusResponse(boolean planned) {
    }

    record PlannedMovieResponse(long tmdbId, String title, String originalTitle, String posterPath,
            java.time.LocalDate releaseDate, java.math.BigDecimal voteAverage, java.time.Instant addedAt) {
    }

    record PlannedSeriesRequest(@NotBlank String name, String originalName, String posterPath,
            java.time.LocalDate firstAirDate, java.math.BigDecimal voteAverage) {
    }

    record PlannedSeriesResponse(long tmdbId, String name, String originalName, String posterPath,
            java.time.LocalDate firstAirDate, java.math.BigDecimal voteAverage, java.time.Instant addedAt) {
    }

    @GetMapping("/series")
    List<SeriesLibraryItemResponse> browseSeries() {
        return jdbc.query("""
                SELECT lf.id, lf.series_tmdb_id, lf.series_title, lf.series_poster_path, lf.season_number, lf.episode_number,
                       lf.relative_path, lf.size_bytes, d.resolution_height, d.source_type, d.dynamic_range, lf.discovered_at
                FROM local_file lf
                LEFT JOIN download d ON d.id = lf.download_id
                WHERE lf.file_kind = 'VIDEO' AND lf.availability = 'PRESENT' AND lf.series_tmdb_id IS NOT NULL
                ORDER BY lf.discovered_at DESC
                """, (result, row) -> new SeriesLibraryItemResponse(result.getLong("id"), result.getLong("series_tmdb_id"),
                result.getString("series_title"), result.getString("series_poster_path"), result.getObject("season_number", Integer.class),
                result.getObject("episode_number", Integer.class), result.getString("relative_path"), result.getLong("size_bytes"),
                result.getObject("resolution_height", Integer.class), result.getString("source_type"), result.getString("dynamic_range"),
                result.getTimestamp("discovered_at").toInstant()));
    }

    @GetMapping
    List<LibraryItemResponse> browse() {
        return jdbc.query("""
                SELECT lf.id, m.tmdb_id, m.title, m.poster_path, lf.relative_path, lf.size_bytes, d.resolution_height,
                       d.source_type, d.dynamic_range, lf.discovered_at
                FROM local_file lf
                JOIN movie m ON m.id = lf.movie_id
                LEFT JOIN download d ON d.id = lf.download_id
                WHERE lf.file_kind = 'VIDEO' AND lf.availability = 'PRESENT'
                ORDER BY lf.discovered_at DESC
                """, (result, row) -> new LibraryItemResponse(result.getLong("id"), result.getLong("tmdb_id"),
                result.getString("title"), result.getString("poster_path"), result.getString("relative_path"),
                result.getLong("size_bytes"), result.getObject("resolution_height", Integer.class), result.getString("source_type"),
                result.getString("dynamic_range"), result.getTimestamp("discovered_at").toInstant()));
    }

    record LibraryItemResponse(long id, long movieTmdbId, String movieTitle, String posterPath, String relativePath,
            long sizeBytes, Integer resolutionHeight, String sourceType, String dynamicRange, java.time.Instant discoveredAt) {
    }

    record SeriesLibraryItemResponse(long id, long seriesTmdbId, String seriesTitle, String posterPath, Integer seasonNumber,
            Integer episodeNumber, String relativePath, long sizeBytes, Integer resolutionHeight, String sourceType,
            String dynamicRange, java.time.Instant discoveredAt) {
    }
}
