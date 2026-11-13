package dev.qlcstream.system;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import dev.qlcstream.system.config.ProwlarrProperties;
import dev.qlcstream.system.config.QbittorrentProperties;
import dev.qlcstream.system.config.TmdbConfigurationProperties;

/** Isolated from download clients: only authentication and metadata reads are allowed here. */
@Component
class IntegrationProbe implements AutoCloseable {
    private IntegrationSettings settings;
    Object revision(String id) { return settings == null ? this : settings.effective(id); }
    private IntegrationProbe snapshot(String id) {
        var c = settings.effective(id);
        return new IntegrationProbe(new TmdbConfigurationProperties(c.baseUrl(), c.secret()),
            new ProwlarrProperties(c.baseUrl(), c.secret()), new QbittorrentProperties(c.baseUrl(), c.username(), c.secret()), json);
    }
    @org.springframework.beans.factory.annotation.Autowired
    IntegrationProbe(TmdbConfigurationProperties t, ProwlarrProperties p, QbittorrentProperties q, ObjectMapper j, IntegrationSettings settings) {
        this(t,p,q,j); this.settings = settings;
    }
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(5);
    private final TmdbConfigurationProperties tmdb;
    private final ProwlarrProperties prowlarr;
    private final QbittorrentProperties qbittorrent;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final HttpClient qbClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();

    IntegrationProbe(TmdbConfigurationProperties tmdb, ProwlarrProperties prowlarr,
            QbittorrentProperties qbittorrent, ObjectMapper json) {
        this.tmdb = tmdb;
        this.prowlarr = prowlarr;
        this.qbittorrent = qbittorrent;
        this.json = json;
    }

    boolean configured(String id) {
        if (settings != null) return settings.effective(id).configured(id);
        return switch (id) {
            case "tmdb" -> tmdb.configured();
            case "prowlarr" -> prowlarr.configured();
            case "qbittorrent" -> qbittorrent.configured();
            default -> false;
        };
    }

    String check(String id) {
        if (settings != null) { try (var probe = snapshot(id)) { return probe.check(id); } }
        long deadline = System.nanoTime() + TOTAL_TIMEOUT.toNanos();
        try {
            switch (id) {
                case "tmdb" -> {
                    var response = send(client, request(tmdb.baseUrl(), "/configuration")
                            .header("Authorization", "Bearer " + tmdb.apiToken()).GET().build(), deadline);
                    var failure = statusFailure(response);
                    if (failure != null) return failure;
                    var body = json.readTree(response.body());
                    if (body == null || !body.path("images").isObject()
                            || !body.path("change_keys").isArray()) return "invalid_response";
                }
                case "prowlarr" -> {
                    var response = send(client, request(prowlarr.baseUrl(), "/api/v1/system/status")
                            .header("X-Api-Key", prowlarr.apiKey()).GET().build(), deadline);
                    var failure = statusFailure(response);
                    if (failure != null) return failure;
                    var body = json.readTree(response.body());
                    if (body == null || !body.path("version").isString()
                            || body.path("version").asString().isBlank()) return "invalid_response";
                }
                case "qbittorrent" -> {
                    var login = send(qbClient, request(qbittorrent.baseUrl(), "/api/v2/auth/login")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("username=" + encode(qbittorrent.username())
                                    + "&password=" + encode(qbittorrent.password()))).build(), deadline);
                    var failure = statusFailure(login);
                    if (failure != null) return failure;
                    if (login.statusCode() != 204 && !(login.statusCode() == 200 && "Ok.".equals(login.body().trim()))) {
                        return "Fails.".equals(login.body().trim()) ? "authentication_rejected" : "invalid_response";
                    }
                    var response = send(qbClient, request(qbittorrent.baseUrl(), "/api/v2/app/version").GET().build(), deadline);
                    failure = statusFailure(response);
                    if (failure != null) return failure;
                    if (!response.body().trim().matches("v?\\d+\\.\\d+(?:\\.\\d+)?[a-zA-Z0-9.+~-]*")) return "invalid_response";
                }
                default -> { return "invalid_configuration"; }
            }
            return "ok";
        } catch (IllegalArgumentException exception) {
            return "invalid_configuration";
        } catch (TimeoutException exception) {
            return "timeout";
        } catch (ExecutionException exception) {
            return exception.getCause() instanceof HttpTimeoutException ? "timeout" : "connection_failed";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "connection_failed";
        } catch (RuntimeException exception) {
            // Never propagate remote bodies, addresses or exception messages to logs or callers.
            return "invalid_response";
        }
    }

    private static HttpRequest.Builder request(String baseUrl, String path) {
        return HttpRequest.newBuilder(IntegrationEndpoint.api(baseUrl, path)).timeout(Duration.ofSeconds(2));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private static String statusFailure(HttpResponse<String> response) {
        if (response.statusCode() == 401 || response.statusCode() == 403) return "authentication_rejected";
        return response.statusCode() == 200 || response.statusCode() == 204 ? null : "http_error";
    }

    @Override public void close() { client.close(); qbClient.close(); }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
