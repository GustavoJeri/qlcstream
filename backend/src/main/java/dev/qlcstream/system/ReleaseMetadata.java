package dev.qlcstream.system;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ReleaseMetadata {

    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$");
    private static final Pattern SCHEMA_VERSION = Pattern.compile("^V\\d+$");
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final Set<String> CHANNELS = Set.of("candidate", "release");

    private final Info info;

    public ReleaseMetadata(@Value("${QLCSTREAM_RELEASE_COMMIT:}") String commit) {
        Properties properties = loadProperties();
        String version = requireMatch(properties.getProperty("qlcstream.release.version"), SEMVER, "version");
        String channel = requireChannel(properties.getProperty("qlcstream.release.channel"), version);
        String schemaVersion = requireMatch(properties.getProperty("qlcstream.release.schema-version"), SCHEMA_VERSION,
                "schema-version");
        String sanitizedCommit = sanitizeCommit(commit);
        this.info = new Info(version, channel, schemaVersion, sanitizedCommit);
    }

    public Info info() {
        return info;
    }

    private static Properties loadProperties() {
        ClassPathResource resource = new ClassPathResource("release-metadata.properties");
        Properties properties = new Properties();
        try (InputStream stream = resource.getInputStream()) {
            properties.load(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Release metadata resource could not be loaded", exception);
        }
        return properties;
    }

    private static String requireMatch(String value, Pattern pattern, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (!pattern.matcher(trimmed).matches()) {
            throw new IllegalStateException("Invalid release metadata field: " + field);
        }
        return trimmed;
    }

    private static String requireChannel(String value, String version) {
        String trimmed = value == null ? "" : value.trim();
        if (!CHANNELS.contains(trimmed)) {
            throw new IllegalStateException("Invalid release metadata field: channel");
        }
        boolean prerelease = version.contains("-");
        if ((prerelease && !"candidate".equals(trimmed)) || (!prerelease && !"release".equals(trimmed))) {
            throw new IllegalStateException("Release channel does not match version");
        }
        return trimmed;
    }

    static String sanitizeCommit(String value) {
        if (value == null) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase();
        return COMMIT_SHA.matcher(normalized).matches() ? normalized : "unknown";
    }

    public record Info(String version, String channel, String schemaVersion, String commit) {
    }
}
