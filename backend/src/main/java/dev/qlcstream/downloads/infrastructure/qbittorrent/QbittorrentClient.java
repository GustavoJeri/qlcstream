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

    private final DownloadQbittorrentProperties properties;
    private final ObjectMapper objectMapper;

    public QbittorrentClient(DownloadQbittorrentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<TorrentSnapshot> torrents() {
        if (!properties.configured()) return List.of();
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
                        torrent.path("dlspeed").asLong(), torrent.path("eta").asLong(), torrent.path("save_path").asString()));
            }
            return snapshots;
        } catch (IOException exception) {
            throw new QbittorrentUnavailableException("Não foi possível alcançar o qBittorrent.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QbittorrentUnavailableException("A comunicação com o qBittorrent foi interrompida.", exception);
        }
    }

    public void remove(String hash) {
        if (hash == null || hash.isBlank()) return;
        if (!properties.configured()) throw new QbittorrentUnavailableException("qBittorrent não está configurado.");
        try {
            var response = post(authenticatedClient(), "/api/v2/torrents/delete", Map.of("hashes", hash, "deleteFiles", "false"));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new QbittorrentUnavailableException("Não foi possível remover o torrent do qBittorrent.");
            }
        } catch (IOException exception) {
            throw new QbittorrentUnavailableException("Não foi possível alcançar o qBittorrent.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QbittorrentUnavailableException("A comunicação com o qBittorrent foi interrompida.", exception);
        }
    }

    public void pause(String hash) { command(hash, "/api/v2/torrents/stop", "/api/v2/torrents/pause"); }
    public void resume(String hash) { command(hash, "/api/v2/torrents/start", "/api/v2/torrents/resume"); }
    public void retry(String hash) {
        command(hash, "/api/v2/torrents/recheck");
        resume(hash);
    }
    public void increasePriority(String hash) { command(hash, "/api/v2/torrents/increasePrio"); }
    public void decreasePriority(String hash) { command(hash, "/api/v2/torrents/decreasePrio"); }

    public String hashForSavePath(String savePath) {
        return torrents().stream().filter(torrent -> savePath.equals(torrent.savePath())).map(TorrentSnapshot::hash).findFirst().orElse(null);
    }

    private void command(String hash, String... paths) {
        if (hash == null || hash.isBlank()) throw new QbittorrentUnavailableException("O torrent ainda não está disponível no qBittorrent.");
        try {
            var client = authenticatedClient();
            for (var path : paths) {
                var response = post(client, path, Map.of("hashes", hash));
                if (response.statusCode() >= 200 && response.statusCode() < 300) return;
                if (response.statusCode() != 404) {
                    if (response.statusCode() == 409) throw new QbittorrentUnavailableException("Ative a fila de torrents no qBittorrent para alterar a prioridade.");
                    throw new QbittorrentUnavailableException("O qBittorrent recusou a ação.");
                }
            }
            throw new QbittorrentUnavailableException("A versão atual do qBittorrent não reconhece esta ação.");
        } catch (IOException exception) {
            throw new QbittorrentUnavailableException("Não foi possível alcançar o qBittorrent.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QbittorrentUnavailableException("A comunicação com o qBittorrent foi interrompida.", exception);
        }
    }

    private HttpClient authenticatedClient() throws IOException, InterruptedException {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
        var login = post(client, "/api/v2/auth/login", Map.of("username", properties.username(), "password", properties.password()));
        if (login.statusCode() < 200 || login.statusCode() >= 300) {
            throw new QbittorrentUnavailableException("Não foi possível autenticar no qBittorrent.");
        }
        return client;
    }

    public void add(String acquisitionRef, String savePath) {
        if (!properties.configured()) {
            throw new QbittorrentUnavailableException("qBittorrent não está configurado.");
        }
        try {
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .connectTimeout(Duration.ofSeconds(5)).build();
            var login = post(client, "/api/v2/auth/login", Map.of("username", properties.username(), "password", properties.password()));
            if (login.statusCode() < 200 || login.statusCode() >= 300) {
                throw new QbittorrentUnavailableException("Não foi possível autenticar no qBittorrent.");
            }
            var add = post(client, "/api/v2/torrents/add", Map.of("urls", acquisitionRef, "savepath", savePath));
            if (add.statusCode() < 200 || add.statusCode() >= 300) {
                throw new QbittorrentUnavailableException("O qBittorrent recusou esta fonte de download.");
            }
        } catch (IOException exception) {
            throw new QbittorrentUnavailableException("Não foi possível alcançar o qBittorrent.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QbittorrentUnavailableException("A comunicação com o qBittorrent foi interrompida.", exception);
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
            long downloadSpeedBps, long eta, String savePath) {
    }
}
