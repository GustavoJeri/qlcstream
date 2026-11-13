package dev.qlcstream.system;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import tools.jackson.databind.ObjectMapper;
import dev.qlcstream.system.config.*;

/** Manual UI fixture: only loopback servers, dummy credentials, no database or download client. */
public class IntegrationDiagnosticsPreview {
    private static HttpServer prowlarr;

    public static void main(String[] args) throws Exception {
        var assets = Path.of(args[0]).toAbsolutePath().normalize();
        var peers = server(18081);
        peers.createContext("/configuration", exchange -> {
            var body = "{\"images\":{},\"change_keys\":[]}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        peers.createContext("/api/v2/auth/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "SID=preview; Path=/");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        peers.createContext("/api/v2/app/version", exchange -> {
            byte[] body = "v5.3.0".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        peers.start();
        startProwlarr();
        var probe = new IntegrationProbe(new TmdbConfigurationProperties("http://127.0.0.1:18081", "preview-token"),
                new ProwlarrProperties("http://127.0.0.1:18082", "preview-key"),
                new QbittorrentProperties("http://127.0.0.1:18081", "preview-user", "preview-password"), new ObjectMapper());
        var diagnostics = new IntegrationDiagnostics(probe);
        var mvc = MockMvcBuilders.standaloneSetup(new SystemStatusController(diagnostics)).build();
        var ui = server(18080);
        ui.createContext("/", exchange -> {
            try {
                var path = exchange.getRequestURI().getPath();
                byte[] body;
                int status = 200;
                String type = "application/json";
                if (path.startsWith("/api/system/")) {
                    var request = exchange.getRequestMethod().equals("POST") ? post(path) : get(path);
                    var response = mvc.perform(request).andReturn().getResponse();
                    body = response.getContentAsByteArray();
                    status = response.getStatus();
                    exchange.getResponseHeaders().add("Cache-Control", "no-store");
                } else if (path.equals("/test/prowlarr/stop") && exchange.getRequestMethod().equals("POST")) {
                    if (prowlarr != null) { prowlarr.stop(0); prowlarr = null; }
                    body = "{}".getBytes();
                } else if (path.equals("/test/prowlarr/start") && exchange.getRequestMethod().equals("POST")) {
                    if (prowlarr == null) startProwlarr();
                    body = "{}".getBytes();
                } else if (path.equals("/api/library/storage")) {
                    body = "{\"downloadDirectory\":\"preview-only\"}".getBytes();
                } else if (path.startsWith("/api/")) {
                    body = "[]".getBytes();
                } else {
                    var file = assets.resolve(path.substring(1)).normalize();
                    if (!file.startsWith(assets)) { exchange.sendResponseHeaders(400, -1); exchange.close(); return; }
                    if (!Files.isRegularFile(file)) file = assets.resolve("index.html");
                    type = file.toString().endsWith(".js") ? "text/javascript"
                            : file.toString().endsWith(".css") ? "text/css"
                            : file.toString().endsWith(".woff2") ? "font/woff2" : "text/html";
                    body = Files.readAllBytes(file);
                }
                exchange.getResponseHeaders().add("Content-Type", type);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception exception) {
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ui.stop(0); peers.stop(0); if (prowlarr != null) prowlarr.stop(0); diagnostics.close();
        }));
        ui.start();
        System.out.println("Isolated preview: http://127.0.0.1:18080/settings");
    }

    private static HttpServer server(int port) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        return server;
    }

    private static synchronized void startProwlarr() throws Exception {
        prowlarr = server(18082);
        prowlarr.createContext("/api/v1/system/status", exchange -> {
            byte[] body = "{\"version\":\"2.3.0\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        prowlarr.start();
    }
}
