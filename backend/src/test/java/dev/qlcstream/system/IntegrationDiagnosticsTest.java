package dev.qlcstream.system;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import dev.qlcstream.system.config.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IntegrationDiagnosticsTest {
    private HttpServer server;
    private ExecutorService executor;
    private String url;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> mode = new AtomicReference<>("ok");
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            var path = exchange.getRequestURI().getPath();
            paths.add(exchange.getRequestMethod() + " " + path);
            if (mode.get().equals("slow_body")) {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try { Thread.sleep(6000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                exchange.close();
                return;
            }
            int status = 200;
            String body;
            switch (mode.get()) {
                case "unauthorized" -> { status = 401; body = "private-secret"; }
                case "forbidden" -> { status = 403; body = "private-secret"; }
                case "invalid" -> body = "<html>private-secret</html>";
                case "empty" -> body = "{}";
                case "error" -> { status = 503; body = "private-secret"; }
                case "redirect" -> { status = 302; body = ""; exchange.getResponseHeaders().add("Location", url + "/secret"); }
                case "fails" -> body = "Fails.";
                case "timeout" -> {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    body = "{}";
                }
                default -> {
                    if (path.endsWith("/auth/login")) {
                        assertEquals("POST", exchange.getRequestMethod());
                        assertEquals("username=test-user&password=test-password", new String(exchange.getRequestBody().readAllBytes()));
                        exchange.getResponseHeaders().add("Set-Cookie", "SID=test-session; Path=/");
                        status = mode.get().equals("legacy") ? 200 : 204;
                        body = mode.get().equals("legacy") ? "Ok." : "";
                    } else if (path.endsWith("/app/version")) {
                        assertEquals("SID=test-session", exchange.getRequestHeaders().getFirst("Cookie"));
                        body = "v5.3.0";
                    } else if (path.endsWith("/configuration")) {
                        assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
                        body = "{\"images\":{},\"change_keys\":[]}";
                    } else {
                        assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-Api-Key"));
                        body = "{\"version\":\"2.3.0\"}";
                    }
                }
            }
            exchange.sendResponseHeaders(status, status == 204 ? -1 : body.getBytes().length);
            if (status != 204) exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/subpath";
    }

    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    private IntegrationProbe probe() { return probe(url); }
    private IntegrationProbe probe(String base) {
        return new IntegrationProbe(new TmdbConfigurationProperties(base, "test-token"),
                new ProwlarrProperties(base, "test-key"),
                new QbittorrentProperties(base, "test-user", "test-password"), new ObjectMapper());
    }

    @ParameterizedTest @ValueSource(strings = {"tmdb", "prowlarr", "qbittorrent"})
    void verifiesAuthenticatedReadsAndClassifiesFailures(String id) {
        var probe = probe();
        assertEquals("ok", probe.check(id));
        for (var failure : List.of("unauthorized", "forbidden", "invalid", "empty", "error", "redirect")) {
            mode.set(failure);
            assertEquals(switch (failure) {
                case "unauthorized", "forbidden" -> "authentication_rejected";
                case "error", "redirect" -> "http_error";
                default -> "invalid_response";
            }, probe.check(id), failure);
        }
        assertTrue(paths.stream().allMatch(path -> path.startsWith("GET ") || path.endsWith("/auth/login")));
        assertTrue(paths.stream().noneMatch(path -> path.contains("torrents")));
    }

    @Test void acceptsLegacyLoginAndRecognizesFailedLogin() {
        mode.set("legacy");
        assertEquals("ok", probe().check("qbittorrent"));
        mode.set("fails");
        assertEquals("authentication_rejected", probe().check("qbittorrent"));
    }

    @ParameterizedTest @ValueSource(strings = {"tmdb", "prowlarr", "qbittorrent"})
    void boundsTimeouts(String id) {
        mode.set("timeout");
        assertTimeoutPreemptively(Duration.ofSeconds(6), () -> assertEquals("timeout", probe().check(id)));
    }

    @Test void boundsTheEntireResponseIncludingASlowBody() {
        mode.set("slow_body");
        assertTimeoutPreemptively(Duration.ofSeconds(6), () -> assertEquals("timeout", probe().check("tmdb")));
    }

    @Test void exposesSafeMvcContractAndDoesNotCacheHttpResponses() throws Exception {
        mode.set("unauthorized");
        var service = new IntegrationDiagnostics(probe());
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new SystemStatusController(service)).build();
            var initial = mvc.perform(get("/api/system/integrations")).andReturn().getResponse();
            assertEquals(200, initial.getStatus());
            assertEquals("no-store", initial.getHeader("Cache-Control"));
            assertTrue(initial.getContentAsString().contains("checking"));
            await(() -> service.snapshot(false).stream().noneMatch(result -> result.status().equals("checking")));
            var response = mvc.perform(post("/api/system/integrations/check")).andReturn().getResponse();
            var body = response.getContentAsString();
            assertEquals(200, response.getStatus());
            assertTrue(body.contains("authentication_rejected"));
            assertTrue(body.contains("checkedAt"));
            assertTrue(body.contains("expiresAt"));
            for (var secret : List.of("private-secret", "test-token", "test-key", "test-user", "test-password", "test-session", url)) {
                assertFalse(body.contains(secret));
            }
            assertEquals(3, requests.get(), "Refresh during cooldown must reuse results");
        } finally { service.close(); }
    }

    @ParameterizedTest @ValueSource(strings = {"not a url", "ftp://localhost", "http://user:private-secret@localhost", "http://localhost?key=private-secret"})
    void rejectsInvalidAddressesWithoutSendingSecrets(String base) {
        for (var id : List.of("tmdb", "prowlarr", "qbittorrent")) assertEquals("invalid_configuration", probe(base).check(id));
        assertEquals(0, requests.get());
    }

    @Test void distinguishesStoppedServiceAndAllowsRecovery() throws Exception {
        int port = server.getAddress().getPort();
        var probe = probe();
        server.stop(0);
        assertEquals("connection_failed", probe.check("prowlarr"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/subpath/api/v1/system/status", exchange -> {
            byte[] body = "{\"version\":\"2.3.0\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        assertEquals("ok", probe.check("prowlarr"));
    }

    @Test void missingConfigurationMakesNoRequests() {
        var probe = new IntegrationProbe(new TmdbConfigurationProperties(url, ""),
                new ProwlarrProperties(url, ""), new QbittorrentProperties(url, "", ""), new ObjectMapper());
        try (var diagnostics = new CloseableDiagnostics(probe)) {
            assertTrue(diagnostics.service.snapshot(false).stream().allMatch(result -> result.status().equals("not_configured") && result.checkedAt() == null));
            assertEquals(0, requests.get());
        }
    }

    @Test void slowServiceDoesNotBlockOthersAndRefreshIsCoalesced() throws Exception {
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var counts = new ConcurrentHashMap<String, AtomicInteger>();
        var fake = new IntegrationProbe(new TmdbConfigurationProperties(url, "token"), new ProwlarrProperties(url, "key"),
                new QbittorrentProperties(url, "user", "pass"), new ObjectMapper()) {
            @Override String check(String id) {
                counts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
                if (id.equals("tmdb")) {
                    blocked.countDown();
                    try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    return "timeout";
                }
                return "ok";
            }
        };
        var clock = new MutableClock();
        var service = new IntegrationDiagnostics(fake, clock);
        try {
            assertTrue(service.snapshot(false).stream().allMatch(result -> result.status().equals("checking")));
            assertTrue(blocked.await(1, TimeUnit.SECONDS));
            await(() -> service.snapshot(false).stream().filter(result -> result.status().equals("available")).count() == 2);
            for (int i = 0; i < 20; i++) service.snapshot(true);
            assertTrue(counts.values().stream().allMatch(count -> count.get() == 1));
            release.countDown();
            await(() -> service.snapshot(false).stream().noneMatch(result -> result.status().equals("checking")));
            var first = service.snapshot(false);
            assertTrue(first.stream().allMatch(result -> result.checkedAt().equals(clock.instant()) && result.expiresAt().equals(clock.instant().plusSeconds(60))));
            clock.now = clock.now.plusSeconds(11);
            service.snapshot(true);
            await(() -> counts.values().stream().allMatch(count -> count.get() == 2));
            await(() -> service.snapshot(false).stream().noneMatch(result -> result.status().equals("checking")));
            clock.now = clock.now.plusSeconds(61);
            assertTrue(service.snapshot(false).stream().allMatch(result -> result.status().equals("checking")));
            await(() -> counts.values().stream().allMatch(count -> count.get() == 3));
        } finally { release.countDown(); service.close(); }
    }

    @Test void discardsOldAttemptAfterConfigurationChanges() throws Exception {
        var revision = new java.util.concurrent.atomic.AtomicReference<>(new Object());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var fake = new IntegrationProbe(new TmdbConfigurationProperties(url, "token"), new ProwlarrProperties(url, "key"),
                new QbittorrentProperties(url, "user", "pass"), new ObjectMapper()) {
            @Override Object revision(String id) { return revision.get(); }
            @Override String check(String id) {
                if (id.equals("tmdb") && first.getAndSet(false)) {
                    started.countDown();
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    finished.countDown();
                    return "authentication_rejected";
                }
                return "ok";
            }
        };
        var diagnostics = new IntegrationDiagnostics(fake);
        try {
            diagnostics.snapshot(false);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            revision.set(new Object());
            diagnostics.snapshot(false);
            await(() -> diagnostics.snapshot(false).stream().allMatch(item -> item.status().equals("available")));
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertTrue(diagnostics.snapshot(false).stream().allMatch(item -> item.status().equals("available")));
        } finally { release.countDown(); diagnostics.close(); fake.close(); }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }

    private static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-12T12:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }

    private static class CloseableDiagnostics implements AutoCloseable {
        final IntegrationDiagnostics service;
        CloseableDiagnostics(IntegrationProbe probe) { service = new IntegrationDiagnostics(probe); }
        public void close() { service.close(); }
    }
}
