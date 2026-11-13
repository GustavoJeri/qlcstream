package dev.qlcstream.system;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

@Service
class IntegrationDiagnostics {
    static final Duration VALIDITY = Duration.ofMinutes(1);
    static final Duration COOLDOWN = Duration.ofSeconds(10);
    private final IntegrationProbe probe;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.Map<String, Object> revisions = new java.util.HashMap<>();
    private final LinkedHashMap<String, Result> results = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    IntegrationDiagnostics(IntegrationProbe probe) { this(probe, Clock.systemUTC()); }

    IntegrationDiagnostics(IntegrationProbe probe, Clock clock) {
        this.probe = probe;
        this.clock = clock;
        for (var id : List.of("tmdb", "prowlarr", "qbittorrent")) {
            results.put(id, new Result(id, probe.configured(id), "unknown", "unknown", null, null, null));
        }
    }

    synchronized List<Result> snapshot(boolean refresh) {
        var now = clock.instant();
        for (var id : List.copyOf(results.keySet())) {
            var revision = probe.revision(id);
            if (revisions.get(id) != revision) {
                revisions.put(id, revision);
                results.put(id, new Result(id, probe.configured(id), "unknown", "unknown", null, null, null));
            }
            var current = results.get(id);
            if (!current.configured()) {
                results.put(id, new Result(id, false, "not_configured", "missing_configuration", null, null, null));
            } else if (!current.status().equals("checking") && (current.checkedAt() == null
                    || (refresh && !now.isBefore(current.retryAfter()))
                    || !now.isBefore(current.expiresAt()))) {
                var checking = new Result(id, true, "checking", "checking", current.checkedAt(), current.expiresAt(), now.plus(COOLDOWN));
                results.put(id, checking);
                workers.submit(() -> {
                    String reason;
                    try { reason = probe.check(id); }
                    catch (RuntimeException exception) { reason = "connection_failed"; }
                    complete(id, checking, reason);
                });
            }
        }
        return List.copyOf(results.values());
    }

    private synchronized void complete(String id, Result checking, String reason) {
        // Only the current attempt may publish a result; polling never starts overlapping work.
        if (results.get(id) != checking || revisions.get(id) != probe.revision(id)) return;
        var now = clock.instant();
        var status = reason.equals("ok") ? "available"
                : reason.equals("authentication_rejected") ? "authentication_rejected" : "unavailable";
        results.put(id, new Result(id, true, status, reason, now, now.plus(VALIDITY), checking.retryAfter()));
    }

    @PreDestroy
    void close() { workers.shutdownNow(); }

    record Result(String id, boolean configured, String status, String reason, Instant checkedAt,
            Instant expiresAt, Instant retryAfter) { }
}
