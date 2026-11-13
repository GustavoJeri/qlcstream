package dev.qlcstream.catalog.infrastructure.tmdb;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Comparator;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

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
import dev.qlcstream.catalog.domain.SeriesEpisode;
import dev.qlcstream.catalog.domain.SeriesSeason;
import dev.qlcstream.system.IntegrationSettings;

@Component
public class TmdbMovieAdapter implements MovieMetadataProvider {
    private final TmdbProperties properties;
    private final TmdbCatalogCache cache;
    private final IntegrationSettings settings;

    @Autowired
    TmdbMovieAdapter(TmdbProperties properties, TmdbCatalogCache cache, IntegrationSettings settings) {
        this.properties = properties;
        this.cache = cache;
        this.settings = settings;
    }

    public TmdbMovieAdapter(TmdbProperties properties, IntegrationSettings settings) {
        this(properties, new TmdbCatalogCache(300, Duration.ofMinutes(10), Duration.ofMinutes(3)), settings);
    }

    public TmdbMovieAdapter(TmdbProperties properties) {
        this(properties, null);
    }

    @Override
    public CatalogPage<Movie> trending(String language, int page, CatalogSort sort) {
        var configuration = requireConfiguration();
        return cache.get(cache.key(configuration, "trending-movie", language, page, sort), cache.catalogTtl(), () -> {
        var response = client(configuration).get()
                .uri(uri -> uri.path("/trending/movie/week")
                        .queryParam("language", language)
                        .queryParam("page", page)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken())
                .retrieve()
                .body(TmdbMoviePage.class);
        return map(response);
        });
    }

    @Override
    public CatalogPage<Movie> discover(CatalogCollection collection, String language, int page, CatalogSort sort) {
        var configuration = requireConfiguration();
        return cache.get(cache.key(configuration, "discover-movie", collection, language, page, sort),
                cache.catalogTtl(), () -> {
                    var response = client(configuration).get()
                            .uri(uri -> discoveryQuery(uri.path("/discover/movie").queryParam("include_video", false),
                                    collection, language, page, sort, "primary_release_date", "title").build())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken())
                            .retrieve().body(TmdbMoviePage.class);
                    return map(response);
                });
    }

    @Override
    public CatalogPage<Series> discoverSeries(CatalogCollection collection, String language, int page, CatalogSort sort) {
        var configuration = requireConfiguration();
        return cache.get(cache.key(configuration, "discover-series", collection, language, page, sort),
                cache.catalogTtl(), () -> {
                    var response = client(configuration).get()
                            .uri(uri -> discoveryQuery(uri.path("/discover/tv").queryParam("include_null_first_air_dates", false),
                                    collection, language, page, sort, "first_air_date", "name").build())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken())
                            .retrieve().body(TmdbSeriesPage.class);
                    return map(response);
                });
    }

    private UriBuilder discoveryQuery(UriBuilder uri, CatalogCollection collection, String language,
                                      int page, CatalogSort sort, String dateField, String titleField) {
        var defaultSort = switch (collection) {
            case TOP_RATED -> CatalogSort.RATING;
            case RECENT -> CatalogSort.NEWEST;
            default -> CatalogSort.POPULARITY;
        };
        var effectiveSort = sort == null ? defaultSort : sort;
        String sortBy = switch (effectiveSort) {
            case POPULARITY -> "popularity.desc";
            case RATING -> "vote_average.desc";
            case NEWEST -> dateField + ".desc";
            case OLDEST -> dateField + ".asc";
            case TITLE -> titleField + ".asc";
        };
        uri.queryParam("language", language).queryParam("page", page)
                .queryParam("include_adult", false)
                .queryParam("sort_by", sortBy);
        switch (collection) {
            case POPULAR -> uri.queryParam("vote_count.gte", 100);
            case TOP_RATED -> uri.queryParam("vote_count.gte", 500);
            case ESTABLISHED -> uri.queryParam("vote_count.gte", 100)
                    .queryParam(dateField + ".lte", LocalDate.now().minusYears(2));
            case RECENT -> uri.queryParam(dateField + ".lte", LocalDate.now());
        }
        return uri;
    }

    @Override
    public CatalogPage<Movie> search(String query, String language, int page, CatalogSort sort) {
        var configuration = requireConfiguration();
        return cache.get(cache.key(configuration, "search-movie", query, language, page, sort), cache.searchTtl(), () -> {
        var response = client(configuration).get()
                .uri(uri -> uri.path("/search/movie")
                        .queryParam("query", query)
                        .queryParam("language", language)
                        .queryParam("page", page)
                        .queryParam("include_adult", false)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken())
                .retrieve()
                .body(TmdbMoviePage.class);
        return map(response);
        });
    }

    @Override
    public MovieDetails details(long tmdbId, String language) {
        var configuration = requireConfiguration();
        return cache.get(cache.key(configuration, "movie-details", tmdbId, language), Duration.ofMinutes(30), () -> {
        var response = client(configuration).get()
            .uri(uri -> uri.path("/movie/{tmdbId}")
                        .queryParam("language", language)
                        .queryParam("append_to_response", "credits,videos,recommendations")
                        .build(tmdbId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken())
                .retrieve()
                .body(TmdbMovieDetails.class);
        if (response == null) {
            throw new IllegalStateException("TMDB não retornou detalhes para o filme solicitado.");
        }
        return response.toDomain();
        });
    }

    @Override
    public List<Person> searchPeople(String query, String language) {
        var configuration = requireConfiguration();
        var response = client(configuration).get().uri(uri -> uri.path("/search/person").queryParam("query", query)
                .queryParam("language", language).queryParam("include_adult", false).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken()).retrieve().body(TmdbPersonPage.class);
        return response == null || response.results() == null ? List.of() : response.results().stream().map(TmdbPerson::toDomain).toList();
    }

    @Override
    public PersonDetails personDetails(long tmdbId, String language) {
        var configuration = requireConfiguration();
        var response = client(configuration).get().uri(uri -> uri.path("/person/{tmdbId}").queryParam("language", language)
                .queryParam("append_to_response", "movie_credits").build(tmdbId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken()).retrieve().body(TmdbPersonDetails.class);
        if (response == null) throw new IllegalStateException("TMDB não retornou dados para a pessoa solicitada.");
        return response.toDomain();
    }

    @Override
    public CatalogPage<Series> trendingSeries(String language, int page, CatalogSort sort) {
        var configuration = requireConfiguration();
        return cache.get(cache.key(configuration, "trending-series", language, page, sort), cache.catalogTtl(), () -> {
        var response = client(configuration).get().uri(uri -> uri.path("/trending/tv/week").queryParam("language", language)
                .queryParam("page", page).build()).header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken())
                .retrieve().body(TmdbSeriesPage.class);
        return map(response);
        });
    }

    @Override
    public CatalogPage<Series> searchSeries(String query, String language, int page, CatalogSort sort) {
        var configuration = requireConfiguration();
        return cache.get(cache.key(configuration, "search-series", query, language, page, sort), cache.searchTtl(), () -> {
        var response = client(configuration).get().uri(uri -> uri.path("/search/tv").queryParam("query", query)
                .queryParam("language", language).queryParam("page", page).queryParam("include_adult", false).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken()).retrieve().body(TmdbSeriesPage.class);
        return map(response);
        });
    }

    @Override
    public List<Series> recommendedSeries(long tmdbId, String language) {
        var configuration = requireConfiguration();
        return cache.get(cache.key(configuration, "series-recommendations", tmdbId, language), Duration.ofMinutes(30), () -> {
            var response = client(configuration).get().uri(uri -> uri.path("/tv/{tmdbId}/recommendations")
                    .queryParam("language", language).build(tmdbId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken()).retrieve().body(TmdbSeriesPage.class);
            return map(response).items().stream().limit(8).toList();
        });
    }

    @Override
    public SeriesDetails seriesDetails(long tmdbId, String language) {
        var configuration = requireConfiguration();
        return cache.get(cache.key(configuration, "series-details", tmdbId, language), Duration.ofMinutes(30), () -> {
        var response = client(configuration).get().uri(uri -> uri.path("/tv/{tmdbId}").queryParam("language", language)
                .queryParam("append_to_response", "credits,videos").build(tmdbId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken()).retrieve().body(TmdbSeriesDetails.class);
        if (response == null) throw new IllegalStateException("TMDB não retornou detalhes para a série solicitada.");
        return response.toDomain();
        });
    }

    @Override
    public SeasonDetails seasonDetails(long seriesTmdbId, int seasonNumber, String language) {
        var configuration = requireConfiguration();
        var response = client(configuration).get().uri(uri -> uri.path("/tv/{seriesTmdbId}/season/{seasonNumber}")
                .queryParam("language", language).build(seriesTmdbId, seasonNumber))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiToken()).retrieve().body(TmdbSeasonDetails.class);
        if (response == null) throw new IllegalStateException("TMDB não retornou episódios para a temporada solicitada.");
        return response.toDomain();
    }

    private TmdbProperties requireConfiguration() {
        var configuration = properties;
        if (settings != null) {
            var effective = settings.effective("tmdb");
            configuration = new TmdbProperties(effective.baseUrl(), effective.secret());
        }
        if (!configuration.configured()) throw new TmdbNotConfiguredException();
        return configuration;
    }

    private RestClient client(TmdbProperties configuration) {
        return RestClient.builder().baseUrl(configuration.baseUrl()).build();
    }

    private static CatalogPage<Movie> map(TmdbMoviePage page) {
        if (page == null) return new CatalogPage<>(List.of(), 1, 1, 0);
        var items = page.results() == null ? List.<Movie>of() : page.results().stream().map(TmdbMovie::toDomain).toList();
        return new CatalogPage<>(items, page.page(), page.totalPages(), page.totalResults());
    }

    private static CatalogPage<Series> map(TmdbSeriesPage page) {
        if (page == null) return new CatalogPage<>(List.of(), 1, 1, 0);
        var items = page.results() == null ? List.<Series>of() : page.results().stream().map(TmdbSeries::toDomain).toList();
        return new CatalogPage<>(items, page.page(), page.totalPages(), page.totalResults());
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private record TmdbMoviePage(int page, @JsonProperty("total_pages") int totalPages, @JsonProperty("total_results") long totalResults, List<TmdbMovie> results) {
    }

    private record TmdbMovie(
            long id,
            String title,
            @JsonProperty("original_title") String originalTitle,
            @JsonProperty("release_date") String releaseDate,
            String overview,
            @JsonProperty("poster_path") String posterPath,
            @JsonProperty("backdrop_path") String backdropPath,
            @JsonProperty("vote_average") BigDecimal voteAverage,
            BigDecimal popularity) {

        Movie toDomain() {
            return new Movie(null, id, null, title, originalTitle, parseDate(releaseDate), overview, posterPath,
                    backdropPath, voteAverage, popularity);
        }
    }

    private record TmdbMovieDetails(
            long id,
            String title,
            @JsonProperty("original_title") String originalTitle,
            @JsonProperty("release_date") String releaseDate,
            String overview,
            String tagline,
            int runtime,
            @JsonProperty("poster_path") String posterPath,
            @JsonProperty("backdrop_path") String backdropPath,
            @JsonProperty("vote_average") BigDecimal voteAverage,
            BigDecimal popularity,
            List<TmdbGenre> genres,
            TmdbCredits credits,
            TmdbVideos videos,
            TmdbRecommendations recommendations) {

        MovieDetails toDomain() {
            var movie = new Movie(null, id, null, title, originalTitle, parseDate(releaseDate), overview, posterPath,
                    backdropPath, voteAverage, popularity);
            var genreNames = genres == null ? List.<String>of() : genres.stream().map(TmdbGenre::name).toList();
            var crew = credits == null || credits.crew() == null ? List.<TmdbCrew>of() : credits.crew();
            var director = crew.stream().filter(member -> "Director".equals(member.job())).map(TmdbCrew::name).findFirst().orElse(null);
            var writers = crew.stream().filter(member -> List.of("Writer", "Screenplay", "Story").contains(member.job()))
                    .map(TmdbCrew::name).filter(Objects::nonNull).distinct().limit(3).toList();
            var cast = credits == null || credits.cast() == null ? List.<TmdbCast>of() : credits.cast();
            var castMembers = cast.stream().limit(10).map(member -> new MovieDetails.CastMember(member.id(), member.name(), member.character(), member.profilePath())).toList();
            var trailerUrl = preferredTrailerUrl(videos);
            var related = recommendations == null || recommendations.results() == null ? List.<Movie>of()
                    : recommendations.results().stream().map(TmdbMovie::toDomain).limit(8).toList();
            return new MovieDetails(movie, tagline, runtime, genreNames, director, writers, castMembers, trailerUrl, related);
        }
    }

    private record TmdbGenre(String name) {
    }

    private record TmdbCredits(List<TmdbCast> cast, List<TmdbCrew> crew) {
    }

    private record TmdbCast(long id, String name, String character, @JsonProperty("profile_path") String profilePath) {
    }

    private record TmdbCrew(String name, String job) {
    }

    private record TmdbVideos(List<TmdbVideo> results) {
    }

    private static String preferredTrailerUrl(TmdbVideos videos) {
        if (videos == null || videos.results() == null) return null;
        return videos.results().stream()
                .filter(video -> "YouTube".equalsIgnoreCase(video.site()) && "Trailer".equalsIgnoreCase(video.type()))
                .sorted(Comparator.comparingInt(TmdbMovieAdapter::trailerLanguagePriority)
                        .thenComparing(video -> !Boolean.TRUE.equals(video.official())))
                .map(video -> "https://www.youtube.com/watch?v=" + video.key() + "&cc_lang_pref=pt-BR&cc_load_policy=1")
                .findFirst().orElse(null);
    }

    private static int trailerLanguagePriority(TmdbVideo video) {
        if ("en".equalsIgnoreCase(video.language())) return 0;
        if ("pt".equalsIgnoreCase(video.language())) return 1;
        return 2;
    }

    private record TmdbVideo(String key, String site, String type, Boolean official,
            @JsonProperty("iso_639_1") String language) {
    }

    private record TmdbRecommendations(List<TmdbMovie> results) {
    }

    private record TmdbPersonPage(List<TmdbPerson> results) {
    }

    private record TmdbPerson(long id, String name, @JsonProperty("profile_path") String profilePath,
            @JsonProperty("known_for") List<TmdbMovie> knownFor) {
        Person toDomain() { return new Person(id, name, profilePath, knownFor == null ? List.of() : knownFor.stream().map(TmdbMovie::toDomain).toList()); }
    }

    private record TmdbPersonDetails(long id, String name, @JsonProperty("profile_path") String profilePath,
            String biography, @JsonProperty("movie_credits") TmdbMovieCredits movieCredits) {
        PersonDetails toDomain() {
            var movies = movieCredits == null ? List.<Movie>of() : java.util.stream.Stream.concat(
                    movieCredits.cast() == null ? java.util.stream.Stream.empty() : movieCredits.cast().stream().map(TmdbMovie::toDomain),
                    movieCredits.crew() == null ? java.util.stream.Stream.empty() : movieCredits.crew().stream().map(TmdbMovie::toDomain))
                    .collect(java.util.stream.Collectors.toMap(Movie::tmdbId, movie -> movie, (first, second) -> first)).values().stream()
                    .sorted((first, second) -> java.util.Comparator.nullsLast(java.math.BigDecimal::compareTo).compare(second.popularity(), first.popularity())).toList();
            return new PersonDetails(new Person(id, name, profilePath, List.of()), biography, movies);
        }
    }

    private record TmdbMovieCredits(List<TmdbMovie> cast, List<TmdbMovie> crew) {
    }

    private record TmdbSeriesPage(int page, @JsonProperty("total_pages") int totalPages, @JsonProperty("total_results") long totalResults, List<TmdbSeries> results) {
    }

    private record TmdbSeries(long id, String name, @JsonProperty("original_name") String originalName,
            @JsonProperty("first_air_date") String firstAirDate, String overview, @JsonProperty("poster_path") String posterPath,
            @JsonProperty("backdrop_path") String backdropPath, @JsonProperty("vote_average") BigDecimal voteAverage,
            BigDecimal popularity) {
        Series toDomain() { return new Series(id, name, originalName, parseDate(firstAirDate), overview, posterPath, backdropPath, voteAverage, popularity); }
    }

    private record TmdbSeriesDetails(long id, String name, @JsonProperty("original_name") String originalName,
            @JsonProperty("first_air_date") String firstAirDate, String overview, String tagline,
            @JsonProperty("poster_path") String posterPath, @JsonProperty("backdrop_path") String backdropPath,
            @JsonProperty("vote_average") BigDecimal voteAverage, BigDecimal popularity, List<TmdbGenre> genres,
            String status, @JsonProperty("number_of_seasons") int numberOfSeasons, List<TmdbSeason> seasons,
            TmdbCredits credits, TmdbVideos videos) {
        SeriesDetails toDomain() {
            var series = new TmdbSeries(id, name, originalName, firstAirDate, overview, posterPath, backdropPath, voteAverage, popularity).toDomain();
            var cast = credits == null || credits.cast() == null ? List.<TmdbCast>of() : credits.cast();
            var members = cast.stream().limit(10).map(member -> new MovieDetails.CastMember(member.id(), member.name(), member.character(), member.profilePath())).toList();
            var trailer = preferredTrailerUrl(videos);
            return new SeriesDetails(series, tagline, genres == null ? List.of() : genres.stream().map(TmdbGenre::name).toList(), status,
                    numberOfSeasons, members, trailer, seasons == null ? List.of() : seasons.stream().filter(season -> season.seasonNumber() > 0).map(TmdbSeason::toDomain).toList());
        }
    }

    private record TmdbSeason(long id, @JsonProperty("season_number") int seasonNumber, String name, String overview,
            @JsonProperty("air_date") String airDate, @JsonProperty("poster_path") String posterPath,
            @JsonProperty("episode_count") int episodeCount) {
        SeriesSeason toDomain() { return new SeriesSeason(id, seasonNumber, name, overview, parseDate(airDate), posterPath, episodeCount); }
    }

    private record TmdbSeasonDetails(long id, @JsonProperty("season_number") int seasonNumber, String name, String overview,
            @JsonProperty("air_date") String airDate, @JsonProperty("poster_path") String posterPath, List<TmdbEpisode> episodes) {
        SeasonDetails toDomain() {
            var values = episodes == null ? List.<SeriesEpisode>of() : episodes.stream().map(TmdbEpisode::toDomain).toList();
            return new SeasonDetails(new SeriesSeason(id, seasonNumber, name, overview, parseDate(airDate), posterPath, values.size()), values);
        }
    }

    private record TmdbEpisode(long id, @JsonProperty("episode_number") int episodeNumber, String name, String overview,
            @JsonProperty("air_date") String airDate, @JsonProperty("still_path") String stillPath,
            @JsonProperty("runtime") Integer runtimeMinutes) {
        SeriesEpisode toDomain() { return new SeriesEpisode(id, episodeNumber, name, overview, parseDate(airDate), stillPath, runtimeMinutes == null ? 0 : runtimeMinutes); }
    }
}
