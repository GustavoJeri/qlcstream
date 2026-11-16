import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { CatalogPage } from './catalog.page';
import { CatalogNavigationStateService } from '../core/catalog-navigation-state.service';

describe('Catalog search navigation', () => {
  beforeEach(() => {
    vi.stubGlobal('matchMedia', () => ({matches: true}));
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { callback(0); return 1; });
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]});
  });
  afterEach(() => {
    try { TestBed.inject(HttpTestingController).verify(); }
    finally { TestBed.resetTestingModule(); vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers(); }
  });
  function flushLibrary() {
    for (const request of TestBed.inject(HttpTestingController).match(r => r.url.startsWith('/api/library/'))) request.flush([]);
  }
  it.each(['typing', 'clear button'])('preserves the current filter when clearing a search by %s after restoring navigation', async (method) => {
    const params = new BehaviorSubject(convertToParamMap({}));
    const snapshot = {queryParamMap: params.value};
    TestBed.overrideProvider(ActivatedRoute, {useValue: {queryParamMap: params, snapshot}});
    vi.spyOn(TestBed.inject(Router), 'navigate').mockImplementation((_commands, options) => {
      snapshot.queryParamMap = convertToParamMap(options?.queryParams?.['q'] ? {q: options.queryParams['q']} : {});
      params.next(snapshot.queryParamMap);
      return Promise.resolve(true);
    });
    TestBed.inject(CatalogNavigationStateService).save({
      query: '', lastSubmittedQuery: '', activeFilter: 'POPULAR', sortBy: 'POPULARITY',
      movies: [], unavailablePosterIds: [], currentPage: 2, hasMoreMovies: false, scrollY: 700,
    });
    const page = TestBed.createComponent(CatalogPage).componentInstance;
    const http = TestBed.inject(HttpTestingController);
    flushLibrary();
    page.selectFilter('TOP_RATED');
    http.expectOne(r => r.url === '/api/catalog/discover').flush({items: [{tmdbId: 20}], page: 1, hasNext: false});
    flushLibrary();
    vi.useFakeTimers();
    page.onQuery({target: {value: 'Matrix'}} as unknown as Event);
    await vi.advanceTimersByTimeAsync(300);
    http.expectOne(r => r.url === '/api/catalog/search').flush({items: [{tmdbId: 30}], page: 1, hasNext: false});
    flushLibrary();
    if (method === 'typing') page.onQuery({target: {value: ''}} as unknown as Event);
    else page.clearSearch();
    await vi.advanceTimersByTimeAsync(300);
    flushLibrary();
    expect(page.activeFilter()).toBe('TOP_RATED');
    expect(page.sortBy()).toBe('RATING');
    http.expectOne(r => r.url === '/api/catalog/discover' && r.params.get('collection') === 'TOP_RATED'
      && r.params.get('sort') === 'RATING').flush({items: [{tmdbId: 21}], page: 1, hasNext: false});
    flushLibrary();
    expect(snapshot.queryParamMap.has('q')).toBe(false);
    expect(page.lastSubmittedQuery()).toBe('');
    expect(page.currentPage()).toBe(1);
    expect(page.movies().map(movie => movie.tmdbId)).toEqual([21]);
  });
  it('keeps the route synchronized and restores typed search plus loaded pages', async () => {
    const params = new BehaviorSubject(convertToParamMap({}));
    const snapshot = {queryParamMap: params.value};
    TestBed.overrideProvider(ActivatedRoute, {useValue: {queryParamMap: params, snapshot}});
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockImplementation((_commands, options) => {
      snapshot.queryParamMap = convertToParamMap(options?.queryParams?.['q'] ? {q: options.queryParams['q']} : {});
      params.next(snapshot.queryParamMap);
      return Promise.resolve(true);
    });
    const fixture = TestBed.createComponent(CatalogPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(r => r.url === '/api/catalog/discover').flush({items: [{tmdbId: 1}], page: 1, hasNext: false});
    flushLibrary();
    const page = fixture.componentInstance;
    vi.useFakeTimers();
    page.onQuery({target: {value: 'Matrix'}} as unknown as Event);
    await vi.advanceTimersByTimeAsync(300);
    vi.useRealTimers();
    http.expectOne(r => r.url === '/api/catalog/search').flush({items: [{tmdbId: 2}], page: 1, hasNext: true});
    flushLibrary();
    expect(navigate).toHaveBeenCalledWith([], expect.objectContaining({queryParams: {q: 'Matrix'}, replaceUrl: true}));
    expect(snapshot.queryParamMap.get('q')).toBe('Matrix');
    page.loadMore();
    http.expectOne(r => r.url === '/api/catalog/search' && r.params.get('page') === '2')
      .flush({items: [{tmdbId: 3}], page: 2, hasNext: false});
    fixture.destroy();
    const restored = TestBed.createComponent(CatalogPage).componentInstance;
    flushLibrary();
    expect(restored.lastSubmittedQuery()).toBe('Matrix');
    expect(restored.currentPage()).toBe(2);
    expect(restored.movies().map(movie => movie.tmdbId)).toEqual([2, 3]);
    http.expectNone(r => r.url === '/api/catalog/search');
  });
});
