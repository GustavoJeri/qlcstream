package dev.qlcstream.system;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/** Remote error bodies can echo credentials; never forward or log those exceptions. */
@RestControllerAdvice(basePackages = {"dev.qlcstream.catalog", "dev.qlcstream.search"})
class IntegrationClientExceptionHandler {
    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(502).cacheControl(CacheControl.noStore())
                .body(Map.of("reason", "integration_unavailable"));
    }
}
