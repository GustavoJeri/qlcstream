package dev.qlcstream.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class SystemStatusControllerTest {

    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    void statusExposesReleaseIdentityWithoutGitAndDisablesCaching() throws Exception {
        ReleaseMetadata metadata = new ReleaseMetadata("0123456789abcdef0123456789abcdef01234567");
        SystemStatusController controller = new SystemStatusController(mock(IntegrationDiagnostics.class), metadata);

        ResponseEntity<Map<String, Object>> response = controller.status();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody())
                .containsEntry("application", "QLC Stream")
                .containsEntry("status", "ready")
                .containsEntry("version", metadata.info().version())
                .containsEntry("channel", metadata.info().channel())
                .containsEntry("schemaVersion", metadata.info().schemaVersion())
                .containsEntry("commit", "0123456789abcdef0123456789abcdef01234567")
                .containsKey("timestamp");
    }

    @Test
    void releaseMetadataMatchesRootVersionAndLatestFlywayMigration() throws Exception {
        ReleaseMetadata metadata = new ReleaseMetadata("");

        Path versionFile = Files.isRegularFile(Path.of("..", "VERSION"))
                ? Path.of("..", "VERSION").normalize()
                : Path.of("VERSION").normalize();
        assertThat(Files.isRegularFile(versionFile)).isTrue();
        assertThat(Files.readString(versionFile).trim()).isEqualTo(metadata.info().version());

        Path migrationDir = Files.isDirectory(Path.of("src", "main", "resources", "db", "migration"))
                ? Path.of("src", "main", "resources", "db", "migration")
                : Path.of("backend", "src", "main", "resources", "db", "migration");
        int latest = Files.list(migrationDir)
                .map(path -> MIGRATION.matcher(path.getFileName().toString()))
                .filter(Matcher::matches)
                .map(matcher -> Integer.parseInt(matcher.group(1)))
                .max(Comparator.naturalOrder())
                .orElseThrow();
        assertThat(metadata.info().schemaVersion()).isEqualTo("V" + latest);
        assertThat(metadata.info().commit()).isEqualTo("unknown");
    }

    @Test
    void invalidCommitValuesAreSanitizedToUnknown() {
        assertThat(ReleaseMetadata.sanitizeCommit(null)).isEqualTo("unknown");
        assertThat(ReleaseMetadata.sanitizeCommit("")).isEqualTo("unknown");
        assertThat(ReleaseMetadata.sanitizeCommit("not-a-sha")).isEqualTo("unknown");
        assertThat(ReleaseMetadata.sanitizeCommit("fictional-secret-value\nline2")).isEqualTo("unknown");
        assertThat(ReleaseMetadata.sanitizeCommit("ABCDEF0123456789abcdef0123456789abcdef01"))
                .isEqualTo("abcdef0123456789abcdef0123456789abcdef01");
    }
}
