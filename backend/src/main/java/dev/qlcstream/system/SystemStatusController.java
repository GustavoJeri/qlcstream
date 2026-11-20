package dev.qlcstream.system;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/system")
class SystemStatusController {

    private final IntegrationDiagnostics diagnostics;
    private final ReleaseMetadata releaseMetadata;

    SystemStatusController(IntegrationDiagnostics diagnostics, ReleaseMetadata releaseMetadata) {
        this.diagnostics = diagnostics;
        this.releaseMetadata = releaseMetadata;
    }

    @GetMapping("/status")
    ResponseEntity<Map<String, Object>> status() {
        ReleaseMetadata.Info release = releaseMetadata.info();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "application", "QLC Stream",
                        "status", "ready",
                        "version", release.version(),
                        "channel", release.channel(),
                        "schemaVersion", release.schemaVersion(),
                        "commit", release.commit(),
                        "timestamp", Instant.now()));
    }

    @GetMapping("/integrations")
    ResponseEntity<List<IntegrationDiagnostics.Result>> integrations() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(diagnostics.snapshot(false));
    }

    @PostMapping("/integrations/check")
    ResponseEntity<List<IntegrationDiagnostics.Result>> check() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(diagnostics.snapshot(true));
    }
}
