package dev.qlcstream.system;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** One backend owns the settings database. Each operation captures one immutable configuration. */
@Service
public class IntegrationSettings {
    static final List<String> IDS = List.of("tmdb", "prowlarr", "qbittorrent");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Map<String, Configuration> environment;
    private final String keyFile;
    private IntegrationSecretCipher activeCipher;
    private volatile Map<String, Configuration> managed;

    public IntegrationSettings(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json, Environment env) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.keyFile = env.getProperty("qlc-stream.integrations.key-file", "");
        var initial = new LinkedHashMap<String, Configuration>();
        for (var id : IDS) {
            var prefix = "qlc-stream." + id + ".";
            initial.put(id, new Configuration(env.getProperty(prefix + "base-url", ""),
                    env.getProperty(prefix + "username", ""), env.getProperty(prefix +
                    (id.equals("tmdb") ? "api-token" : id.equals("prowlarr") ? "api-key" : "password"), "")));
        }
        environment = Map.copyOf(initial);
        var loaded = new LinkedHashMap<String, Configuration>();
        try { jdbc.query("SELECT integration_id, encrypted_config FROM integration_settings", rs -> {
            String id = rs.getString(1);
            requireId(id);
            loaded.put(id, json.readValue(cipher().decrypt(id, rs.getString(2)), Configuration.class));
        });
        } catch (Exception failure) { throw new IllegalStateException("Unable to load integration settings; restore the matching key and database."); }
        managed = Map.copyOf(loaded);
    }

    public Configuration effective(String id) {
        requireId(id);
        return managed.getOrDefault(id, environment.get(id));
    }

    synchronized List<State> states() { return IDS.stream().map(this::state).toList(); }

    synchronized boolean keyReady() {
        try { cipher(); return true; }
        catch (IllegalStateException unavailable) { return false; }
    }

    synchronized void provisionKey() {
        if (keyReady()) return;
        // A missing/replaced key must never be regenerated for an existing installation.
        if (activeCipher != null || !managed.isEmpty() || keyFile.isBlank()) throw new IllegalStateException();
        var destination = Path.of(keyFile).toAbsolutePath();
        Path temporary = null;
        try {
            if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException();
            var directory = destination.getParent();
            Files.createDirectories(directory);
            var attributes = Files.getFileStore(directory).supportsFileAttributeView("posix")
                    ? new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}
                    : new java.nio.file.attribute.FileAttribute<?>[0];
            temporary = Files.createTempFile(directory, ".integration-key-", ".tmp", attributes);
            byte[] bytes = new byte[32];
            new java.security.SecureRandom().nextBytes(bytes);
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                var content = java.nio.ByteBuffer.wrap(java.util.Base64.getEncoder().encode(bytes));
                while (content.hasRemaining()) channel.write(content);
                channel.force(true);
            }
            // Publish a complete file atomically, failing if another process created the destination.
            Files.createLink(destination, temporary);
            cipher();
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to provision integration settings key; check the protected directory.");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (java.io.IOException failure) { /* The exclusive temporary file contains no application data. */ }
            }
        }
    }

    private State state(String id) {
        var config = effective(id);
        return new State(id, managed.containsKey(id) ? "application" : "environment", safeAddress(config.baseUrl()),
                !config.username().isBlank(), !config.secret().isBlank(), config.configured(id));
    }

    private static String safeAddress(String address) {
        try {
            var uri = URI.create(address);
            return uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && uri.getHost() != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) ? address : "";
        } catch (RuntimeException failure) { return ""; }
    }

    synchronized State update(String id, Change change) {
        requireId(id);
        if (change == null) throw new IllegalArgumentException();
        var next = new LinkedHashMap<>(managed);
        if ("environment".equals(change.source())) {
            if (change.baseUrl() != null || change.username() != null || change.secret() != null)
                throw new IllegalArgumentException();
            transaction.executeWithoutResult(status -> jdbc.update("DELETE FROM integration_settings WHERE integration_id = ?", id));
            next.remove(id);
        } else {
            if (!"application".equals(change.source())) throw new IllegalArgumentException();
            var candidate = candidate(id, change);
            String encrypted = cipher().encrypt(id, json.writeValueAsString(candidate));
            transaction.executeWithoutResult(status -> jdbc.update("INSERT INTO integration_settings(integration_id, encrypted_config) VALUES (?, ?) "
                    + "ON CONFLICT (integration_id) DO UPDATE SET encrypted_config = EXCLUDED.encrypted_config", id, encrypted));
            next.put(id, candidate);
        }
        // Publication follows the successful database commit. Failed writes retain the previous snapshot.
        managed = Map.copyOf(next);
        return state(id);
    }

    Configuration candidate(String id, Change change) {
        requireId(id);
        if (change == null || change.source() != null && !change.source().equals("application")) throw new IllegalArgumentException();
        var current = effective(id);
        String secret = current.secret();
        if (change.secret() != null) {
            var action = change.secret();
            if ("clear".equals(action.action()) && action.value() == null) secret = "";
            else if ("replace".equals(action.action()) && action.value() != null && !action.value().isBlank()) secret = action.value();
            else throw new IllegalArgumentException();
        }
        var result = new Configuration(change.baseUrl() == null ? current.baseUrl() : change.baseUrl(),
                change.username() == null ? current.username() : change.username(), secret);
        if (result.baseUrl().length() > 2048 || result.username().length() > 256 || secret.length() > 8192
                || secret.contains("\r") || secret.contains("\n"))
            throw new IllegalArgumentException();
        if (!result.baseUrl().isBlank()) {
            var uri = URI.create(result.baseUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        }
        return result;
    }

    private synchronized IntegrationSecretCipher cipher() {
        try {
            var provided = new IntegrationSecretCipher(Files.readString(Path.of(keyFile)).trim());
            if (activeCipher == null) activeCipher = provided;
            if (!activeCipher.sameKey(provided)) throw new IllegalStateException();
            return activeCipher;
        }
        catch (Exception failure) { throw new IllegalStateException("Integration settings key unavailable or invalid."); }
    }

    static void requireId(String id) { if (!IDS.contains(id)) throw new IllegalArgumentException(); }

    public record Configuration(String baseUrl, String username, String secret) {
        public boolean configured(String id) { return !secret.isBlank() && (id.equals("tmdb") || !baseUrl.isBlank())
                && (!id.equals("qbittorrent") || !username.isBlank()); }
        @Override public String toString() { return "IntegrationConfiguration[redacted]"; }
    }
    record State(String id, String source, String baseUrl, boolean usernamePresent, boolean secretPresent, boolean configured) { }
    record Change(String source, String baseUrl, String username, SecretChange secret) {
        @Override public String toString() { return "IntegrationChange[redacted]"; }
    }
    record SecretChange(String action, String value) {
        @Override public String toString() { return "SecretChange[redacted]"; }
    }
}
