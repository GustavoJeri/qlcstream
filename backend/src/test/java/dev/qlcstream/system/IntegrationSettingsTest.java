package dev.qlcstream.system;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.sql.ResultSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IntegrationSettingsTest {
    @TempDir Path temporary;
    JdbcTemplate jdbc;
    PlatformTransactionManager manager;
    StandardEnvironment env;
    Map<String, String> rows;

    @BeforeEach void setup() throws Exception {
        var key = temporary.resolve("key");
        Files.writeString(key, Base64.getEncoder().encodeToString(new byte[32]));
        env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "qlc-stream.integrations.key-file", key.toString(),
                "qlc-stream.tmdb.base-url", "http://127.0.0.1:19999",
                "qlc-stream.tmdb.api-token", "fictional-environment-token")));
        jdbc = mock(JdbcTemplate.class);
        rows = new HashMap<>();
        doAnswer(call -> {
            RowCallbackHandler handler = call.getArgument(1);
            for (var entry : rows.entrySet()) {
                var rs = mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(entry.getKey());
                when(rs.getString(2)).thenReturn(entry.getValue());
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        when(jdbc.update(anyString(), anyString(), anyString())).thenAnswer(call -> {
            rows.put(call.getArgument(1), call.getArgument(2)); return 1;
        });
        when(jdbc.update(anyString(), anyString())).thenAnswer(call -> { rows.remove(call.getArgument(1)); return 1; });
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    IntegrationSettings service() { return new IntegrationSettings(jdbc, new TransactionTemplate(manager), new ObjectMapper(), env); }
    IntegrationSettings.Change change(String url, IntegrationSettings.SecretChange secret) {
        return new IntegrationSettings.Change("application", url, null, secret);
    }

    @Test void environmentIsPreservedUntilExplicitAdoptionAndResetIsExplicit() {
        var settings = service();
        assertEquals("environment", settings.states().getFirst().source());
        assertTrue(rows.isEmpty());
        settings.update("tmdb", change("http://127.0.0.1:19998", null));
        assertEquals("fictional-environment-token", settings.effective("tmdb").secret());
        assertEquals("application", settings.states().getFirst().source());
        assertFalse(rows.get("tmdb").contains("fictional-environment-token"));
        settings.update("tmdb", new IntegrationSettings.Change("environment", null, null, null));
        assertEquals("http://127.0.0.1:19999", settings.effective("tmdb").baseUrl());
        assertTrue(rows.isEmpty());
    }

    @Test void unsafeLegacyEnvironmentAddressNeverExposesEmbeddedCredentials() {
        env.getPropertySources().addFirst(new MapPropertySource("unsafe-fixture", Map.of(
                "qlc-stream.tmdb.base-url", "http://private-user:private-secret@localhost?token=private-secret")));
        String body = new ObjectMapper().writeValueAsString(service().states());
        assertFalse(body.contains("private-secret"));
        assertFalse(body.contains("private-user"));
    }

    @Test void candidateNeverWritesAndOmittedSecretIsPreservedAcrossRestart() {
        var settings = service();
        var replacement = new IntegrationSettings.SecretChange("replace", "fictional-new-token");
        assertEquals("fictional-new-token", settings.candidate("tmdb", change(null, replacement)).secret());
        assertTrue(rows.isEmpty());
        assertEquals("fictional-environment-token", settings.effective("tmdb").secret());
        settings.update("tmdb", change(null, replacement));
        settings.update("tmdb", change("http://127.0.0.1:19997", null));
        assertEquals(settings.effective("tmdb"), service().effective("tmdb"));
        assertEquals("fictional-new-token", service().effective("tmdb").secret());
    }

    @Test void clearingDoesNotFallBackToEnvironmentAndSurvivesRestart() {
        var settings = service();
        settings.update("tmdb", change(null, new IntegrationSettings.SecretChange("clear", null)));
        assertFalse(settings.effective("tmdb").configured("tmdb"));
        assertEquals("", service().effective("tmdb").secret());
        assertFalse(settings.states().getFirst().secretPresent());
    }

    @Test void invalidChangesAndCommitFailureKeepPreviousClientSnapshot() {
        var settings = service();
        var previous = settings.effective("tmdb");
        assertThrows(IllegalArgumentException.class, () -> settings.update("tmdb", change("http://user:private@localhost", null)));
        assertThrows(IllegalArgumentException.class, () -> settings.update("tmdb", change(null, new IntegrationSettings.SecretChange("replace", ""))));
        doThrow(new IllegalStateException("controlled commit failure")).when(manager).commit(any());
        assertThrows(IllegalStateException.class, () -> settings.update("tmdb", change("http://127.0.0.1:19998", null)));
        assertSame(previous, settings.effective("tmdb"));
    }

    @Test void missingKeyFailsClosedForStoredSettingsAndResponsesNeverExposeSecret() throws Exception {
        var settings = service();
        settings.update("tmdb", change(null, null));
        var body = new ObjectMapper().writeValueAsString(settings.states());
        assertFalse(body.contains("fictional-environment-token"));
        Files.delete(temporary.resolve("key"));
        assertThrows(IllegalStateException.class, this::service);
        assertThrows(IllegalStateException.class, () -> settings.update("tmdb", change(null, null)));
    }

    @Test void changedKeyRejectsWritesWithoutMixingKeysAndRestoringItAllowsRecovery() throws Exception {
        var settings = service();
        settings.update("tmdb", change(null, null));
        assertKeyChangeRejected(settings);
        // Startup must pin the key used to decrypt existing rows as well.
        assertKeyChangeRejected(service());
    }

    @Test void explicitProvisionCreatesACompleteKeyOnceAndNeverReplacesExistingFiles() throws Exception {
        Files.delete(temporary.resolve("key"));
        var settings = service();
        assertFalse(settings.keyReady());
        settings.provisionKey();
        var key = Files.readAllBytes(temporary.resolve("key"));
        assertEquals(32, Base64.getDecoder().decode(key).length);
        assertTrue(settings.keyReady());
        settings.provisionKey();
        assertArrayEquals(key, Files.readAllBytes(temporary.resolve("key")));
        settings.update("tmdb", change(null, null));
        assertEquals(settings.effective("tmdb"), service().effective("tmdb"));
        Files.delete(temporary.resolve("key"));
        assertThrows(IllegalStateException.class, settings::provisionKey);
        assertFalse(Files.exists(temporary.resolve("key")));
        Files.writeString(temporary.resolve("key"), "invalid-existing-key");
        var empty = new HashMap<>(rows);
        rows.clear();
        try {
            assertThrows(IllegalStateException.class, service()::provisionKey);
            assertEquals("invalid-existing-key", Files.readString(temporary.resolve("key")));
        } finally { rows.putAll(empty); }
        try (var files = Files.list(temporary)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".integration-key-")));
        }
    }

    private void assertKeyChangeRejected(IntegrationSettings settings) throws Exception {
        var previous = settings.effective("tmdb");
        var previousRows = Map.copyOf(rows);
        byte[] replacement = new byte[32];
        replacement[0] = 1;
        Files.writeString(temporary.resolve("key"), Base64.getEncoder().encodeToString(replacement));
        assertThrows(IllegalStateException.class, () -> settings.update("prowlarr", change("http://127.0.0.1:19999", null)));
        assertThrows(IllegalStateException.class, () -> settings.update("tmdb", change(null,
                new IntegrationSettings.SecretChange("replace", "fictional_replacement"))));
        assertEquals(previousRows, rows);
        assertSame(previous, settings.effective("tmdb"));
        Files.writeString(temporary.resolve("key"), Base64.getEncoder().encodeToString(new byte[32]));
        settings.update("prowlarr", change("http://127.0.0.1:19999", null));
        assertEquals(settings.effective("tmdb"), service().effective("tmdb"));
        assertEquals(settings.effective("prowlarr"), service().effective("prowlarr"));
    }

    @Test void diagnosticStorageAndDownloadClientAgreeOnRootAndSubpathWithTrailingSlashes() throws Exception {
        var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var prefix = new java.util.concurrent.atomic.AtomicReference<>("");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            String apiPath = path.substring(Math.min(prefix.get().length(), path.length()));
            String body = switch (apiPath) {
                case "/api/v2/auth/login" -> "Ok.";
                case "/api/v2/app/version" -> "v5.1.4";
                case "/api/v2/app/preferences" -> "{\"save_path\":\"/downloads\"}";
                case "/api/v2/torrents/info" -> "[]";
                default -> "missing";
            };
            exchange.sendResponseHeaders(body.equals("missing") ? 404 : 200, body.length());
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        server.start();
        try {
            var settings = service();
            var mapper = new ObjectMapper();
            var engine = new dev.qlcstream.downloads.infrastructure.qbittorrent.QbittorrentClient(
                    new dev.qlcstream.downloads.config.DownloadQbittorrentProperties("", "", ""), mapper, settings);
            var storage = new StorageEngineProbe(new dev.qlcstream.system.config.QbittorrentProperties("", "", ""), mapper, settings);
            try (var probe = new IntegrationProbe(new dev.qlcstream.system.config.TmdbConfigurationProperties("", ""),
                    new dev.qlcstream.system.config.ProwlarrProperties("", ""),
                    new dev.qlcstream.system.config.QbittorrentProperties("", "", ""), mapper, settings)) {
                for (var subpath : new String[]{"", "/subpath"}) {
                    prefix.set(subpath);
                    for (var trailing : new String[]{"", "/", "///"}) {
                        paths.clear();
                        String url = "http://127.0.0.1:" + server.getAddress().getPort() + subpath + trailing;
                        settings.update("qbittorrent", new IntegrationSettings.Change("application", url, "fictional_user",
                                new IntegrationSettings.SecretChange("replace", "fictional_secret")));
                        assertEquals("ok", probe.check("qbittorrent"));
                        assertEquals("compatible_unproven", storage.mapping());
                        assertTrue(engine.torrents().isEmpty());
                        assertEquals(6, paths.size());
                        assertTrue(paths.stream().allMatch(path -> path.startsWith(subpath + "/api/v2/") && !path.contains("//")));
                    }
                }
            }
        } finally { server.stop(0); }
    }

    @Test void candidateEndpointAndLiveClientsUseNewSnapshotWithoutReconstruction() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/", exchange -> {
            received.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            String body = path.equals("/configuration") ? "{\"images\":{},\"change_keys\":[]}"
                    : path.equals("/api/v2/auth/login") ? "Ok."
                    : path.equals("/api/v2/app/version") ? "v5.1.4"
                    : path.startsWith("/api/") ? "[]" : "{\"page\":1,\"total_pages\":2,\"total_results\":20,\"results\":[]}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length());
            exchange.getResponseBody().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            var settings = service();
            var mapper = new ObjectMapper();
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var candidate = change(url, new IntegrationSettings.SecretChange("replace", "fictional_candidate"));
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new IntegrationSettingsController(settings, mapper)).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/system/integration-settings/tmdb/test")
                    .contentType("application/json").content(mapper.writeValueAsString(candidate)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.reason").value("ok"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
            assertTrue(rows.isEmpty());
            var catalog = new dev.qlcstream.catalog.infrastructure.tmdb.TmdbMovieAdapter(
                    new dev.qlcstream.catalog.infrastructure.tmdb.TmdbProperties("", ""), settings);
            settings.update("tmdb", candidate);
            var page = catalog.trending("pt-BR", 1, null);
            assertTrue(page.items().isEmpty());
            assertEquals(1, page.page());
            assertEquals(2, page.totalPages());
            assertEquals(20, page.totalResults());
            int requestCount = received.size();
            assertEquals(page, catalog.trending("pt-BR", 1, null));
            assertEquals(requestCount, received.size());
            settings.update("tmdb", change(null, new IntegrationSettings.SecretChange("replace", "fictional_second")));
            assertTrue(catalog.trending("pt-BR", 1, null).items().isEmpty());
            assertEquals("Bearer fictional_second", received.getLast());
            settings.update("qbittorrent", new IntegrationSettings.Change("application", url, "fictional_user",
                    new IntegrationSettings.SecretChange("replace", "fictional_candidate")));
            var engine = new dev.qlcstream.downloads.infrastructure.qbittorrent.QbittorrentClient(
                    new dev.qlcstream.downloads.config.DownloadQbittorrentProperties("", "", ""), mapper, settings);
            assertTrue(engine.torrents().isEmpty());
            settings.update("prowlarr", candidate);
            var search = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new dev.qlcstream.search.ProwlarrSearchController(new dev.qlcstream.search.config.SearchProwlarrProperties("", ""), settings)).build();
            search.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/search/releases").param("query", "fictional"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        } finally { server.stop(0); }
    }
}
