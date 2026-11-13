package dev.qlcstream.downloads;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import dev.qlcstream.downloads.application.*;
import dev.qlcstream.downloads.config.DownloadQbittorrentProperties;
import dev.qlcstream.downloads.infrastructure.persistence.*;
import dev.qlcstream.downloads.infrastructure.qbittorrent.*;
import dev.qlcstream.downloads.infrastructure.web.DownloadUpdatePublisher;
import dev.qlcstream.library.LocalLibraryRegistrar;

class DownloadRegressionTest {
    static class Engine extends QbittorrentClient {
        List<TorrentSnapshot> snapshots = List.of();
        List<String> commands = new ArrayList<>();
        Engine() { super(null, null); }
        @Override public List<TorrentSnapshot> torrents() { return snapshots; }
        @Override public void remove(String hash) { commands.add("remove:" + hash); }
        @Override public void resume(String hash) { commands.add("resume:" + hash); }
        @Override public void increasePriority(String hash) { commands.add("priority_up:" + hash); }
        @Override public void recheck(String hash) { commands.add("recheck:" + hash); }
        @Override public void add(String ref, String path) { fail("Unexpected duplicate submission"); }
    }

    static DownloadRow row() {
        return DownloadRow.queuedSeries(1, "Test series", null, 1, 3, UUID.randomUUID(),
                "test-release", "test-ref", null, 1080, null, null, "incoming/test");
    }

    static QbittorrentClient.TorrentSnapshot snapshot(String state, double progress, String path) {
        return new QbittorrentClient.TorrentSnapshot("hash", state, progress, 1000, 200, 10, 80, path);
    }

    static SpringDataDownloadRepository repository(DownloadRow row) {
        boolean[] locked = {false};
        return (SpringDataDownloadRepository) Proxy.newProxyInstance(
                DownloadRegressionTest.class.getClassLoader(), new Class<?>[]{SpringDataDownloadRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.of(row);
                    case "lockSubmissions" -> { locked[0] = true; yield 1L; }
                    case "findExistingRelease" -> {
                        assertTrue(locked[0], "Deduplication must occur inside the submission lock");
                        yield Optional.of(row);
                    }
                    case "findMovieIdByTmdbId" -> 1L;
                    case "findByStatusNotIn" -> {
                        assertFalse(((Collection<?>) args[0]).contains("ERROR"));
                        yield List.of(row);
                    }
                    case "toString" -> "InMemoryDownloads";
                    default -> throw new AssertionError("Unexpected repository call: " + method.getName());
                });
    }

    @Test void keepsLastConfirmedProgressWhenTheEngineDisconnectsAndRecovers() throws Exception {
        try (var api = new ApiFixture()) {
            var row = row();
            api.infoBody = """
                    [{"hash":"hash","state":"downloading","progress":0.2,"save_path":"/downloads/incoming/test"}]
                    """;
            var synchronizer = new QbittorrentDownloadSynchronizer(repository(row), api.client(), null,
                    new DownloadUpdatePublisher());
            synchronizer.synchronize();
            var confirmed = synchronizer.syncStatus();
            assertEquals("available", confirmed.state());
            assertTrue(confirmed.queueingEnabled());

            api.infoStatus = 503;
            synchronizer.synchronize();
            assertEquals("unavailable", synchronizer.syncStatus().state());
            assertEquals(confirmed.lastSuccessfulAt(), synchronizer.syncStatus().lastSuccessfulAt());
            assertEquals("DOWNLOADING", row.status());

            api.infoStatus = 200;
            api.infoBody = api.infoBody.replace("downloading", "missingFiles");
            synchronizer.synchronize();
            assertEquals("available", synchronizer.syncStatus().state());
            assertTrue(synchronizer.syncStatus().queueingEnabled());
            assertEquals("ERROR", row.status());
        }
    }

    @Test void cancelsOrphanWithoutRemovingAnotherTorrent() {
        var engine = new Engine(); var row = row();
        engine.snapshots = List.of(snapshot("downloading", .2, "/downloads/incoming/other"));
        new DownloadControlService(repository(row), engine).control(row.id(), DownloadControlService.Action.CANCEL);
        assertEquals("REMOVED", row.status()); assertTrue(engine.commands.isEmpty());
    }

    @Test void cancelsOwnedTorrentBeforeMetadataIncludingTrailingSlash() {
        var engine = new Engine(); var row = row();
        engine.snapshots = List.of(snapshot("downloading", .2, "/downloads/incoming/test/"));
        new DownloadControlService(repository(row), engine).control(row.id(), DownloadControlService.Action.CANCEL);
        assertEquals("REMOVED", row.status()); assertEquals(List.of("remove:hash"), engine.commands);
    }

    @Test void repeatedCancelIsHarmless() {
        var engine = new Engine(); var row = row(); row.markRemoved();
        new DownloadControlService(repository(row), engine).control(row.id(), DownloadControlService.Action.CANCEL);
        assertTrue(engine.commands.isEmpty());
    }

    @Test void recoversErrorByHashAfterPathChanges() {
        var engine = new Engine(); var row = row();
        row.synchronize("hash", "missingFiles", 0, 1000, 0, 0, 0, "ERROR");
        engine.snapshots = List.of(snapshot("downloading", .2, "/downloads/changed"));
        new QbittorrentDownloadSynchronizer(repository(row), engine, null, new DownloadUpdatePublisher()).synchronize();
        assertEquals("DOWNLOADING", row.status());
    }

    @Test void rechecksAndStartsError() {
        var engine = new Engine(); var row = row();
        row.synchronize("hash", "missingFiles", 0, 1000, 0, 0, 0, "ERROR");
        new DownloadControlService(repository(row), engine).control(row.id(), DownloadControlService.Action.RESUME);
        assertEquals(List.of("recheck:hash", "resume:hash"), engine.commands);
    }

    @Test void retriesAndPrioritizesTheOwnedTorrentBeforeMetadata() {
        var engine = new Engine();
        var row = row();
        engine.snapshots = List.of(snapshot("downloading", .2, "/downloads/incoming/test/"));
        var service = new DownloadControlService(repository(row), engine);
        service.control(row.id(), DownloadControlService.Action.PRIORITY_UP);
        service.control(row.id(), DownloadControlService.Action.RETRY);
        assertEquals(List.of("priority_up:hash", "recheck:hash", "resume:hash"), engine.commands);
        assertEquals("CHECKING", row.status());
    }

    @Test void leavesTransfersAloneWhenTheSavePathMatchesMultipleHashes() {
        var engine = new Engine();
        var row = row();
        engine.snapshots = List.of(snapshot("downloading", .2, "/downloads/incoming/test/"),
                new QbittorrentClient.TorrentSnapshot("another-hash", "downloading", .2, 1000, 200, 10, 80,
                        "/downloads/incoming/test"));
        var service = new DownloadControlService(repository(row), engine);
        assertThrows(IllegalStateException.class,
                () -> service.control(row.id(), DownloadControlService.Action.PRIORITY_UP));
        assertTrue(engine.commands.isEmpty());
    }

    @Test void deduplicatesSeriesUnderSubmissionLock() {
        var row = row();
        var result = new DownloadSubmissionService(repository(row), new Engine(), null).submitSeries(
                new DownloadSubmissionService.SeriesDownloadRequest(1, "Test", null, 1, 3,
                        "test-release", "test-ref", null, 1080, null, null));
        assertEquals(row.id(), result.id());
    }

    @Test void deduplicatesMovieUnderSubmissionLock() {
        var row = row();
        var result = new DownloadSubmissionService(repository(row), new Engine(), null).submit(
                new DownloadSubmissionService.DownloadRequest(1, "test-release", "test-ref", null, 1080, null, null));
        assertEquals(row.id(), result.id());
    }

    @Test void expiresUnlinkedAttemptInsteadOfQueueingForever() throws Exception {
        var row = row(); var createdAt = DownloadRow.class.getDeclaredField("createdAt");
        createdAt.setAccessible(true); createdAt.set(row, Instant.now().minusSeconds(600));
        new QbittorrentDownloadSynchronizer(repository(row), new Engine(), null, new DownloadUpdatePublisher()).synchronize();
        assertEquals("ERROR", row.status());
    }

    @Test void keepsRecentUnlinkedAttemptWhileEngineAcceptsIt() {
        var row = row();
        new QbittorrentDownloadSynchronizer(repository(row), new Engine(), null, new DownloadUpdatePublisher()).synchronize();
        assertEquals("QUEUED", row.status());
    }

    @Test void recognizesTrailingSlashAndForcedMetadata() {
        var engine = new Engine(); var row = row();
        engine.snapshots = List.of(snapshot("forcedMetaDL", 0, "/downloads/incoming/test/"));
        new QbittorrentDownloadSynchronizer(repository(row), engine, null, new DownloadUpdatePublisher()).synchronize();
        assertEquals("METADATA", row.status());
    }

    @Test void keepsCompletedTorrentWhenLibraryCannotFindVideo() {
        var engine = new Engine(); var row = row();
        engine.snapshots = List.of(snapshot("queuedUP", 1, "/downloads/incoming/test"));
        var library = new LocalLibraryRegistrar(null) {
            @Override public void registerCompletedSeries(long id, String title, String poster, Integer season,
                    Integer episode, UUID download, String directory) { }
            @Override public boolean hasPresentVideo(UUID id) { return false; }
        };
        new QbittorrentDownloadSynchronizer(repository(row), engine, library, new DownloadUpdatePublisher()).synchronize();
        assertEquals("SEEDING", row.status()); assertTrue(engine.commands.isEmpty());
    }

    static class ApiFixture implements AutoCloseable {
        final HttpServer server;
        final List<String> calls = new ArrayList<>();
        int loginStatus = 204, addStatus = 204;
        String loginBody = "", addBody = "", infoBody = "[]";
        boolean legacyCommands;
        int infoStatus = 200;

        ApiFixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v2/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                calls.add(exchange.getRequestMethod() + " " + path + " " + body);
                int status = 200; String result = "";
                if (path.endsWith("/login")) {
                    status = loginStatus; result = loginBody;
                    exchange.getResponseHeaders().add("Set-Cookie", "SID=test; Path=/");
                } else if (path.endsWith("/add")) { status = addStatus; result = addBody; }
                else if (path.endsWith("/info")) { status = infoStatus; result = infoBody; }
                else if (path.endsWith("/preferences")) result = "{\"queueing_enabled\":true}";
                else if (legacyCommands && (path.endsWith("/stop") || path.endsWith("/start"))) status = 404;
                byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
                if (status != 204) exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }
        QbittorrentClient client() {
            return new QbittorrentClient(new DownloadQbittorrentProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "user", "password"), new ObjectMapper());
        }
        @Override public void close() { server.stop(0); }
    }

    @Test void acceptsCurrent204LoginAndAddition() throws Exception {
        try (var api = new ApiFixture()) { api.client().add("magnet:?test", "/downloads/test"); }
    }

    @Test void acceptsLegacy200LoginAndAddition() throws Exception {
        try (var api = new ApiFixture()) {
            api.loginStatus = api.addStatus = 200; api.loginBody = api.addBody = "Ok.";
            api.client().add("magnet:?test", "/downloads/test");
        }
    }

    @Test void rejects200FailedLoginBeforeAddingTorrent() throws Exception {
        try (var api = new ApiFixture()) {
            api.loginStatus = 200; api.loginBody = "Fails.";
            assertThrows(QbittorrentClient.QbittorrentUnavailableException.class,
                    () -> api.client().add("magnet:?test", "/downloads/test"));
            assertEquals(1, api.calls.size());
        }
    }

    @Test void rejects200FailedAddition() throws Exception {
        try (var api = new ApiFixture()) {
            api.addStatus = 200; api.addBody = "Fails.";
            assertThrows(QbittorrentClient.QbittorrentUnavailableException.class,
                    () -> api.client().add("magnet:?test", "/downloads/test"));
        }
    }

    @Test void acceptsJsonSummaryOfNewTorrent() throws Exception {
        try (var api = new ApiFixture()) {
            api.addStatus = 200;
            api.addBody = "{\"success_count\":1,\"failure_count\":0,\"pending_count\":0,\"added_torrent_ids\":[\"hash\"]}";
            api.client().add("magnet:?test", "/downloads/test");
        }
    }

    @Test void acceptsAsynchronousUrlAddition() throws Exception {
        try (var api = new ApiFixture()) {
            api.addStatus = 202;
            api.addBody = "{\"success_count\":0,\"failure_count\":0,\"pending_count\":1,\"added_torrent_ids\":[]}";
            api.client().add("https://indexer.example/test.torrent", "/downloads/test");
        }
    }

    @Test void rejectsJsonSummaryWithoutSuccessfulOrPendingTorrent() throws Exception {
        try (var api = new ApiFixture()) {
            api.addStatus = 200;
            api.addBody = "{\"success_count\":0,\"failure_count\":1,\"pending_count\":0,\"added_torrent_ids\":[]}";
            assertThrows(QbittorrentClient.QbittorrentUnavailableException.class,
                    () -> api.client().add("magnet:?test", "/downloads/test"));
        }
    }

    static SpringDataDownloadRepository newRepository(List<DownloadRow> saved) {
        return (SpringDataDownloadRepository) Proxy.newProxyInstance(
                DownloadRegressionTest.class.getClassLoader(), new Class<?>[]{SpringDataDownloadRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "lockSubmissions" -> 1L;
                    case "findExistingRelease", "findByRelativeDirectory" -> Optional.empty();
                    case "save" -> { saved.add((DownloadRow) args[0]); yield args[0]; }
                    default -> throw new AssertionError("Unexpected repository call: " + method.getName());
                });
    }

    static DownloadSubmissionService.SeriesDownloadRequest newSeriesRequest() {
        return new DownloadSubmissionService.SeriesDownloadRequest(1, "Test", null, 1, 5,
                "test-release", "https://indexer.example/test.torrent", null, 1080, null, null);
    }

    @Test void persistsNewSeriesRequestWhenEngineReturns202Json() throws Exception {
        try (var api = new ApiFixture()) {
            api.addStatus = 202;
            api.addBody = "{\"success_count\":0,\"failure_count\":0,\"pending_count\":1}";
            var saved = new ArrayList<DownloadRow>();
            var storage = new dev.qlcstream.library.LocalStorageSettings(null) {
                @Override public String downloadDirectory() { return "incoming"; }
            };
            var result = new DownloadSubmissionService(newRepository(saved), api.client(), storage).submitSeries(newSeriesRequest());
            assertEquals(1, saved.size()); assertEquals(result.id(), saved.getFirst().id());
            assertTrue(api.calls.stream().anyMatch(call -> call.contains("/torrents/add")));
        }
    }

    @Test void recoversEngineTransferWithoutAddingAgainOrMovingFiles() throws Exception {
        try (var api = new ApiFixture()) {
            api.infoBody = "[{\"hash\":\"hash\",\"name\":\"test-release\",\"state\":\"downloading\",\"progress\":0.25,\"size\":1000,\"downloaded\":250,\"dlspeed\":10,\"eta\":75,\"save_path\":\"/downloads/incoming/original\"}]";
            var saved = new ArrayList<DownloadRow>();
            new DownloadSubmissionService(newRepository(saved), api.client(), null).submitSeries(newSeriesRequest());
            assertEquals(1, saved.size());
            assertEquals("incoming/original", saved.getFirst().relativeDirectory());
            assertEquals("hash", saved.getFirst().infoHash());
            assertTrue(api.calls.stream().noneMatch(call -> call.contains("/torrents/add")));
        }
    }

    @Test void fallsBackToLegacyCommandsAndSendsQueuePriority() throws Exception {
        try (var api = new ApiFixture()) {
            api.legacyCommands = true;
            var client = api.client();
            client.pause("hash");
            client.resume("hash");
            client.increasePriority("hash");
            client.decreasePriority("hash");
            assertEquals(List.of(
                    "POST /api/v2/torrents/stop hashes=hash",
                    "POST /api/v2/torrents/pause hashes=hash",
                    "POST /api/v2/torrents/start hashes=hash",
                    "POST /api/v2/torrents/resume hashes=hash",
                    "POST /api/v2/torrents/increasePrio hashes=hash",
                    "POST /api/v2/torrents/decreasePrio hashes=hash"),
                    api.calls.stream().filter(call -> call.contains("/torrents/")).toList());
        }
    }

    @Test void usesCurrentCommandsAndPreservesDownloadedFiles() throws Exception {
        try (var api = new ApiFixture()) {
            var client = api.client();
            client.pause("hash"); client.resume("hash"); client.recheck("hash"); client.remove("hash");
            assertTrue(api.calls.contains("POST /api/v2/torrents/stop hashes=hash"));
            assertTrue(api.calls.contains("POST /api/v2/torrents/start hashes=hash"));
            assertTrue(api.calls.contains("POST /api/v2/torrents/recheck hashes=hash"));
            assertTrue(api.calls.stream().anyMatch(call -> call.contains("/delete") && call.contains("deleteFiles=false")));
        }
    }
}
