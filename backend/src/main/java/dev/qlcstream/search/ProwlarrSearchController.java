package dev.qlcstream.search;

import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import dev.qlcstream.search.config.SearchProwlarrProperties;

@RestController
@RequestMapping("/api/search")
public class ProwlarrSearchController {
    private dev.qlcstream.system.IntegrationSettings settings;
    private final RestClient client;
    private final SearchProwlarrProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public ProwlarrSearchController(SearchProwlarrProperties properties, dev.qlcstream.system.IntegrationSettings settings) {
        this(properties); this.settings = settings;
    }
    public ProwlarrSearchController(SearchProwlarrProperties properties) {
        this.properties = properties;
        this.client = RestClient.builder().baseUrl(properties.baseUrl()).build();
    }

    @GetMapping("/releases")
    List<ReleaseResponse> releases(@RequestParam @NotBlank String query, @RequestParam(required = false) Integer year) {
        if (settings != null) { var c = settings.effective("prowlarr"); return new ProwlarrSearchController(new SearchProwlarrProperties(c.baseUrl(), c.secret())).releases(query, year); }
        if (!properties.configured()) throw new IllegalStateException("Prowlarr não está configurado.");
        var response = client.get().uri(uri -> uri.path("/api/v1/search").queryParam("query", query).build())
                .header("X-Api-Key", properties.apiKey()).retrieve().body(ProwlarrRelease[].class);
        if (response == null) return List.of();
        return java.util.Arrays.stream(response).filter(release -> isMovieRelease(release, year))
                .filter(release -> !isAdultRelease(release.title()))
                .sorted(Comparator.comparingInt((ProwlarrRelease release) -> score(release, year)).reversed())
                .map(ReleaseResponse::from).toList();
    }

    @GetMapping("/series-releases")
    List<ReleaseResponse> seriesReleases(@RequestParam @NotBlank String query) {
        if (settings != null) { var c = settings.effective("prowlarr"); return new ProwlarrSearchController(new SearchProwlarrProperties(c.baseUrl(), c.secret())).seriesReleases(query); }
        if (!properties.configured()) throw new IllegalStateException("Prowlarr não está configurado.");
        var response = client.get().uri(uri -> uri.path("/api/v1/search").queryParam("query", query).build())
                .header("X-Api-Key", properties.apiKey()).retrieve().body(ProwlarrRelease[].class);
        if (response == null) return List.of();
        return java.util.Arrays.stream(response).filter(release -> EPISODE.matcher(release.title() == null ? "" : release.title()).find())
                .filter(release -> !isAdultRelease(release.title()))
                .sorted(Comparator.comparingInt((ProwlarrRelease release) -> score(release, null)).reversed()).map(ReleaseResponse::from).toList();
    }

    private static final Pattern EPISODE = Pattern.compile("(?i)\\b(s\\d{1,2}(e\\d{1,2})?|season\\s*\\d+)\\b");
    private static final Pattern ADULT_TITLE = Pattern.compile("(?i)\\bxxx\\b");
    private static boolean isAdultRelease(String title) {
        return title != null && ADULT_TITLE.matcher(title).find();
    }

    private static boolean isMovieRelease(ProwlarrRelease release, Integer year) {
        var title = release.title() == null ? "" : release.title();
        return !EPISODE.matcher(title).find() && (year == null || title.contains(String.valueOf(year)));
    }
    private static int score(ProwlarrRelease release, Integer year) {
        var title = release.title() == null ? "" : release.title().toLowerCase(Locale.ROOT);
        var score = release.seeders() == null ? 0 : Math.min(release.seeders(), 500);
        if (year != null && title.contains(String.valueOf(year))) score += 400;
        if (title.contains("2160p")) score += 220; else if (title.contains("1080p")) score += 160; else if (title.contains("720p")) score += 80;
        if (title.contains("web-dl") || title.contains("bluray")) score += 60;
        if (title.contains("hdr")) score += 25;
        return score;
    }

    record ProwlarrRelease(String title, String protocol, String indexer, Long size, Integer seeders, Integer leechers,
            String downloadUrl, String magnetUrl, String infoUrl, Instant publishDate) { }
    record ReleaseResponse(String title, String protocol, String indexer, Long size, Integer seeders, Integer leechers,
            String downloadUrl, String magnetUrl, String infoUrl, Instant publishDate) {
        static ReleaseResponse from(ProwlarrRelease release) { return new ReleaseResponse(release.title(), release.protocol(), release.indexer(), release.size(), release.seeders(), release.leechers(), release.downloadUrl(), release.magnetUrl(), release.infoUrl(), release.publishDate()); }
    }
}
