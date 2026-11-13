import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { CatalogApiService, CatalogMovie } from './catalog-api.service';

describe('CatalogApiService', () => {
  let service: CatalogApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CatalogApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests the weekly catalog in Brazilian Portuguese', () => {
    const movies: CatalogMovie[] = [{
      id: 1,
      tmdbId: 42,
      title: 'Filme de teste',
      originalTitle: 'Test Movie',
      releaseDate: '2026-04-03',
      overview: 'Uma cidade desaparece do mapa.',
      posterPath: '/poster.jpg',
      backdropPath: '/backdrop.jpg',
      voteAverage: 8.1,
      popularity: null,
    }];

    const page = { items: movies, page: 1, totalPages: 3, totalResults: 42, hasNext: true };
    service.trending().subscribe((result) => expect(result).toEqual(page));

    const request = http.expectOne((candidate) => candidate.url === '/api/catalog/trending');
    expect(request.request.params.get('language')).toBe('pt-BR');
    expect(request.request.params.get('page')).toBe('1');
    expect(request.request.params.has('sort')).toBe(false);
    request.flush(page);
  });

  it('requests a curated discovery collection', () => {
    service.discover('ESTABLISHED').subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/catalog/discover');
    expect(request.request.params.get('collection')).toBe('ESTABLISHED');
    expect(request.request.params.get('language')).toBe('pt-BR');
    request.flush({ items: [], page: 1, totalPages: 1, totalResults: 0, hasNext: false });
  });

  it('sends the submitted title to catalog search', () => {
    service.search('Matrix').subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/catalog/search');
    expect(request.request.params.get('query')).toBe('Matrix');
    request.flush({ items: [], page: 1, totalPages: 1, totalResults: 0, hasNext: false });
  });

  it('requests the details for a selected TMDB movie', () => {
    service.details(42).subscribe();

    const request = http.expectOne('/api/catalog/42?language=pt-BR');
    request.flush({
      id: 1, tmdbId: 42, title: 'Filme de teste', originalTitle: 'Test Movie', releaseDate: '2026-04-03',
      overview: 'Sinopse de teste.', posterPath: '/poster.jpg', backdropPath: '/backdrop.jpg', voteAverage: 8.1,
      tagline: 'Uma frase de teste.', runtimeMinutes: 120, genres: ['Drama'],
    });
  });
});
