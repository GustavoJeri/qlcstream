import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, Subject, Subscription, debounceTime } from 'rxjs';

import { CatalogApiService, CatalogCollection, CatalogPageResponse, CatalogSeries, CatalogSort } from '../core/catalog-api.service';
import { tmdbImage } from '../core/tmdb-image';
import { LibraryApiService } from '../core/library-api.service';

type SeriesFilter = CatalogCollection | 'TRENDING';
type Sort = CatalogSort;

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-series-page',
  imports: [RouterLink],
  styleUrl: './series.page.scss',
  template: `
    @if (spotlightSeries(); as series) {
      <section class="spotlight" aria-labelledby="spotlight-title">
        @if (series.backdropPath) {
          <img
            [src]="backdropUrl(series)"
            [alt]="'Cena de ' + series.name"
            fetchpriority="high"
            decoding="async"
          />
        }
        <div class="spotlight-shade"></div>
        <div class="spotlight-content">
          <p class="spotlight-kicker">Em destaque</p>
          <h1 id="spotlight-title">{{ series.name }}</h1>
          <p class="movie-meta">
            {{ year(series) }} <span><i class="ph-fill ph-star"></i>{{ rating(series) }}</span>
          </p>
          @if (series.overview) {
            <p class="spotlight-copy">{{ series.overview }}</p>
          }
          <div class="spotlight-actions">
            <a class="btn btn-primary" href="#series-search"
              ><i class="ph ph-magnifying-glass"></i>Explorar séries</a
            ><a class="btn btn-secondary" [routerLink]="['/series', series.tmdbId]"
              ><i class="ph ph-info"></i>Detalhes</a
            >
          </div>
          @if (spotlightItems().length > 1) {
            <div class="spotlight-carousel" aria-label="Outras séries em destaque">
              <button
                type="button"
                class="spotlight-arrow"
                (click)="previousSpotlight()"
                aria-label="Série anterior"
              >
                <i class="ph ph-arrow-left"></i>
              </button>
              <div class="spotlight-dots">
                @for (item of spotlightItems(); track item.tmdbId; let index = $index) {
                  <button
                    type="button"
                    class="spotlight-dot"
                    [class.active]="index === activeSpotlightIndex()"
                    [attr.aria-label]="'Mostrar ' + item.name"
                    [attr.aria-current]="index === activeSpotlightIndex() ? 'true' : null"
                    (click)="selectSpotlight(index)"
                  >
                    <span></span>
                  </button>
                }
              </div>
              <button
                type="button"
                class="spotlight-arrow"
                (click)="nextSpotlight()"
                aria-label="Próxima série"
              >
                <i class="ph ph-arrow-right"></i>
              </button>
            </div>
          }
        </div>
      </section>
    }
    <div class="page catalog-content">
      <section class="discovery" aria-labelledby="discovery-title">
        <div class="discovery-copy">
          <h2 id="discovery-title">O que você quer acompanhar?</h2>
          <p>Pesquise o catálogo ou navegue pelas séries em destaque.</p>
        </div>
        <label class="catalog-search"
          ><span class="sr-only">Título da série</span
          ><i class="ph ph-magnifying-glass" aria-hidden="true"></i
          ><input
            id="series-search"
            type="search"
            placeholder="Título da série"
            [value]="query()"
            (input)="onQuery($event)"
          /><button type="button" (click)="search()">Buscar</button></label
        >
      </section>
      <div class="filters" aria-label="Filtros rápidos">
        @for (filter of filters; track filter.id) {
          <button
            type="button"
            [class.active]="filter.id === activeFilter()"
            (click)="selectFilter(filter.id)"
          >
            {{ filter.label }}
          </button>
        }
      </div>
      <section aria-labelledby="series-title">
        <div class="section-heading">
          <h2 id="series-title">{{ sectionTitle() }}</h2>
          <div class="catalog-tools">
            <span class="catalog-count">{{ displayedSeries().length }} séries</span>
            @if (!lastSubmittedQuery() && activeFilter() !== 'TRENDING') {
            <label class="catalog-sort"
              ><span>Ordenar</span
              ><select [value]="sortBy()" (change)="setSort($event)">
                <option value="POPULARITY">Popularidade</option>
                <option value="RATING">Melhor nota</option>
                <option value="NEWEST">Estreias recentes</option>
                <option value="OLDEST">Mais antigas</option>
                <option value="TITLE">Título A-Z</option>
              </select></label>
            }
          </div>
        </div>
        @switch (viewState()) {
          @case ('loading') {
            <div class="poster-grid" aria-label="Carregando séries">
              @for (item of skeletons; track item) {
                <div class="poster-skeleton"><span></span><i></i><i></i></div>
              }
            </div>
          }
          @case ('error') {
            <div class="state-message error-state">
              <i class="ph ph-warning-circle"></i>
              <div>
                <strong>Não foi possível carregar as séries.</strong>
                <p>{{ errorMessage() }}</p>
              </div>
              <button class="btn btn-quiet" type="button" (click)="retry()">
                Tentar novamente
              </button>
            </div>
          }
          @default {
            @if (displayedSeries().length) {
              <div class="poster-grid">
                @for (series of displayedSeries(); track series.tmdbId; let index = $index) {
                  <article class="movie-card" [style.--delay]="index * 45 + 'ms'">
                    <a
                      class="movie-link"
                      [routerLink]="['/series', series.tmdbId]"
                      [attr.aria-label]="'Abrir detalhes de ' + series.name"
                      ><div class="poster">
                        @if (series.posterPath && !unavailablePosterIds().has(series.tmdbId)) {
                          <img
                            [src]="posterUrl(series)"
                            [alt]="'Pôster de ' + series.name"
                            (error)="hidePoster(series)"
                          />
                        } @else {
                          <span class="poster-placeholder"><i class="ph ph-television"></i></span>
                        }
                      </div>
                      <div class="movie-title-row">
                        <h3>
                          {{ series.name }}
                          @if (series.originalName && series.originalName !== series.name) {
                            <small class="original-title">({{ series.originalName }})</small>
                          }
                        </h3>
                        <span><i class="ph-fill ph-star"></i>{{ rating(series) }}</span>
                      </div>
                      <p>{{ year(series) }}</p></a
                    ><button
                      class="poster-favorite"
                      type="button"
                      [class.active]="favoriteSeriesIds().has(series.tmdbId)"
                      (click)="toggleFavorite(series)"
                    >
                      <i
                        class="ph"
                        [class.ph-heart-fill]="favoriteSeriesIds().has(series.tmdbId)"
                        [class.ph-heart]="!favoriteSeriesIds().has(series.tmdbId)"
                      ></i></button
                    ><button
                      class="poster-add"
                      type="button"
                      [disabled]="
                        addingSeriesIds().has(series.tmdbId) || addedSeriesIds().has(series.tmdbId)
                      "
                      [attr.aria-label]="
                        addedSeriesIds().has(series.tmdbId)
                          ? series.name + ' já está na biblioteca'
                          : 'Adicionar ' + series.name + ' à biblioteca'
                      "
                      (click)="addToLibrary(series)"
                    >
                      <i
                        class="ph"
                        [class.ph-plus]="!addedSeriesIds().has(series.tmdbId)"
                        [class.ph-check]="addedSeriesIds().has(series.tmdbId)"
                      ></i>
                    </button>
                  </article>
                }
              </div>
              @if (hasMoreSeries()) {
                <div class="load-more-actions">
                  <button class="btn btn-quiet" type="button" [disabled]="loadingMoreSeries()" (click)="loadMore()">
                    {{ loadingMoreSeries() ? 'Carregando séries…' : 'Carregar mais séries' }}
                  </button>
                </div>
              }
            } @else {
              <div class="state-message empty-state">
                <i class="ph ph-television"></i>
                <div>
                  <strong>Nenhuma série encontrada.</strong>
                  <p>Tente outro título ou limpe a busca.</p>
                </div>
                <button class="btn btn-quiet" type="button" (click)="clearSearch()">
                  Limpar busca
                </button>
              </div>
            }
          }
        }
      </section>
    </div>
  `,
})
export class SeriesPage {
  private readonly api = inject(CatalogApiService);
  private readonly libraryApi = inject(LibraryApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly queryChanges = new Subject<string>();
  private activeRequest?: Subscription;
  private spotlightTimer?: ReturnType<typeof setInterval>;
  readonly filters: ReadonlyArray<{ id: SeriesFilter; label: string }> = [
    { id: 'TRENDING', label: 'Em alta' },
    { id: 'POPULAR', label: 'Populares' },
    { id: 'TOP_RATED', label: 'Bem avaliadas' },
    { id: 'RECENT', label: 'Estreias' },
  ];
  readonly spotlightCycle = signal(0);
  readonly skeletons = [1, 2, 3, 4];
  readonly activeFilter = signal<SeriesFilter>('TRENDING');
  readonly query = signal('');
  readonly lastSubmittedQuery = signal('');
  readonly viewState = signal<'ready' | 'loading' | 'error'>('loading');
  readonly errorMessage = signal('Confira a conexão com o TMDB e tente novamente.');
  readonly series = signal<CatalogSeries[]>([]);
  readonly unavailablePosterIds = signal<ReadonlySet<number>>(new Set());
  readonly addingSeriesIds = signal<ReadonlySet<number>>(new Set());
  readonly addedSeriesIds = signal<ReadonlySet<number>>(new Set());
  readonly favoriteSeriesIds = signal<ReadonlySet<number>>(new Set());
  readonly currentPage = signal(1);
  readonly loadingMoreSeries = signal(false);
  readonly hasMoreSeries = signal(true);
  readonly sortBy = signal<Sort>('POPULARITY');
  readonly spotlightIndex = signal(0);
  readonly sectionTitle = computed(() =>
    this.lastSubmittedQuery()
      ? `Resultados para “${this.lastSubmittedQuery()}”`
      : (this.filters.find((filter) => filter.id === this.activeFilter())?.label ?? 'Séries'),
  );
  readonly spotlightItems = computed(() =>
    this.series()
      .filter((item) => Boolean(item.backdropPath))
      .slice(0, 5),
  );
  readonly activeSpotlightIndex = computed(() => {
    const total = this.spotlightItems().length;
    return total ? this.spotlightIndex() % total : 0;
  });
  readonly spotlightSeries = computed(
    () => this.spotlightItems()[this.activeSpotlightIndex()] ?? this.series()[0],
  );
  readonly displayedSeries = computed(() => this.series());
  constructor() {
    if (
      typeof window !== 'undefined' &&
      !window.matchMedia('(prefers-reduced-motion: reduce)').matches
    ) {
      this.spotlightTimer = setInterval(() => this.nextSpotlight(), 7000);
      this.destroyRef.onDestroy(() => clearInterval(this.spotlightTimer));
    }
    this.queryChanges
      .pipe(debounceTime(260), takeUntilDestroyed(this.destroyRef))
      .subscribe((query) => {
        if (query !== this.query().replace(/\s+/g, ' ').trim()) return;
        const normalized = query.length >= 2 ? query : '';
        if (normalized === this.lastSubmittedQuery()) return;
        this.lastSubmittedQuery.set(normalized);
        this.retry();
      });
    this.retry();
  }
  onQuery(event: Event): void {
    const query = (event.target as HTMLInputElement).value;
    this.query.set(query);
    this.queryChanges.next(query.replace(/\s+/g, ' ').trim());
  }
  search(): void {
    const query = this.query().replace(/\s+/g, ' ').trim();
    this.lastSubmittedQuery.set(query.length >= 2 ? query : '');
    this.retry();
  }
  retry(): void {
    this.load(this.requestPage(1));
  }
  selectFilter(filter: SeriesFilter): void {
    if (filter === this.activeFilter() && !this.lastSubmittedQuery()) return;
    this.activeFilter.set(filter);
    this.sortBy.set(filter === 'TOP_RATED' ? 'RATING' : filter === 'RECENT' ? 'NEWEST' : 'POPULARITY');
    this.clearSearch();
  }
  clearSearch(): void {
    this.query.set('');
    this.queryChanges.next('');
    this.lastSubmittedQuery.set('');
    this.retry();
  }
  posterUrl(item: CatalogSeries): string {
    return tmdbImage(item.posterPath, 'poster-card');
  }
  backdropUrl(item: CatalogSeries): string {
    return tmdbImage(item.backdropPath, 'backdrop-hero');
  }
  year(item: CatalogSeries): string {
    return item.firstAirDate?.slice(0, 4) ?? 'Sem data';
  }
  rating(item: CatalogSeries): string {
    return item.voteAverage?.toFixed(1) ?? 'N/D';
  }
  hidePoster(item: CatalogSeries): void {
    this.unavailablePosterIds.update((ids) => new Set(ids).add(item.tmdbId));
  }
  setSort(event: Event): void {
    this.sortBy.set((event.target as HTMLSelectElement).value as Sort);
    this.retry();
  }
  loadMore(): void {
    if (this.viewState() !== 'ready' || this.loadingMoreSeries() || !this.hasMoreSeries()) return;
    const nextPage = this.currentPage() + 1;
    this.loadingMoreSeries.set(true);
    this.activeRequest = this.requestPage(nextPage).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (page) => {
        this.series.update((current) => this.uniqueSeries([...current, ...page.items]));
        this.currentPage.set(page.page);
        this.hasMoreSeries.set(page.hasNext);
        this.loadingMoreSeries.set(false);
      },
      error: () => {
        this.loadingMoreSeries.set(false);
      },
    });
  }
  private resetSpotlightTimer(): void {
    if (
      typeof window === 'undefined' ||
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    )
      return;
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
  addToLibrary(series: CatalogSeries): void {
    if (this.addingSeriesIds().has(series.tmdbId) || this.addedSeriesIds().has(series.tmdbId))
      return;
    this.addingSeriesIds.update((ids) => new Set(ids).add(series.tmdbId));
    this.libraryApi
      .addPlannedSeries({
        tmdbId: series.tmdbId,
        name: series.name,
        originalName: series.originalName,
        posterPath: series.posterPath,
        firstAirDate: series.firstAirDate,
        voteAverage: series.voteAverage,
        addedAt: '',
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.addingSeriesIds.update((ids) => {
            const next = new Set(ids);
            next.delete(series.tmdbId);
            return next;
          });
          this.addedSeriesIds.update((ids) => new Set(ids).add(series.tmdbId));
        },
        error: () =>
          this.addingSeriesIds.update((ids) => {
            const next = new Set(ids);
            next.delete(series.tmdbId);
            return next;
          }),
      });
  }
  toggleFavorite(series: CatalogSeries): void {
    const favorite = this.favoriteSeriesIds().has(series.tmdbId);
    const request = favorite
      ? this.libraryApi.removeFavorite('SERIES', series.tmdbId)
      : this.libraryApi.addFavorite('SERIES', series.tmdbId, {
          title: series.name,
          posterPath: series.posterPath,
          subtitle: this.year(series),
        });
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () =>
        this.favoriteSeriesIds.update((ids) => {
          const next = new Set(ids);
          favorite ? next.delete(series.tmdbId) : next.add(series.tmdbId);
          return next;
        }),
    });
  }
  private requestPage(page: number): Observable<CatalogPageResponse<CatalogSeries>> {
    const query = this.lastSubmittedQuery();
    const filter = this.activeFilter();
    if (query) return this.api.searchSeries(query, 'pt-BR', page);
    if (filter === 'TRENDING') return this.api.trendingSeries('pt-BR', page);
    return this.api.discoverSeries(filter, 'pt-BR', page, this.sortBy());
  }
  private load(request: Observable<CatalogPageResponse<CatalogSeries>>): void {
    this.activeRequest?.unsubscribe();
    this.viewState.set('loading');
    this.loadingMoreSeries.set(false);
    this.unavailablePosterIds.set(new Set());
    this.activeRequest = request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (page) => {
        this.series.set(this.uniqueSeries(page.items));
        this.spotlightIndex.set(0);
        this.currentPage.set(page.page);
        this.hasMoreSeries.set(page.hasNext);
        this.loadingMoreSeries.set(false);
        this.viewState.set('ready');
        this.libraryApi
          .plannedSeries()
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: (planned) => this.addedSeriesIds.set(new Set(planned.map((item) => item.tmdbId))),
          });
        this.libraryApi
          .favorites()
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: (items) =>
              this.favoriteSeriesIds.set(
                new Set(
                  items.filter((item) => item.mediaType === 'SERIES').map((item) => item.tmdbId),
                ),
              ),
          });
      },
      error: () => {
        this.series.set([]);
        this.errorMessage.set(
          'O catálogo não respondeu como esperado. Tente novamente em instantes.',
        );
        this.viewState.set('error');
      },
    });
  }

  private uniqueSeries(values: CatalogSeries[]): CatalogSeries[] {
    const seen = new Set<number>();
    return values.filter((item) => !seen.has(item.tmdbId) && seen.add(item.tmdbId));
  }
}
