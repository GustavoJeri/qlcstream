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
    private final Map<Key, Entry> entries = new LinkedHashMap<>(64, .75f, true);
    private final Duration catalogTtl;
    private final Duration searchTtl;

    TmdbCatalogCache(@Value("${qlc-stream.tmdb.cache.max-entries:300}") int maxEntries,
                     @Value("${qlc-stream.tmdb.cache.catalog-ttl:10m}") Duration catalogTtl,
                     @Value("${qlc-stream.tmdb.cache.search-ttl:3m}") Duration searchTtl) {
        this.maxEntries = Math.max(20, maxEntries);
        this.catalogTtl = catalogTtl;
        this.searchTtl = searchTtl;
    }

    Duration catalogTtl() { return catalogTtl; }
    Duration searchTtl() { return searchTtl; }

    <T> T get(Key key, Duration ttl, Supplier<T> loader) {
        synchronized (entries) {
            var entry = entries.get(key);
            if (entry != null && entry.expiresAt().isAfter(Instant.now())) return cast(entry.value());
            entries.remove(key);
        }
        T value = loader.get();
        if (value != null && !ttl.isZero() && !ttl.isNegative()) {
            synchronized (entries) {
                entries.put(key, new Entry(value, Instant.now().plus(ttl)));
                while (entries.size() > maxEntries) entries.remove(entries.keySet().iterator().next());
            }
        }
        return value;
    }

    Key key(TmdbProperties configuration, String endpoint, Object... values) {
        var parameters = java.util.Arrays.stream(values)
                .map(value -> value == null ? "" : value.toString().trim().replaceAll("\\s+", " ")
                        .toLowerCase(java.util.Locale.ROOT))
                .toList();
        return new Key(configuration, endpoint, parameters);
    }

    record Key(TmdbProperties configuration, String endpoint, java.util.List<String> parameters) { }

    @SuppressWarnings("unchecked") private static <T> T cast(Object value) { return (T) value; }
    private record Entry(Object value, Instant expiresAt) { }
}
