package dev.qlcstream.downloads.infrastructure.qbittorrent;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.qlcstream.downloads.config.DownloadQbittorrentProperties;

@Component
public class QbittorrentClient {
    private dev.qlcstream.system.IntegrationSettings settings;

    private final DownloadQbittorrentProperties properties;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public QbittorrentClient(DownloadQbittorrentProperties properties, ObjectMapper objectMapper, dev.qlcstream.system.IntegrationSettings settings) {
        this(properties, objectMapper); this.settings = settings;
    }
    public QbittorrentClient(DownloadQbittorrentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<TorrentSnapshot> torrents() {
        if (settings != null) { var c = settings.effective("qbittorrent"); return new QbittorrentClient(new DownloadQbittorrentProperties(c.baseUrl(), c.username(), c.secret()), objectMapper).torrents(); }
        if (!properties.configured()) throw new QbittorrentUnavailableException("qBittorrent não está configurado.");
        try {
            var client = authenticatedClient();
            var request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/api/v2/torrents/info"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new QbittorrentUnavailableException("Não foi possível consultar o qBittorrent.");
            }
            var snapshots = new ArrayList<TorrentSnapshot>();
            for (JsonNode torrent : objectMapper.readTree(response.body())) {
                snapshots.add(new TorrentSnapshot(torrent.path("hash").asString(), torrent.path("state").asString(),
                        torrent.path("progress").asDouble(), torrent.path("size").asLong(), torrent.path("downloaded").asLong(),
                        torrent.path("dlspeed").asLong(), torrent.path("eta").asLong(), torrent.path("save_path").asString(), torrent.path("name").asString()));
            }
            return snapshots;
        } catch (IOException exception) {
            throw new QbittorrentUnavailableException("Não foi possível alcançar o qBittorrent.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QbittorrentUnavailableException("A comunicação com o qBittorrent foi interrompida.");
        } catch (RuntimeException failure) {
            if (failure instanceof QbittorrentUnavailableException known) throw known;
            throw new QbittorrentUnavailableException("Resposta inválida do qBittorrent.");
        }
    }

    public void remove(String hash) {
        if (settings != null) { var c = settings.effective("qbittorrent"); new QbittorrentClient(new DownloadQbittorrentProperties(c.baseUrl(), c.username(), c.secret()), objectMapper).remove(hash); return; }
        if (hash == null || hash.isBlank()) return;
        if (!properties.configured()) throw new QbittorrentUnavailableException("qBittorrent não está configurado.");
        try {
            var response = post(authenticatedClient(), "/api/v2/torrents/delete", Map.of("hashes", hash, "deleteFiles", "false"));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new QbittorrentUnavailableException("Não foi possível remover o torrent do qBittorrent.");
            }
        } catch (IOException exception) {
            throw new QbittorrentUnavailableException("Não foi possível alcançar o qBittorrent.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QbittorrentUnavailableException("A comunicação com o qBittorrent foi interrompida.");
        } catch (RuntimeException failure) {
            if (failure instanceof QbittorrentUnavailableException known) throw known;
            throw new QbittorrentUnavailableException("Resposta inválida do qBittorrent.");
        }
    }

    public void pause(String hash) {
        if (settings != null) { var c = settings.effective("qbittorrent"); new QbittorrentClient(new DownloadQbittorrentProperties(c.baseUrl(), c.username(), c.secret()), objectMapper).pause(hash); return; } command("/api/v2/torrents/stop", hash); }
    public void resume(String hash) {
        if (settings != null) { var c = settings.effective("qbittorrent"); new QbittorrentClient(new DownloadQbittorrentProperties(c.baseUrl(), c.username(), c.secret()), objectMapper).resume(hash); return; } command("/api/v2/torrents/start", hash); }

    public void recheck(String hash) {
        if (settings != null) { var c = settings.effective("qbittorrent"); new QbittorrentClient(new DownloadQbittorrentProperties(c.baseUrl(), c.username(), c.secret()), objectMapper).recheck(hash); return; } command("/api/v2/torrents/recheck", hash); }

    private void command(String path, String hash) {
        if (hash == null || hash.isBlank()) throw new QbittorrentUnavailableException("O torrent não possui identificador.");
        try {
            var response = post(authenticatedClient(), path, Map.of("hashes", hash));
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new QbittorrentUnavailableException("O qBittorrent recusou a ação.");
        } catch (IOException exception) {
            throw new QbittorrentUnavailableException("Não foi possível alcançar o qBittorrent.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QbittorrentUnavailableException("A comunicação com o qBittorrent foi interrompida.");
        } catch (RuntimeException failure) {
            if (failure instanceof QbittorrentUnavailableException known) throw known;
            throw new QbittorrentUnavailableException("Resposta inválida do qBittorrent.");
        }
    }

    private HttpClient authenticatedClient() throws IOException, InterruptedException {
        if (!properties.configured()) throw new QbittorrentUnavailableException("qBittorrent não está configurado.");
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
        var login = post(client, "/api/v2/auth/login", Map.of("username", properties.username(), "password", properties.password()));
        if (!loginSucceeded(login)) {
            throw new QbittorrentUnavailableException("Não foi possível autenticar no qBittorrent.");
        }
        return client;
    }

    private static boolean loginSucceeded(HttpResponse<String> response) {
        // qBittorrent 5.3 uses 204; older releases return 200 + Ok.
        return response.statusCode() == 204
                || (response.statusCode() == 200 && "Ok.".equals(response.body().trim()));
    }

    public void add(String acquisitionRef, String savePath) {
        if (settings != null) { var c = settings.effective("qbittorrent"); new QbittorrentClient(new DownloadQbittorrentProperties(c.baseUrl(), c.username(), c.secret()), objectMapper).add(acquisitionRef, savePath); return; }
        if (!properties.configured()) {
            throw new QbittorrentUnavailableException("qBittorrent não está configurado.");
        }
        try {
            var client = authenticatedClient();
            var add = post(client, "/api/v2/torrents/add", Map.of("urls", acquisitionRef, "savepath", savePath));
            if (!additionSucceeded(add)) {
                throw new QbittorrentUnavailableException("O qBittorrent recusou esta fonte de download.");
            }
        } catch (IOException exception) {
            throw new QbittorrentUnavailableException("Não foi possível alcançar o qBittorrent.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QbittorrentUnavailableException("A comunicação com o qBittorrent foi interrompida.");
        } catch (RuntimeException failure) {
            if (failure instanceof QbittorrentUnavailableException known) throw known;
            throw new QbittorrentUnavailableException("Resposta inválida do qBittorrent.");
        }
    }

    private boolean additionSucceeded(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) return false;
        var body = response.body().trim();
        if (response.statusCode() == 204 || "Ok.".equals(body)) return true;
        // qBittorrent 5.3 returns a JSON summary, with 202 while URL retrieval is pending.
        if (!body.startsWith("{")) return false;
        try {
            var result = objectMapper.readTree(body);
            return result.path("success_count").asInt(0) > 0 || result.path("pending_count").asInt(0) > 0;
        } catch (RuntimeException invalidResponse) {
            return false;
        }
    }

    private HttpResponse<String> post(HttpClient client, String path, Map<String, String> form) throws IOException, InterruptedException {
        var encoded = form.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encoded)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static class QbittorrentUnavailableException extends RuntimeException {
        QbittorrentUnavailableException(String message) {
            super(message);
        }

        QbittorrentUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record TorrentSnapshot(String hash, String state, double progress, long size, long downloaded,
            long downloadSpeedBps, long eta, String savePath, String name) {
        public TorrentSnapshot(String hash, String state, double progress, long size, long downloaded,
                long downloadSpeedBps, long eta, String savePath) {
            this(hash, state, progress, size, downloaded, downloadSpeedBps, eta, savePath, "");
        }
    }
}
