package dev.qlcstream.catalog.infrastructure.tmdb;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class TmdbCatalogCache {
    private final int maxEntries;
    private final Map<String, Entry> entries = new LinkedHashMap<>(64, .75f, true);
    TmdbCatalogCache(@Value("${qlc-stream.tmdb.cache.max-entries:300}") int maxEntries) { this.maxEntries = Math.max(20, maxEntries); }
    synchronized <T> T get(String key, Duration ttl, Supplier<T> loader) {
        var now = Instant.now(); var entry = entries.get(key);
        if (entry != null && entry.expiresAt().isAfter(now)) return cast(entry.value());
        entries.remove(key); T value = loader.get();
        if (value != null) { entries.put(key, new Entry(value, now.plus(ttl))); while (entries.size() > maxEntries) entries.remove(entries.keySet().iterator().next()); }
        return value;
    }
    String key(String endpoint, Object... values) { var key = new StringBuilder(endpoint); for (var value : values) key.append('|').append(value == null ? "" : value.toString().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT)); return key.toString(); }
    @SuppressWarnings("unchecked") private static <T> T cast(Object value) { return (T) value; }
    private record Entry(Object value, Instant expiresAt) { }
}
