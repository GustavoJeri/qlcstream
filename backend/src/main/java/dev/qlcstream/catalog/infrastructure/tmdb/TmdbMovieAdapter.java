package dev.qlcstream.catalog.infrastructure.tmdb;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Comparator;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.qlcstream.catalog.application.port.out.MovieMetadataProvider;
import dev.qlcstream.catalog.domain.CatalogCollection;
import dev.qlcstream.catalog.domain.Movie;
import dev.qlcstream.catalog.domain.MovieDetails;
import dev.qlcstream.catalog.domain.Person;
import dev.qlcstream.catalog.domain.PersonDetails;
import dev.qlcstream.catalog.domain.SeasonDetails;
import dev.qlcstream.catalog.domain.Series;
import dev.qlcstream.catalog.domain.SeriesDetails;
import dev.qlcstream.catalog.domain.SeriesEpisode;
import dev.qlcstream.catalog.domain.SeriesSeason;

@Component
public class TmdbMovieAdapter implements MovieMetadataProvider {
    private dev.qlcstream.system.IntegrationSettings settings;

    private final RestClient client;
    private final TmdbProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public TmdbMovieAdapter(TmdbProperties properties, dev.qlcstream.system.IntegrationSettings settings) {
        this(properties);
        this.settings = settings;
    }

    public TmdbMovieAdapter(TmdbProperties properties) {
        this.properties = properties;
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).build();
    }

    @Override
    public List<Movie> trending(String language, int page) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).trending(language, page); }
        requireConfiguration();
        var response = client.get()
                .uri(uri -> uri.path("/trending/movie/week")
                        .queryParam("language", language)
                        .queryParam("page", page)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                .retrieve()
                .body(TmdbMoviePage.class);
        return map(response);
    }

    @Override
    public List<Movie> discover(CatalogCollection collection, String language, int page) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).discover(collection, language, page); }
        requireConfiguration();
        var request = client.get()
                .uri(uri -> {
                    var builder = uri.path("/discover/movie")
                            .queryParam("language", language)
                            .queryParam("page", page)
                            .queryParam("include_adult", false)
                            .queryParam("include_video", false);
                    switch (collection) {
                        case POPULAR -> builder
                                .queryParam("sort_by", "popularity.desc")
                                .queryParam("vote_count.gte", 100);
                        case TOP_RATED -> builder
                                .queryParam("sort_by", "vote_average.desc")
                                .queryParam("vote_count.gte", 500);
                        case ESTABLISHED -> builder
                                .queryParam("sort_by", "popularity.desc")
                                .queryParam("vote_count.gte", 100)
                                .queryParam("primary_release_date.lte", LocalDate.now().minusYears(2));
                        case RECENT -> builder
                                .queryParam("sort_by", "primary_release_date.desc")
                                .queryParam("primary_release_date.lte", LocalDate.now());
                    }
                    return builder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                .retrieve()
                .body(TmdbMoviePage.class);
        return map(request);
    }

    @Override
    public List<Movie> search(String query, String language, int page) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).search(query, language, page); }
        requireConfiguration();
        var response = client.get()
                .uri(uri -> uri.path("/search/movie")
                        .queryParam("query", query)
                        .queryParam("language", language)
                        .queryParam("page", page)
                        .queryParam("include_adult", false)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                .retrieve()
                .body(TmdbMoviePage.class);
        return map(response);
    }

    @Override
    public MovieDetails details(long tmdbId, String language) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).details(tmdbId, language); }
        requireConfiguration();
        var response = client.get()
            .uri(uri -> uri.path("/movie/{tmdbId}")
                        .queryParam("language", language)
                        .queryParam("append_to_response", "credits,videos,recommendations")
                        .build(tmdbId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                .retrieve()
                .body(TmdbMovieDetails.class);
        if (response == null) {
            throw new IllegalStateException("TMDB não retornou detalhes para o filme solicitado.");
        }
        return response.toDomain();
    }

    @Override
    public List<Person> searchPeople(String query, String language) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).searchPeople(query, language); }
        requireConfiguration();
        var response = client.get().uri(uri -> uri.path("/search/person").queryParam("query", query)
                .queryParam("language", language).queryParam("include_adult", false).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken()).retrieve().body(TmdbPersonPage.class);
        return response == null || response.results() == null ? List.of() : response.results().stream().map(TmdbPerson::toDomain).toList();
    }

    @Override
    public PersonDetails personDetails(long tmdbId, String language) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).personDetails(tmdbId, language); }
        requireConfiguration();
        var response = client.get().uri(uri -> uri.path("/person/{tmdbId}").queryParam("language", language)
                .queryParam("append_to_response", "movie_credits").build(tmdbId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken()).retrieve().body(TmdbPersonDetails.class);
        if (response == null) throw new IllegalStateException("TMDB não retornou dados para a pessoa solicitada.");
        return response.toDomain();
    }

    @Override
    public List<Series> trendingSeries(String language, int page) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).trendingSeries(language, page); }
        requireConfiguration();
        var response = client.get().uri(uri -> uri.path("/trending/tv/week").queryParam("language", language)
                .queryParam("page", page).build()).header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                .retrieve().body(TmdbSeriesPage.class);
        return response == null || response.results() == null ? List.of() : response.results().stream().map(TmdbSeries::toDomain).toList();
    }

    @Override
    public List<Series> searchSeries(String query, String language, int page) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).searchSeries(query, language, page); }
        requireConfiguration();
        var response = client.get().uri(uri -> uri.path("/search/tv").queryParam("query", query)
                .queryParam("language", language).queryParam("page", page).queryParam("include_adult", false).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken()).retrieve().body(TmdbSeriesPage.class);
        return response == null || response.results() == null ? List.of() : response.results().stream().map(TmdbSeries::toDomain).toList();
    }

    @Override
    public SeriesDetails seriesDetails(long tmdbId, String language) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).seriesDetails(tmdbId, language); }
        requireConfiguration();
        var response = client.get().uri(uri -> uri.path("/tv/{tmdbId}").queryParam("language", language)
                .queryParam("append_to_response", "credits,videos").build(tmdbId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken()).retrieve().body(TmdbSeriesDetails.class);
        if (response == null) throw new IllegalStateException("TMDB não retornou detalhes para a série solicitada.");
        return response.toDomain();
    }

    @Override
    public SeasonDetails seasonDetails(long seriesTmdbId, int seasonNumber, String language) {
        if (settings != null) { var c = settings.effective("tmdb"); return new TmdbMovieAdapter(new TmdbProperties(c.baseUrl(), c.secret())).seasonDetails(seriesTmdbId, seasonNumber, language); }
        requireConfiguration();
        var response = client.get().uri(uri -> uri.path("/tv/{seriesTmdbId}/season/{seasonNumber}")
                .queryParam("language", language).build(seriesTmdbId, seasonNumber))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken()).retrieve().body(TmdbSeasonDetails.class);
        if (response == null) throw new IllegalStateException("TMDB não retornou episódios para a temporada solicitada.");
        return response.toDomain();
    }

    private void requireConfiguration() {
        if (!properties.configured()) {
            throw new TmdbNotConfiguredException();
        }
    }

    private static List<Movie> map(TmdbMoviePage page) {
        if (page == null || page.results() == null) {
            return List.of();
        }
        return page.results().stream().map(TmdbMovie::toDomain).toList();
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

    private record TmdbMoviePage(List<TmdbMovie> results) {
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

    private record TmdbSeriesPage(List<TmdbSeries> results) {
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
