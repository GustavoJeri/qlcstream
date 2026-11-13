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

    SystemStatusController(IntegrationDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        return Map.of(
                "application", "QLC Stream",
                "status", "ready",
                "timestamp", Instant.now());
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
