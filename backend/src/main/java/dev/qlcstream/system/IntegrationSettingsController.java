package dev.qlcstream.system;

import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import dev.qlcstream.system.config.*;

@RestController
@RequestMapping("/api/system/integration-settings")
class IntegrationSettingsController {
    private final IntegrationSettings settings;
    private final ObjectMapper json;
    IntegrationSettingsController(IntegrationSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }
    @GetMapping
    ResponseEntity<List<IntegrationSettings.State>> states() { return response(settings.states()); }

    @GetMapping("/key")
    ResponseEntity<Map<String, Boolean>> key() { return response(Map.of("ready", settings.keyReady())); }

    @PostMapping("/key")
    ResponseEntity<Map<String, Boolean>> provisionKey() {
        settings.provisionKey();
        return response(Map.of("ready", true));
    }

    @PutMapping("/{id}")
    ResponseEntity<IntegrationSettings.State> update(@PathVariable String id, @RequestBody IntegrationSettings.Change change) {
        return response(settings.update(id, change));
    }

    @PostMapping("/{id}/test")
    ResponseEntity<Map<String, Object>> test(@PathVariable String id, @RequestBody IntegrationSettings.Change change) {
        var c = settings.candidate(id, change);
        try (var probe = new IntegrationProbe(new TmdbConfigurationProperties(c.baseUrl(), c.secret()),
                new ProwlarrProperties(c.baseUrl(), c.secret()), new QbittorrentProperties(c.baseUrl(), c.username(), c.secret()), json)) {
            boolean configured = c.configured(id);
            return response(Map.of("id", id, "configured", configured, "reason", configured ? probe.check(id) : "missing_configuration"));
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("reason", "invalid_configuration")); }

    @ExceptionHandler({IllegalStateException.class, org.springframework.dao.DataAccessException.class})
    ResponseEntity<Map<String, String>> unavailable() { return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("reason", "settings_unavailable")); }

    private static <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
