package dev.qlcstream.catalog.application.port.in;

import dev.qlcstream.catalog.domain.CatalogCollection;
import dev.qlcstream.catalog.domain.CatalogPage;
import dev.qlcstream.catalog.domain.CatalogSort;
import dev.qlcstream.catalog.domain.Movie;

public interface BrowseCatalogUseCase {

    CatalogPage<Movie> trending(String language, int page, CatalogSort sort);

    CatalogPage<Movie> discover(CatalogCollection collection, String language, int page, CatalogSort sort);

    CatalogPage<Movie> search(String query, String language, int page, CatalogSort sort);
}
