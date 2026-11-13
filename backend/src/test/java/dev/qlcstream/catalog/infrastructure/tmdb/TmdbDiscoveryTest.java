package dev.qlcstream.catalog.infrastructure.tmdb;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import com.sun.net.httpserver.HttpServer;
import dev.qlcstream.catalog.domain.CatalogCollection;
import dev.qlcstream.catalog.domain.CatalogSort;
import org.junit.jupiter.api.Test;

class TmdbDiscoveryTest {
    @Test void sendsTheSameSortAndCollectionFiltersAcrossAllPages() throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var url = exchange.getRequestURI();
            requests.add(url.toString());
            var page = java.util.regex.Pattern.compile("(?:^|&)page=([1-3])(?:&|$)").matcher(url.getQuery());
            int number = page.find() ? Integer.parseInt(page.group(1)) : 1;
            String result = url.getPath().endsWith("tv")
                    ? """
                      {"id":%d,"name":"Serie %d","first_air_date":"202%d-01-01"}
                      """
                    : """
                      {"id":%d,"title":"Filme %d","release_date":"202%d-01-01"}
                      """;
            var body = """
                    {"page":%d,"total_pages":3,"total_results":3,"results":[%s]}
                    """.formatted(number, result.formatted(number, number, 4 - number))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) { response.write(body); }
        });
        server.start();
        try {
            var provider = new TmdbMovieAdapter(new TmdbProperties("http://127.0.0.1:" + server.getAddress().getPort(), "test"));
            for (int page = 1; page <= 3; page++) {
                var films = provider.discover(CatalogCollection.TOP_RATED, "pt-BR", page, CatalogSort.NEWEST);
                var series = provider.discoverSeries(CatalogCollection.TOP_RATED, "pt-BR", page, CatalogSort.NEWEST);
                assertEquals(page, films.items().getFirst().tmdbId());
                assertEquals(page, series.items().getFirst().tmdbId());
                assertEquals(2024 - page, films.items().getFirst().releaseDate().getYear());
                assertEquals(2024 - page, series.items().getFirst().firstAirDate().getYear());
                assertEquals(page < 3, films.hasNext());
                assertEquals(page < 3, series.hasNext());
            }
            assertEquals(6, requests.size());
            for (int i = 0; i < 6; i++) {
                var request = requests.get(i);
                assertTrue(request.contains("page=" + (i / 2 + 1)));
                assertTrue(request.contains("vote_count.gte=500"));
                assertTrue(request.contains(i % 2 == 0 ? "sort_by=primary_release_date.desc" : "sort_by=first_air_date.desc"));
                assertEquals(1, request.split("sort_by=", -1).length - 1);
            }
        } finally {
            server.stop(0);
        }
    }
}
