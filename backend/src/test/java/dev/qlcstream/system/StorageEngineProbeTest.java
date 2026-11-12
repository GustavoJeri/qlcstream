package dev.qlcstream.system;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import dev.qlcstream.system.config.QbittorrentProperties;
import tools.jackson.databind.ObjectMapper;

class StorageEngineProbeTest {
    String probe(int loginStatus, String loginBody, String preferences, ArrayList<String> calls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path);
            boolean login = path.endsWith("/auth/login");
            if (!login && !"SID=test".equals(exchange.getRequestHeaders().getFirst("Cookie"))) {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return;
            }
            exchange.getResponseHeaders().add("Set-Cookie", "SID=test; Path=/");
            var body = (login ? loginBody : preferences).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int status = login ? loginStatus : 200;
            exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
            if (status != 204) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            return new StorageEngineProbe(new QbittorrentProperties("http://127.0.0.1:" + server.getAddress().getPort(), "test", "test"), new ObjectMapper()).mapping();
        } finally { server.stop(0); }
    }

    @Test void authenticatedReadCompatibleIsStillUnproven() throws Exception {
        for (int status : new int[]{200, 204}) {
            var calls = new ArrayList<String>();
            assertEquals("compatible_unproven", probe(status, "Ok.", "{\"save_path\":\"/downloads/\"}", calls));
            assertEquals(java.util.List.of("POST /api/v2/auth/login", "GET /api/v2/app/preferences"), calls);
        }
    }
    @Test void rejectsIncompatibleAndTemporaryDestinations() throws Exception {
        for (var prefs : new String[]{"{\"save_path\":\"/other\"}", "{\"save_path\":\"/downloads\",\"temp_path_enabled\":true,\"temp_path\":\"/tmp\"}"})
            assertEquals("mapping_incompatible", probe(200, "Ok.", prefs, new ArrayList<>()));
    }
    @Test void rejectsTraversalAndPrefixLookalikes() {
        for (var path : new String[]{"/downloads-other", "/downloads/../outside", "relative", "C:/downloads", "/downloads\\escape"})
            assertFalse(StorageEngineProbe.insideEngineRoot(path), path);
        assertTrue(StorageEngineProbe.insideEngineRoot("/downloads/incoming/"));
    }
    @Test void missingMalformedOrUnauthorizedIsInconclusive() throws Exception {
        assertEquals("engine_authentication_failed", probe(200, "Fails.", "{}", new ArrayList<>()));
        for (var body : new String[]{"{}", "invalid json", "{\"save_path\":42}"})
            assertEquals("engine_unavailable", probe(200, "Ok.", body, new ArrayList<>()));
        assertEquals("engine_not_configured", new StorageEngineProbe(new QbittorrentProperties("", "", ""), new ObjectMapper()).mapping());
    }
}
