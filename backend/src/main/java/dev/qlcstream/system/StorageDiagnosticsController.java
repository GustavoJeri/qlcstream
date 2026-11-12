package dev.qlcstream.system;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StorageDiagnosticsController {
    private final StorageDiagnostics diagnostics;
    StorageDiagnosticsController(StorageDiagnostics diagnostics) { this.diagnostics = diagnostics; }

    @PostMapping("/api/system/storage/check")
    ResponseEntity<StorageDiagnostics.Result> check() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(diagnostics.check());
    }
}
