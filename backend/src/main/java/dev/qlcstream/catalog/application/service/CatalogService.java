package dev.qlcstream.catalog.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.qlcstream.catalog.application.port.in.BrowseCatalogUseCase;
import dev.qlcstream.catalog.application.port.in.ViewMovieDetailsUseCase;
import dev.qlcstream.catalog.application.port.out.MovieCatalogRepository;
import dev.qlcstream.catalog.application.port.out.MovieMetadataProvider;
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

@Service
@Transactional
public class CatalogService implements BrowseCatalogUseCase, ViewMovieDetailsUseCase {

    private final MovieMetadataProvider metadataProvider;
    private final MovieCatalogRepository catalogRepository;

    public CatalogService(MovieMetadataProvider metadataProvider, MovieCatalogRepository catalogRepository) {
        this.metadataProvider = metadataProvider;
        this.catalogRepository = catalogRepository;
    }

    @Override
    public CatalogPage<Movie> trending(String language, int page, CatalogSort sort) {
        return sort(persist(metadataProvider.trending(language, page, sort)), sort);
    }

    @Override
    public CatalogPage<Movie> discover(CatalogCollection collection, String language, int page, CatalogSort sort) {
        return sort(persist(metadataProvider.discover(collection, language, page, sort)), sort);
    }

    @Override
    public CatalogPage<Movie> search(String query, String language, int page, CatalogSort sort) {
        return sort(persist(metadataProvider.search(query, language, page, sort)), sort);
    }

    private CatalogPage<Movie> sort(CatalogPage<Movie> page, CatalogSort sort) {
        if (sort == null) return page;
        var comparator = switch (sort) {
            case POPULARITY -> java.util.Comparator.comparing(Movie::popularity, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
            case RATING -> java.util.Comparator.comparing(Movie::voteAverage, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
            case NEWEST -> java.util.Comparator.comparing(Movie::releaseDate, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
            case OLDEST -> java.util.Comparator.comparing(Movie::releaseDate, java.util.Comparator.nullsLast(java.time.LocalDate::compareTo));
            case TITLE -> java.util.Comparator.comparing(Movie::title, java.text.Collator.getInstance(java.util.Locale.forLanguageTag("pt-BR")));
        };
        return new CatalogPage<>(page.items().stream().sorted(comparator.thenComparing(Movie::tmdbId)).toList(), page.page(), page.totalPages(), page.totalResults());
    }

    private CatalogPage<Series> sortSeries(CatalogPage<Series> page, CatalogSort sort) {
        if (sort == null) return page;
        var comparator = switch (sort) {
            case POPULARITY -> java.util.Comparator.comparing(Series::popularity, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
            case RATING -> java.util.Comparator.comparing(Series::voteAverage, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
            case NEWEST -> java.util.Comparator.comparing(Series::firstAirDate, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
            case OLDEST -> java.util.Comparator.comparing(Series::firstAirDate, java.util.Comparator.nullsLast(java.time.LocalDate::compareTo));
            case TITLE -> java.util.Comparator.comparing(Series::name, java.text.Collator.getInstance(java.util.Locale.forLanguageTag("pt-BR")));
        };
        return new CatalogPage<>(page.items().stream().sorted(comparator.thenComparing(Series::tmdbId)).toList(), page.page(), page.totalPages(), page.totalResults());
    }

    private CatalogPage<Movie> persist(CatalogPage<Movie> page) {
        return new CatalogPage<>(catalogRepository.saveAll(page.items()), page.page(), page.totalPages(), page.totalResults());
    }

    @Override
    public MovieDetails view(long tmdbId, String language) {
        var details = metadataProvider.details(tmdbId, language);
        var persistedMovie = catalogRepository.saveAll(List.of(details.movie())).getFirst();
        return new MovieDetails(persistedMovie, details.tagline(), details.runtimeMinutes(), details.genres(),
                details.director(), details.writers(), details.cast(), details.trailerUrl(), details.recommendations());
    }

    public List<Person> searchPeople(String query, String language) { return metadataProvider.searchPeople(query, language); }

    public PersonDetails personDetails(long tmdbId, String language) {
        var details = metadataProvider.personDetails(tmdbId, language);
        return new PersonDetails(details.person(), details.biography(), catalogRepository.saveAll(details.movies()));
    }

    @Transactional(readOnly = true)
    public List<PersonalizedRecommendation> personalizedRecommendations(List<Long> movieIds, List<Long> seriesIds, String language, int page) {
        var movieSeeds = movieIds.stream().filter(id -> id != null && id > 0).distinct().limit(3).toList();
        var seriesSeeds = seriesIds.stream().filter(id -> id != null && id > 0).distinct().limit(3).toList();
        var ownedMovies = new java.util.HashSet<>(movieSeeds);
        var ownedSeries = new java.util.HashSet<>(seriesSeeds);
        var sources = new java.util.ArrayList<List<PersonalizedRecommendation>>();
        for (var movieId : movieSeeds) {
            sources.add(metadataProvider.details(movieId, language).recommendations().stream()
                    .filter(movie -> !ownedMovies.contains(movie.tmdbId()))
                    .map(PersonalizedRecommendation::fromMovie)
                    .filter(item -> !isAdultTitle(item.title())).toList());
        }
        for (var seriesId : seriesSeeds) {
            sources.add(metadataProvider.recommendedSeries(seriesId, language).stream()
                    .filter(series -> !ownedSeries.contains(series.tmdbId()))
                    .map(PersonalizedRecommendation::fromSeries)
                    .filter(item -> !isAdultTitle(item.title())).toList());
        }

        var candidates = new java.util.ArrayList<PersonalizedRecommendation>();
        var seen = new java.util.HashSet<String>();
        for (var position = 0; position < 8; position++) {
            for (var source : sources) {
                if (position >= source.size()) continue;
                var candidate = source.get(position);
                if (seen.add(candidate.mediaType() + ":" + candidate.tmdbId())) candidates.add(candidate);
            }
        }
        if (candidates.isEmpty()) return List.of();
        var start = Math.floorMod(page, Math.max(1, (int) Math.ceil((double) candidates.size() / 12))) * 12;
        return java.util.stream.IntStream.range(0, Math.min(12, candidates.size()))
                .mapToObj(index -> candidates.get((start + index) % candidates.size())).toList();
    }

    @Transactional(readOnly = true)
    public CatalogPage<Series> trendingSeries(String language, int page, CatalogSort sort) { return sortSeries(metadataProvider.trendingSeries(language, page, sort), sort); }

    @Transactional(readOnly = true)
    public CatalogPage<Series> searchSeries(String query, String language, int page, CatalogSort sort) { return sortSeries(metadataProvider.searchSeries(query, language, page, sort), sort); }

    @Transactional(readOnly = true)
    public SeriesDetails seriesDetails(long tmdbId, String language) { return metadataProvider.seriesDetails(tmdbId, language); }

    @Transactional(readOnly = true)
    public SeasonDetails seasonDetails(long seriesTmdbId, int seasonNumber, String language) {
        return metadataProvider.seasonDetails(seriesTmdbId, seasonNumber, language);
    }
    private static boolean isAdultTitle(String title) {
        return title != null && title.matches("(?i).*\\bxxx\\b.*");
    }

    public record PersonalizedRecommendation(String mediaType, long tmdbId, String title, String originalTitle,
            java.time.LocalDate releaseDate, String posterPath, java.math.BigDecimal voteAverage) {
        static PersonalizedRecommendation fromMovie(Movie movie) {
            return new PersonalizedRecommendation("MOVIE", movie.tmdbId(), movie.title(), movie.originalTitle(),
                    movie.releaseDate(), movie.posterPath(), movie.voteAverage());
        }
        static PersonalizedRecommendation fromSeries(Series series) {
            return new PersonalizedRecommendation("SERIES", series.tmdbId(), series.name(), series.originalName(),
                    series.firstAirDate(), series.posterPath(), series.voteAverage());
        }
    }

}
