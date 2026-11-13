package dev.qlcstream.system;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import dev.qlcstream.system.config.QbittorrentProperties;
import tools.jackson.databind.ObjectMapper;

@Component
class StorageEngineProbe {
    private IntegrationSettings settings;
    @org.springframework.beans.factory.annotation.Autowired
    StorageEngineProbe(QbittorrentProperties properties, ObjectMapper mapper, IntegrationSettings settings) {
        this(properties, mapper); this.settings = settings;
    }
    private final QbittorrentProperties properties;
    private final ObjectMapper mapper;

    StorageEngineProbe(QbittorrentProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    String mapping() {
        if (settings != null) { var c = settings.effective("qbittorrent");
            return new StorageEngineProbe(new QbittorrentProperties(c.baseUrl(), c.username(), c.secret()), mapper).mapping(); }
        if (!properties.configured()) return "engine_not_configured";
        try {
            var base = URI.create(properties.baseUrl());
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) return "engine_unavailable";
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
                var login = request(client, "/api/v2/auth/login", "username=" + encode(properties.username())
                        + "&password=" + encode(properties.password()), deadline);
                if (!(login.statusCode() == 204 || login.statusCode() == 200 && login.body().trim().equals("Ok.")))
                    return "engine_authentication_failed";
                var response = request(client, "/api/v2/app/preferences", null, deadline);
                if (response.statusCode() != 200) return "engine_unavailable";
                var prefs = mapper.readTree(response.body());
                var save = prefs.path("save_path");
                if (!save.isString() || save.asString().isBlank()) return "engine_unavailable";
                if (!insideEngineRoot(save.asString())) return "mapping_incompatible";
                if (prefs.path("temp_path_enabled").asBoolean() && !insideEngineRoot(prefs.path("temp_path").asString()))
                    return "mapping_incompatible";
                return "compatible_unproven";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "engine_unavailable";
        } catch (Exception failure) {
            // Remote bodies and exception messages may contain credentials or private paths.
            return "engine_unavailable";
        }
    }

    static boolean insideEngineRoot(String value) {
        if (value == null || !value.startsWith("/") || value.contains("\\") || value.contains(":")) return false;
        if (java.util.Arrays.asList(value.split("/")).contains("..")) return false;
        var normalized = java.nio.file.Path.of(value).normalize().toString().replace('\\', '/');
        return normalized.equals("/downloads") || normalized.startsWith("/downloads/");
    }

    private HttpResponse<String> request(HttpClient client, String path, String form, long deadline) throws Exception {
        var builder = HttpRequest.newBuilder(IntegrationEndpoint.api(properties.baseUrl(), path))
                .timeout(Duration.ofSeconds(5));
        if (form == null) builder.GET();
        else builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        var future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
        try { return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
