import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, Subject, Subscription, catchError, debounceTime, forkJoin, map, of, switchMap } from 'rxjs';

import { CatalogApiService, CatalogCollection, CatalogMovie, CatalogPageResponse, CatalogSort, PersonalizedRecommendation } from '../core/catalog-api.service';
import { tmdbImage, tmdbPosterSrcset } from '../core/tmdb-image';
import { LibraryApiService } from '../core/library-api.service';
import { CatalogNavigationSnapshot, CatalogNavigationStateService } from '../core/catalog-navigation-state.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-catalog-page',
  imports: [RouterLink],
  styleUrl: './catalog.page.scss',
  template: `
    @if (spotlightMovie(); as movie) {
      <section class="spotlight" aria-labelledby="spotlight-title">
        @if (movie.backdropPath) { <img [src]="backdropUrl(movie)" [alt]="'Cena de ' + movie.title" fetchpriority="high" decoding="async" /> }
        <div class="spotlight-shade"></div>
        <div class="spotlight-content">
          <p class="spotlight-kicker">Em destaque</p>
          <h1 id="spotlight-title">{{ movie.title }}</h1>
          <p class="movie-meta">{{ year(movie) }} <span><i class="ph-fill ph-star"></i>{{ rating(movie) }}</span></p>
          @if (movie.overview) { <p class="spotlight-copy">{{ movie.overview }}</p> }
          <div class="spotlight-actions">
            <a class="btn btn-primary" href="#catalog-search"><i class="ph ph-magnifying-glass"></i>Explorar catálogo</a>
            <a class="btn btn-secondary" [routerLink]="['/catalog', movie.tmdbId]"><i class="ph ph-info"></i>Detalhes</a>
          </div>
          @if (spotlightItems().length > 1) {
            <div class="spotlight-carousel" aria-label="Outros filmes em destaque">
              <button type="button" class="spotlight-arrow" (click)="previousSpotlight()" aria-label="Filme anterior"><i class="ph ph-arrow-left"></i></button>
              <div class="spotlight-dots">
                @for (item of spotlightItems(); track item.tmdbId + ':' + spotlightCycle(); let index = $index) {
                  <button type="button" class="spotlight-dot" [class.active]="index === activeSpotlightIndex()" [attr.aria-label]="'Mostrar ' + item.title" [attr.aria-current]="index === activeSpotlightIndex() ? 'true' : null" (click)="selectSpotlight(index)"><span></span></button>
                }
              </div>
              <button type="button" class="spotlight-arrow" (click)="nextSpotlight()" aria-label="Próximo filme"><i class="ph ph-arrow-right"></i></button>
            </div>
          }
        </div>
      </section>
    }

    @if (recommendedForYou().length) {
        <section class="personalized-recommendations" aria-labelledby="recommendations-title">
          <div class="recommendations-heading">
            <div><p>DA SUA BIBLIOTECA</p><h2 id="recommendations-title">Recomendados para você</h2></div>
            <div class="recommendations-actions"><span>Baseado nos seus filmes e séries</span><button type="button" (click)="moreRecommendations()"><i class="ph ph-arrows-clockwise"></i>{{ refreshingRecommendations() ? 'Atualizando…' : 'Mais recomendações' }}</button></div>
          </div>
          <div class="recommendations-rail">
            @for (item of recommendedForYou(); track item.mediaType + ':' + item.tmdbId) {
              <a class="recommendation-card" [routerLink]="[item.mediaType === 'SERIES' ? '/series' : '/catalog', item.tmdbId]">
                <div class="recommendation-poster">@if (item.posterPath) { <img [src]="posterUrl(item)" [attr.srcset]="posterSrcset(item)" sizes="150px" [alt]="'Pôster de ' + item.title" loading="lazy" decoding="async" /> } @else { <i class="ph" [class.ph-film-strip]="item.mediaType === 'MOVIE'" [class.ph-television]="item.mediaType === 'SERIES'"></i> }</div>
                <small>{{ item.mediaType === 'SERIES' ? 'SÉRIE' : 'FILME' }}</small><strong>{{ item.title }}</strong><span>{{ year(item) }} · <i class="ph-fill ph-star"></i>{{ rating(item) }}</span>
              </a>
            }
          </div>
        </section>
      }

    <div class="page catalog-content">
      <section class="discovery" aria-labelledby="discovery-title">
        <div class="discovery-copy">
          <h2 id="discovery-title">O que você quer encontrar?</h2>
          <p>Pesquise o catálogo ou navegue pelos filmes em destaque.</p>
        </div>
        <label class="catalog-search">
          <span class="sr-only">Título do filme</span>
          <i class="ph ph-magnifying-glass" aria-hidden="true"></i>
          <input id="catalog-search" type="search" placeholder="Título do filme" [value]="query()" (input)="onQuery($event)" (keyup.enter)="search()" />
          <button type="button" (click)="search()">Buscar</button>
        </label>
      </section>

      <div class="filters" aria-label="Filtros rápidos">
        @for (filter of filters; track filter.collection) {
          <button type="button" [class.active]="filter.collection === activeFilter()" (click)="selectFilter(filter.collection)">{{ filter.label }}</button>
        }
      </div>

      <section aria-labelledby="popular-title">
        <div class="section-heading">
          <h2 id="popular-title">{{ sectionTitle() }}</h2>
          <div class="catalog-tools"><span class="catalog-count">{{ displayedMovies().length }} filmes</span>@if (!lastSubmittedQuery()) { <label class="catalog-sort"><span>Ordenar</span><select [value]="sortBy()" (change)="setSort($event)"><option value="POPULARITY">Popularidade</option><option value="RATING">Melhor nota</option><option value="NEWEST">Lançamentos recentes</option><option value="OLDEST">Ano mais antigo</option><option value="TITLE">Título A-Z</option></select></label> }</div>
        </div>

        @switch (viewState()) {
          @case ('loading') {
            <div class="poster-grid" aria-label="Carregando filmes">
              @for (item of skeletons; track item) { <div class="poster-skeleton"><span></span><i></i><i></i></div> }
            </div>
          }
          @case ('error') {
            <div class="state-message error-state">
              <i class="ph ph-warning-circle"></i><div><strong>Não foi possível carregar o catálogo.</strong><p>{{ errorMessage() }}</p></div>
              <div class="state-actions">
                @if (configurationMissing()) { <a class="btn btn-quiet" routerLink="/settings">Configurações</a> }
                <button class="btn btn-quiet" type="button" (click)="retry()">Tentar novamente</button>
              </div>
            </div>
          }
          @default {
            @if (displayedMovies().length) {
              <div class="poster-grid">
                @for (movie of displayedMovies(); track movie.tmdbId; let index = $index) {
                  <article class="movie-card" [style.--delay]="index * 45 + 'ms'">
                    <a class="movie-link" [routerLink]="['/catalog', movie.tmdbId]" [attr.aria-label]="'Abrir detalhes de ' + movie.title">
                      <div class="poster">
                        @if (movie.posterPath && !unavailablePosterIds().has(movie.tmdbId)) {
                          <img [src]="posterUrl(movie)" [attr.srcset]="posterSrcset(movie)" sizes="(max-width: 700px) 44vw, 210px" [alt]="'Pôster de ' + movie.title" loading="lazy" decoding="async" (error)="hidePoster(movie)" />
                        } @else { <span class="poster-placeholder"><i class="ph ph-film-strip"></i></span> }
                      </div>
                      <div class="movie-title-row"><h3>{{ movie.title }}@if (movie.originalTitle && movie.originalTitle !== movie.title) { <small class="original-title">({{ movie.originalTitle }})</small> }</h3><span><i class="ph-fill ph-star"></i>{{ rating(movie) }}</span></div>
                      <p>{{ year(movie) }}</p>
                    </a>
                    <button class="poster-favorite" type="button" [class.active]="favoriteMovieIds().has(movie.tmdbId)" [attr.aria-label]="favoriteMovieIds().has(movie.tmdbId) ? 'Remover ' + movie.title + ' dos favoritos' : 'Favoritar ' + movie.title" (click)="toggleFavorite(movie)"><i class="ph" [class.ph-heart-fill]="favoriteMovieIds().has(movie.tmdbId)" [class.ph-heart]="!favoriteMovieIds().has(movie.tmdbId)"></i></button><button class="poster-add" type="button" [disabled]="addingMovieIds().has(movie.tmdbId) || addedMovieIds().has(movie.tmdbId)" [attr.aria-label]="addedMovieIds().has(movie.tmdbId) ? movie.title + ' já está na biblioteca' : 'Adicionar ' + movie.title + ' à biblioteca'" (click)="addToLibrary(movie)"><i class="ph" [class.ph-plus]="!addedMovieIds().has(movie.tmdbId)" [class.ph-check]="addedMovieIds().has(movie.tmdbId)"></i></button>
                  </article>
                }
              </div>
              @if (loadingMoreMovies()) {
                <div class="poster-grid poster-grid-loading" aria-label="Carregando mais filmes">
                  @for (item of moreSkeletons; track item) { <div class="poster-skeleton"><span></span><i></i><i></i></div> }
                </div>
              }
              @if (hasMoreMovies()) {
                <div class="load-more-actions">
                  <button class="btn btn-quiet" type="button" [disabled]="loadingMoreMovies()" (click)="loadMore()">
                    {{ loadingMoreMovies() ? 'Carregando mais filmes…' : 'Carregar mais filmes' }}
                  </button>
                </div>
              }
            } @else {
              <div class="state-message empty-state">
                <i class="ph ph-film-strip"></i><div><strong>Nenhum filme encontrado.</strong><p>Tente outro título ou limpe a busca.</p></div>
                <button class="btn btn-quiet" type="button" (click)="clearSearch()">Limpar busca</button>
              </div>
            }
          }
        }
      </section>
    </div>
  `,
})
export class CatalogPage {
  private readonly catalogApi = inject(CatalogApiService);
  private readonly libraryApi = inject(LibraryApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly navigationState = inject(CatalogNavigationStateService);
  private activeRequest?: Subscription;
  private spotlightTimer?: ReturnType<typeof setInterval>;
  private readonly queryChanges = new Subject<string>();

  readonly filters: ReadonlyArray<{ collection: CatalogCollection; label: string }> = [
    { collection: 'ESTABLISHED', label: 'Catálogo consolidado' },
    { collection: 'POPULAR', label: 'Populares' },
    { collection: 'TOP_RATED', label: 'Bem avaliados' },
    { collection: 'RECENT', label: 'Lançamentos' },
  ];
  readonly skeletons = [1, 2, 3, 4];
  readonly moreSkeletons = [1, 2, 3, 4, 5];
  readonly activeFilter = signal<CatalogCollection>('ESTABLISHED');
  readonly query = signal('');
  readonly viewState = signal<'ready' | 'loading' | 'error'>('loading');
  readonly errorMessage = signal('Confira a conexão com o backend e tente novamente.');
  readonly configurationMissing = signal(false);
  readonly lastSubmittedQuery = signal('');
  readonly movies = signal<CatalogMovie[]>([]);
  readonly unavailablePosterIds = signal<ReadonlySet<number>>(new Set());
  readonly addingMovieIds = signal<ReadonlySet<number>>(new Set());
  readonly addedMovieIds = signal<ReadonlySet<number>>(new Set());
  readonly favoriteMovieIds = signal<ReadonlySet<number>>(new Set());
  readonly currentPage = signal(1);
  readonly loadingMoreMovies = signal(false);
  readonly hasMoreMovies = signal(true);
  readonly recommendedForYou = signal<PersonalizedRecommendation[]>([]);
  readonly recommendationPage = signal(0);
  readonly refreshingRecommendations = signal(false);
  readonly sortBy = signal<CatalogSort>('POPULARITY');
  readonly spotlightIndex = signal(0);
  readonly spotlightCycle = signal(0);
  readonly sectionTitle = computed(() => {
    if (this.lastSubmittedQuery()) {
      return `Resultados para “${this.lastSubmittedQuery()}”`;
    }
    return this.filters.find((filter) => filter.collection === this.activeFilter())?.label ?? 'Catálogo';
  });
  readonly spotlightItems = computed(() => this.movies().filter((movie) => Boolean(movie.backdropPath)).slice(0, 5));
  readonly activeSpotlightIndex = computed(() => {
    const total = this.spotlightItems().length;
    return total ? this.spotlightIndex() % total : 0;
  });
  readonly spotlightMovie = computed(() => this.spotlightItems()[this.activeSpotlightIndex()] ?? this.movies()[0]);
  readonly displayedMovies = computed(() => this.movies());

  constructor() {
    if (typeof window !== 'undefined' && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      this.resetSpotlightTimer();
      this.destroyRef.onDestroy(() => clearInterval(this.spotlightTimer));
    }
    this.queryChanges.pipe(
      debounceTime(260),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((query) => {
      if (query !== this.query().replace(/\s+/g, ' ').trim()) return;
      const normalized = query.length >= 2 ? query : '';
      if (normalized === this.lastSubmittedQuery()) return;
      this.lastSubmittedQuery.set(normalized);
      this.load(normalized.length >= 2 ? this.catalogApi.search(normalized) : this.catalogApi.discover(this.activeFilter(), 'pt-BR', 1, this.sortBy()));
    });
    this.destroyRef.onDestroy(() => this.saveNavigationState());
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const input = params.get('q')?.replace(/\s+/g, ' ').trim() ?? '';
      const query = input.length >= 2 ? input : '';
      const restored = this.navigationState.restoreFor(query);
      if (restored) {
        this.restoreNavigationState(restored);
        return;
      }
      this.query.set(query);
      if (query) {
        this.lastSubmittedQuery.set(query);
        this.load(this.catalogApi.search(query));
      } else {
        this.loadTrending();
      }
    });
  }

  onQuery(event: Event): void {
    const query = (event.target as HTMLInputElement).value;
    this.query.set(query);
    this.queryChanges.next(query.replace(/\s+/g, ' ').trim());
  }

  search(): void {
    const input = this.query().replace(/\s+/g, ' ').trim();
    const query = input.length >= 2 ? input : '';
    if (query === this.lastSubmittedQuery()) {
      this.retry();
      return;
    }
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { q: query || null },
      queryParamsHandling: 'merge',
    });
  }

  retry(): void {
    const query = this.lastSubmittedQuery();
    this.load(query ? this.catalogApi.search(query) : this.catalogApi.discover(this.activeFilter(), 'pt-BR', 1, this.sortBy()));
  }

  selectFilter(collection: CatalogCollection): void {
    if (collection === this.activeFilter() && !this.query() && !this.lastSubmittedQuery()) return;
    this.activeFilter.set(collection);
    this.sortBy.set(collection === 'TOP_RATED' ? 'RATING' : collection === 'RECENT' ? 'NEWEST' : 'POPULARITY');
    if (this.query() || this.lastSubmittedQuery()) this.clearSearch();
    else this.loadCollection();
  }

  clearSearch(): void {
    this.query.set('');
    this.queryChanges.next('');
    if (!this.route.snapshot.queryParamMap.has('q')) {
      this.loadTrending();
      return;
    }
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { q: null },
      queryParamsHandling: 'merge',
    });
  }

  posterUrl(movie: CatalogMovie | PersonalizedRecommendation): string {
    return tmdbImage(movie.posterPath, 'poster-card');
  }

  posterSrcset(movie: CatalogMovie | PersonalizedRecommendation): string { return tmdbPosterSrcset(movie.posterPath); }

  backdropUrl(movie: CatalogMovie): string {
    return tmdbImage(movie.backdropPath, 'backdrop-hero');
  }

  private resetSpotlightTimer(): void {
    if (typeof window === 'undefined' || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    if (this.spotlightTimer) clearInterval(this.spotlightTimer);
    this.spotlightCycle.update((cycle) => cycle + 1);
    this.spotlightTimer = setInterval(() => this.nextSpotlight(), 7000);
  }

  previousSpotlight(): void {
    const total = this.spotlightItems().length;
    if (total) {
      this.spotlightIndex.update((index) => (index - 1 + total) % total);
      this.resetSpotlightTimer();
    }
  }

  nextSpotlight(): void {
    const total = this.spotlightItems().length;
    if (total) {
      this.spotlightIndex.update((index) => (index + 1) % total);
      this.resetSpotlightTimer();
    }
  }

  selectSpotlight(index: number): void {
    this.spotlightIndex.set(index);
    this.resetSpotlightTimer();
  }

  loadMore(): void {
    if (this.viewState() !== 'ready' || this.loadingMoreMovies() || !this.hasMoreMovies()) return;
    const nextPage = this.currentPage() + 1;
    const query = this.lastSubmittedQuery();
    const request = query
      ? this.catalogApi.search(query, 'pt-BR', nextPage)
      : this.catalogApi.discover(this.activeFilter(), 'pt-BR', nextPage, this.sortBy());
    this.loadingMoreMovies.set(true);
    this.activeRequest = request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (page) => {
        this.movies.update((current) => this.uniqueMovies([...current, ...page.items]));
        this.currentPage.set(page.page);
        this.hasMoreMovies.set(page.hasNext);
        this.loadingMoreMovies.set(false);
      },
      error: () => {
        this.loadingMoreMovies.set(false);
      },
    });
  }

  hidePoster(movie: CatalogMovie): void {
    this.unavailablePosterIds.update((ids) => new Set(ids).add(movie.tmdbId));
  }

  year(movie: Pick<CatalogMovie, 'releaseDate'>): number | string {
    return movie.releaseDate ? Number(movie.releaseDate.slice(0, 4)) : 'Sem data';
  }

  rating(movie: Pick<CatalogMovie, 'voteAverage'>): string {
    return movie.voteAverage == null ? 'N/D' : movie.voteAverage.toFixed(1);
  }

  setSort(event: Event): void {
    this.sortBy.set((event.target as HTMLSelectElement).value as CatalogSort);
    this.retry();
  }

  addToLibrary(movie: CatalogMovie): void {
    if (this.addingMovieIds().has(movie.tmdbId) || this.addedMovieIds().has(movie.tmdbId)) return;
    this.addingMovieIds.update((ids) => new Set(ids).add(movie.tmdbId));
    this.libraryApi.addPlanned(movie.tmdbId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.addingMovieIds.update((ids) => { const next = new Set(ids); next.delete(movie.tmdbId); return next; });
        this.addedMovieIds.update((ids) => new Set(ids).add(movie.tmdbId));
      },
      error: () => this.addingMovieIds.update((ids) => { const next = new Set(ids); next.delete(movie.tmdbId); return next; }),
    });
  }

  toggleFavorite(movie: CatalogMovie): void {
    const favorite = this.favoriteMovieIds().has(movie.tmdbId);
    const request = favorite ? this.libraryApi.removeFavorite('MOVIE', movie.tmdbId) : this.libraryApi.addFavorite('MOVIE', movie.tmdbId, { title: movie.title, posterPath: movie.posterPath, subtitle: this.year(movie).toString() });
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => this.favoriteMovieIds.update((ids) => { const next = new Set(ids); favorite ? next.delete(movie.tmdbId) : next.add(movie.tmdbId); return next; }) });
  }

  private loadTrending(): void {
    this.lastSubmittedQuery.set('');
    this.loadCollection();
  }

  private loadCollection(): void {
    this.load(this.catalogApi.discover(this.activeFilter(), 'pt-BR', 1, this.sortBy()));
  }

  private load(request: Observable<CatalogPageResponse<CatalogMovie>>): void {
    this.activeRequest?.unsubscribe();
    this.viewState.set('loading');
    this.loadingMoreMovies.set(false);
    this.configurationMissing.set(false);
    this.unavailablePosterIds.set(new Set());
    this.activeRequest = request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (page) => {
        this.movies.set(this.uniqueMovies(page.items));
        this.spotlightIndex.set(0);
        this.currentPage.set(page.page);
        this.hasMoreMovies.set(page.hasNext);
        this.loadingMoreMovies.set(false);
        this.resetSpotlightTimer();
        this.viewState.set('ready');
        this.libraryApi.movieIds().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: (ids) => this.addedMovieIds.set(new Set(ids)),
        });
        this.libraryApi.favorites().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (items) => this.favoriteMovieIds.set(new Set(items.filter((item) => item.mediaType === 'MOVIE').map((item) => item.tmdbId))) });
    this.loadPersonalizedRecommendations();
        this.loadPersonalizedRecommendations();
      },
      error: (error: unknown) => {
        this.movies.set([]);
        this.describeError(error);
        this.viewState.set('error');
      },
    });
  }

  moreRecommendations(): void {
    if (this.refreshingRecommendations()) return;
    this.recommendationPage.update((page) => page + 1);
    this.refreshingRecommendations.set(true);
    this.loadPersonalizedRecommendations();
  }

  private loadPersonalizedRecommendations(): void {
    forkJoin({
      movies: this.libraryApi.movieIds().pipe(catchError(() => of([]))),
      plannedSeries: this.libraryApi.plannedSeries().pipe(catchError(() => of([]))),
      downloadedSeries: this.libraryApi.browseSeries().pipe(catchError(() => of([]))),
      favorites: this.libraryApi.favorites().pipe(catchError(() => of([]))),
    }).pipe(
      map((library) => ({
        movieIds: [...new Set([...library.movies, ...library.favorites.filter((item) => item.mediaType === 'MOVIE').map((item) => item.tmdbId)])].slice(0, 3),
        seriesIds: [...new Set([...library.plannedSeries.map((item) => item.tmdbId), ...library.downloadedSeries.map((item) => item.seriesTmdbId), ...library.favorites.filter((item) => item.mediaType === 'SERIES').map((item) => item.tmdbId)])].slice(0, 3),
      })),
      switchMap((seed) => seed.movieIds.length || seed.seriesIds.length
        ? this.catalogApi.recommendations(seed.movieIds, seed.seriesIds, 'pt-BR', this.recommendationPage()).pipe(catchError(() => of([])))
        : of([])),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((items) => { this.recommendedForYou.set(items); this.refreshingRecommendations.set(false); });
  }

  private saveNavigationState(): void {
    if (typeof window === 'undefined' || this.viewState() !== 'ready') return;
    this.navigationState.save({
      query: this.query(),
      lastSubmittedQuery: this.lastSubmittedQuery(),
      activeFilter: this.activeFilter(),
      sortBy: this.sortBy(),
      movies: this.movies(),
      unavailablePosterIds: [...this.unavailablePosterIds()],
      currentPage: this.currentPage(),
      hasMoreMovies: this.hasMoreMovies(),
      scrollY: window.scrollY,
    });
  }

  private restoreNavigationState(snapshot: CatalogNavigationSnapshot): void {
    this.activeRequest?.unsubscribe();
    this.query.set(snapshot.query);
    this.lastSubmittedQuery.set(snapshot.lastSubmittedQuery);
    this.activeFilter.set(snapshot.activeFilter);
    this.sortBy.set(snapshot.sortBy);
    this.movies.set(snapshot.movies);
    this.unavailablePosterIds.set(new Set(snapshot.unavailablePosterIds));
    this.currentPage.set(snapshot.currentPage);
    this.hasMoreMovies.set(snapshot.hasMoreMovies);
    this.loadingMoreMovies.set(false);
    this.viewState.set('ready');
    this.restoreScroll(snapshot.scrollY);
    this.libraryApi.movieIds().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (ids) => this.addedMovieIds.set(new Set(ids)) });
    this.libraryApi.favorites().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (items) => this.favoriteMovieIds.set(new Set(items.filter((item) => item.mediaType === 'MOVIE').map((item) => item.tmdbId))) });
    this.loadPersonalizedRecommendations();
  }

  private restoreScroll(scrollY: number): void {
    if (typeof window === 'undefined') return;
    requestAnimationFrame(() => requestAnimationFrame(() => window.scrollTo({ top: scrollY, behavior: 'instant' })));
  }

  private uniqueMovies(values: CatalogMovie[]): CatalogMovie[] {
    const seen = new Set<number>();
    return values.filter((item) => !seen.has(item.tmdbId) && seen.add(item.tmdbId));
  }

  private describeError(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 503) {
      this.configurationMissing.set(true);
      this.errorMessage.set(typeof error.error?.detail === 'string'
        ? error.error.detail
        : 'Configure o token do TMDB para carregar o catálogo.');
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 0) {
      this.errorMessage.set('O backend local não está respondendo. Confira os serviços do Docker.');
      return;
    }
    this.errorMessage.set('O catálogo não respondeu como esperado. Tente novamente em instantes.');
  }
}
