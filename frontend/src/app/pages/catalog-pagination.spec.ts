import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { CatalogPage } from './catalog.page';
import { SeriesPage } from './series.page';

describe('Catalog pagination', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }));
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.unstubAllGlobals();
  });

  function request(url: string, page: number, sort?: string): TestRequest {
    return http.expectOne(candidate => candidate.url === url
      && candidate.params.get('page') === String(page)
      && candidate.params.get('sort') === (sort ?? null));
  }

  function respond(request: TestRequest, page: number, ids: number[]) {
    request.flush({
      items: ids.map(tmdbId => ({ tmdbId, title: 'Filme', name: 'Série' })),
      page, totalPages: 3, totalResults: 4, hasNext: page < 3,
    });
    for (const pending of http.match(candidate => candidate.url.startsWith('/api/library/'))) {
      pending.flush([]);
    }
  }

  it('cancels an older movie page and appends three pages without duplicate IDs', () => {
    const component = TestBed.createComponent(CatalogPage).componentInstance;
    const url = '/api/catalog/discover';
    respond(request(url, 1, 'POPULARITY'), 1, [1]);
    component.loadMore();
    const old = request(url, 2, 'POPULARITY');
    component.setSort({ target: { value: 'NEWEST' } } as unknown as Event);
    expect(old.cancelled).toBe(true);
    respond(request(url, 1, 'NEWEST'), 1, [9, 8]);
    component.loadMore();
    respond(request(url, 2, 'NEWEST'), 2, [8, 7, 7]);
    component.loadMore();
    respond(request(url, 3, 'NEWEST'), 3, [6]);
    expect(component.movies().map(item => item.tmdbId)).toEqual([9, 8, 7, 6]);
    expect(component.hasMoreMovies()).toBe(false);
  });

  it('keeps textual search in relevance order after sorting discovery', () => {
    const component = TestBed.createComponent(SeriesPage).componentInstance;
    respond(request('/api/catalog/series/trending', 1), 1, [1]);
    component.selectFilter('TOP_RATED');
    respond(request('/api/catalog/series/discover', 1, 'RATING'), 1, [2]);
    component.query.set('Matrix');
    component.search();
    const search = request('/api/catalog/series/search', 1);
    expect(search.request.params.get('query')).toBe('Matrix');
    respond(search, 1, [7, 3]);
    expect(component.series().map(item => item.tmdbId)).toEqual([7, 3]);
  });

  it('keeps the series ranking in Trending and resets discovery when the filter changes', () => {
    const component = TestBed.createComponent(SeriesPage).componentInstance;
    respond(request('/api/catalog/series/trending', 1), 1, [10]);
    component.loadMore();
    const old = request('/api/catalog/series/trending', 2);
    component.selectFilter('RECENT');
    expect(old.cancelled).toBe(true);
    const url = '/api/catalog/series/discover';
    const first = request(url, 1, 'NEWEST');
    expect(first.request.params.get('collection')).toBe('RECENT');
    respond(first, 1, [9, 8]);
    component.loadMore();
    respond(request(url, 2, 'NEWEST'), 2, [8, 7, 7]);
    component.loadMore();
    respond(request(url, 3, 'NEWEST'), 3, [6]);
    expect(component.series().map(item => item.tmdbId)).toEqual([9, 8, 7, 6]);
    expect(component.hasMoreSeries()).toBe(false);
  });
});
