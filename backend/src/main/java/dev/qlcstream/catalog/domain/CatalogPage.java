package dev.qlcstream.catalog.domain;

import java.util.List;

public record CatalogPage<T>(List<T> items, int page, int totalPages, long totalResults) {
    public CatalogPage {
        items = items == null ? List.of() : List.copyOf(items);
        page = Math.max(page, 1);
        totalPages = Math.clamp(totalPages, 1, 500);
        totalResults = Math.max(totalResults, 0);
    }

    public boolean hasNext() {
        return page < totalPages;
    }

    public <R> CatalogPage<R> map(java.util.function.Function<T, R> mapper) {
        return new CatalogPage<>(items.stream().map(mapper).toList(), page, totalPages, totalResults);
    }
}
