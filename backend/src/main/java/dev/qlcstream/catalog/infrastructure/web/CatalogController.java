package dev.qlcstream.catalog.infrastructure.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import dev.qlcstream.catalog.application.port.in.BrowseCatalogUseCase;
import dev.qlcstream.catalog.application.port.in.ViewMovieDetailsUseCase;
import dev.qlcstream.catalog.domain.CatalogCollection;
import dev.qlcstream.catalog.domain.CatalogPage;
import dev.qlcstream.catalog.domain.CatalogSort;
import dev.qlcstream.catalog.domain.Movie;
import dev.qlcstream.catalog.domain.MovieDetails;
import dev.qlcstream.catalog.domain.Person;
import dev.qlcstream.catalog.domain.PersonDetails;
import dev.qlcstream.catalog.domain.SeasonDetails;
import dev.qlcstream.catalog.domain.Series;
import dev.qlcstream.catalog.domain.SeriesDetails;
import dev.qlcstream.catalog.domain.SeriesEpisode;
import dev.qlcstream.catalog.domain.SeriesSeason;
import dev.qlcstream.catalog.application.service.CatalogService;

@Validated
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final BrowseCatalogUseCase browseCatalog;
    private final ViewMovieDetailsUseCase viewMovieDetails;
    private final CatalogService catalogService;

    public CatalogController(BrowseCatalogUseCase browseCatalog, ViewMovieDetailsUseCase viewMovieDetails, CatalogService catalogService) {
        this.browseCatalog = browseCatalog;
        this.viewMovieDetails = viewMovieDetails;
        this.catalogService = catalogService;
    }

    @GetMapping("/trending")
    CatalogPageResponse<CatalogMovieResponse> trending(
            @RequestParam(defaultValue = "pt-BR") String language,
            @RequestParam(defaultValue = "1") @Min(1) @Max(500) int page,
            @RequestParam(defaultValue = "POPULARITY") CatalogSort sort) {
        return CatalogPageResponse.from(browseCatalog.trending(language, page, sort), CatalogMovieResponse::from);
    }

    @GetMapping("/discover")
    CatalogPageResponse<CatalogMovieResponse> discover(
            @RequestParam(defaultValue = "POPULAR") CatalogCollection collection,
            @RequestParam(defaultValue = "pt-BR") String language,
            @RequestParam(defaultValue = "1") @Min(1) @Max(500) int page,
            @RequestParam(required = false) CatalogSort sort) {
        return CatalogPageResponse.from(browseCatalog.discover(collection, language, page, sort), CatalogMovieResponse::from);
    }

    @GetMapping("/search")
    CatalogPageResponse<CatalogMovieResponse> search(
            @RequestParam @NotBlank String query,
            @RequestParam(defaultValue = "pt-BR") String language,
            @RequestParam(defaultValue = "1") @Min(1) @Max(500) int page,
            @RequestParam(required = false) CatalogSort sort) {
        return CatalogPageResponse.from(browseCatalog.search(query, language, page, sort), CatalogMovieResponse::from);
    }

    @GetMapping("/recommendations")
    List<PersonalizedRecommendationResponse> recommendations(
            @RequestParam(required = false) List<Long> movieIds,
            @RequestParam(required = false) List<Long> seriesIds,
            @RequestParam(defaultValue = "pt-BR") String language,
            @RequestParam(defaultValue = "0") @Min(0) @Max(20) int page) {
        return catalogService.personalizedRecommendations(movieIds == null ? List.of() : movieIds,
                seriesIds == null ? List.of() : seriesIds, language, page).stream()
                .map(PersonalizedRecommendationResponse::from).toList();
    }

    @GetMapping("/people/search")
    List<PersonResponse> searchPeople(@RequestParam @NotBlank String query, @RequestParam(defaultValue = "pt-BR") String language) {
        return catalogService.searchPeople(query, language).stream().map(PersonResponse::from).toList();
    }

    @GetMapping("/people/{tmdbId}")
    PersonDetailsResponse personDetails(@PathVariable @Positive long tmdbId, @RequestParam(defaultValue = "pt-BR") String language) {
        return PersonDetailsResponse.from(catalogService.personDetails(tmdbId, language));
    }

    @GetMapping("/series/trending")
    CatalogPageResponse<SeriesResponse> trendingSeries(@RequestParam(defaultValue = "pt-BR") String language,
            @RequestParam(defaultValue = "1") @Min(1) @Max(500) int page,
            @RequestParam(defaultValue = "POPULARITY") CatalogSort sort) {
        return CatalogPageResponse.from(catalogService.trendingSeries(language, page, sort), SeriesResponse::from);
    }

    @GetMapping("/series/discover")
    CatalogPageResponse<SeriesResponse> discoverSeries(
            @RequestParam(defaultValue = "POPULAR") CatalogCollection collection,
            @RequestParam(defaultValue = "pt-BR") String language,
            @RequestParam(defaultValue = "1") @Min(1) @Max(500) int page,
            @RequestParam(required = false) CatalogSort sort) {
        return CatalogPageResponse.from(catalogService.discoverSeries(collection, language, page, sort), SeriesResponse::from);
    }

    @GetMapping("/series/search")
    CatalogPageResponse<SeriesResponse> searchSeries(@RequestParam @NotBlank String query, @RequestParam(defaultValue = "pt-BR") String language,
            @RequestParam(defaultValue = "1") @Min(1) @Max(500) int page, @RequestParam(required = false) CatalogSort sort) {
        return CatalogPageResponse.from(catalogService.searchSeries(query, language, page, sort), SeriesResponse::from);
    }

    @GetMapping("/series/{tmdbId}")
    SeriesDetailsResponse seriesDetails(@PathVariable @Positive long tmdbId, @RequestParam(defaultValue = "pt-BR") String language) {
        return SeriesDetailsResponse.from(catalogService.seriesDetails(tmdbId, language));
    }

    @GetMapping("/series/{tmdbId}/seasons/{seasonNumber}")
    SeasonDetailsResponse seasonDetails(@PathVariable @Positive long tmdbId, @PathVariable @Positive int seasonNumber,
            @RequestParam(defaultValue = "pt-BR") String language) {
        return SeasonDetailsResponse.from(catalogService.seasonDetails(tmdbId, seasonNumber, language));
    }

    @GetMapping("/{tmdbId}")
    MovieDetailsResponse details(
            @PathVariable @Positive long tmdbId,
            @RequestParam(defaultValue = "pt-BR") String language) {
        return MovieDetailsResponse.from(viewMovieDetails.view(tmdbId, language));
    }

    record CatalogPageResponse<T>(List<T> items, int page, int totalPages, long totalResults, boolean hasNext) {
        static <T, R> CatalogPageResponse<R> from(CatalogPage<T> page, java.util.function.Function<T, R> mapper) {
            return new CatalogPageResponse<>(page.items().stream().map(mapper).toList(), page.page(), page.totalPages(), page.totalResults(), page.hasNext());
        }
    }

    record CatalogMovieResponse(
            Long id,
            long tmdbId,
            String title,
            String originalTitle,
            LocalDate releaseDate,
            String overview,
            String posterPath,
            String backdropPath,
            BigDecimal voteAverage,
            BigDecimal popularity) {

        static CatalogMovieResponse from(Movie movie) {
            return new CatalogMovieResponse(movie.id(), movie.tmdbId(), movie.title(), movie.originalTitle(),
                    movie.releaseDate(), movie.overview(), movie.posterPath(), movie.backdropPath(), movie.voteAverage(),
                    movie.popularity());
        }
    }

    record MovieDetailsResponse(
            Long id,
            long tmdbId,
            String title,
            String originalTitle,
            LocalDate releaseDate,
            String overview,
            String posterPath,
            String backdropPath,
            BigDecimal voteAverage,
            BigDecimal popularity,
            String tagline,
            int runtimeMinutes,
            List<String> genres,
            String director,
            List<String> writers,
            List<CastMemberResponse> cast,
            String trailerUrl,
            List<CatalogMovieResponse> recommendations) {

        static MovieDetailsResponse from(MovieDetails details) {
            var movie = details.movie();
            return new MovieDetailsResponse(movie.id(), movie.tmdbId(), movie.title(), movie.originalTitle(),
                    movie.releaseDate(), movie.overview(), movie.posterPath(), movie.backdropPath(),
                    movie.voteAverage(), movie.popularity(), details.tagline(), details.runtimeMinutes(), details.genres(), details.director(),
                    details.writers(), details.cast().stream().map(CastMemberResponse::from).toList(), details.trailerUrl(),
                    details.recommendations().stream().map(CatalogMovieResponse::from).toList());
        }
    }

    record CastMemberResponse(long tmdbId, String name, String character, String profilePath) {
        static CastMemberResponse from(MovieDetails.CastMember member) {
            return new CastMemberResponse(member.tmdbId(), member.name(), member.character(), member.profilePath());
        }
    }

    record PersonResponse(long tmdbId, String name, String profilePath, List<CatalogMovieResponse> knownFor) {
        static PersonResponse from(Person person) { return new PersonResponse(person.tmdbId(), person.name(), person.profilePath(), person.knownFor().stream().map(CatalogMovieResponse::from).toList()); }
    }

    record PersonDetailsResponse(long tmdbId, String name, String profilePath, String biography, List<CatalogMovieResponse> movies) {
        static PersonDetailsResponse from(PersonDetails details) { return new PersonDetailsResponse(details.person().tmdbId(), details.person().name(), details.person().profilePath(), details.biography(), details.movies().stream().map(CatalogMovieResponse::from).toList()); }
    }

    record SeriesResponse(long tmdbId, String name, String originalName, LocalDate firstAirDate, String overview,
            String posterPath, String backdropPath, BigDecimal voteAverage, BigDecimal popularity) {
        static SeriesResponse from(Series series) { return new SeriesResponse(series.tmdbId(), series.name(), series.originalName(), series.firstAirDate(), series.overview(), series.posterPath(), series.backdropPath(), series.voteAverage(), series.popularity()); }
    }

    record SeriesDetailsResponse(SeriesResponse series, String tagline, List<String> genres, String status, int numberOfSeasons,
            List<CastMemberResponse> cast, String trailerUrl, List<SeasonResponse> seasons) {
        static SeriesDetailsResponse from(SeriesDetails details) { return new SeriesDetailsResponse(SeriesResponse.from(details.series()), details.tagline(), details.genres(), details.status(), details.numberOfSeasons(), details.cast().stream().map(CastMemberResponse::from).toList(), details.trailerUrl(), details.seasons().stream().map(SeasonResponse::from).toList()); }
    }

    record SeasonResponse(long tmdbId, int seasonNumber, String name, String overview, LocalDate airDate, String posterPath, int episodeCount) {
        static SeasonResponse from(SeriesSeason season) { return new SeasonResponse(season.tmdbId(), season.seasonNumber(), season.name(), season.overview(), season.airDate(), season.posterPath(), season.episodeCount()); }
    }

    record SeasonDetailsResponse(SeasonResponse season, List<EpisodeResponse> episodes) {
        static SeasonDetailsResponse from(SeasonDetails details) { return new SeasonDetailsResponse(SeasonResponse.from(details.season()), details.episodes().stream().map(EpisodeResponse::from).toList()); }
    }

    record EpisodeResponse(long tmdbId, int episodeNumber, String name, String overview, LocalDate airDate, String stillPath, int runtimeMinutes) {
        static EpisodeResponse from(SeriesEpisode episode) { return new EpisodeResponse(episode.tmdbId(), episode.episodeNumber(), episode.name(), episode.overview(), episode.airDate(), episode.stillPath(), episode.runtimeMinutes()); }
    }
    record PersonalizedRecommendationResponse(String mediaType, long tmdbId, String title, String originalTitle,
            LocalDate releaseDate, String posterPath, BigDecimal voteAverage) {
        static PersonalizedRecommendationResponse from(CatalogService.PersonalizedRecommendation item) {
            return new PersonalizedRecommendationResponse(item.mediaType(), item.tmdbId(), item.title(), item.originalTitle(),
                    item.releaseDate(), item.posterPath(), item.voteAverage());
        }
    }


}
