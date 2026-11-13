import { HttpErrorResponse } from '@angular/common/http';
import { tmdbImage } from '../core/tmdb-image';
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
import { forkJoin, map } from 'rxjs';

import { CatalogApiService, CatalogMovie, CatalogSeries } from '../core/catalog-api.service';

import {
  FavoriteItem,
  LibraryApiService,
  LibraryCollection,
  LibraryItem,
  PlannedMovie,
  PlannedSeries,
  SeriesLibraryItem,
} from '../core/library-api.service';
import { DesktopFileService } from '../core/desktop-file.service';
import { isDesktopApp } from '../core/api-url';

interface CollectionSearchResult {
  mediaType: 'MOVIE' | 'SERIES';
  tmdbId: number;
  title: string;
  posterPath: string | null;
  subtitle: string | null;
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-library-page',
  imports: [RouterLink],
  styleUrl: './library.page.scss',
  template: ` <div class="page library-page">
    <header class="collection-header">
      <div>
        <p class="collection-label">Minha coleção</p>
        <h1>Biblioteca</h1>
        <p class="collection-copy">
          Escolha o que quer guardar por perto e encontre os arquivos locais em um só lugar.
        </p>
      </div>
      <dl class="collection-counts" aria-label="Resumo da biblioteca">
        <div>
          <dt>Minha biblioteca</dt>
          <dd>{{ plannedMovies().length + plannedSeries().length }}</dd>
        </div>
        <div>
          <dt>Disponíveis</dt>
          <dd>{{ items().length + availableSeries().length }}</dd>
        </div>
      </dl>
    </header>
    <label class="library-search"
      ><i class="ph ph-magnifying-glass" aria-hidden="true"></i
      ><span class="sr-only">Buscar na biblioteca</span
      ><input
        type="search"
        autocomplete="off"
        placeholder="Buscar títulos, versões ou arquivos"
        [value]="query()"
        (input)="updateQuery($event)"
      />
      @if (query()) {
        <button type="button" aria-label="Limpar busca" (click)="query.set('')">
          <i class="ph ph-x"></i>
        </button>
      }
    </label>
    @if (state() === 'loading') {
      <section class="library-skeleton" aria-live="polite">
        <div class="skeleton-title"></div>
        <div class="skeleton-rail"><i></i><i></i><i></i><i></i></div>
      </section>
    } @else if (state() === 'error') {
      <section class="empty-library">
        <i class="ph ph-warning-circle" aria-hidden="true"></i>
        <div>
          <h2>Não foi possível abrir a biblioteca</h2>
          <p>{{ errorMessage() }}</p>
          <button class="btn btn-quiet" type="button" (click)="load()">Tentar novamente</button>
        </div>
      </section>
    } @else {
      <section class="collections-section" aria-labelledby="collections-title">
        <div class="section-heading collection-heading">
          <div>
            <h2 id="collections-title">Coleções</h2>
            <p>Listas que organizam o que você quer ver.</p>
          </div>
          <div class="collection-heading-actions">
            <button
              class="text-action"
              type="button"
              (click)="openCollectionBrowser()"
              [disabled]="!collections().length"
            >
              <i class="ph ph-plus-circle"></i>Adicionar títulos</button
            ><button
              class="text-action"
              type="button"
              (click)="showCollectionForm.set(!showCollectionForm())"
            >
              <i class="ph ph-plus"></i>Nova coleção
            </button>
          </div>
        </div>
        @if (showCollectionForm()) {
          <form class="collection-form" (submit)="createCollection($event)">
            <input
              type="text"
              maxlength="48"
              placeholder="Ex.: Para ver no fim de semana"
              [value]="collectionName()"
              (input)="collectionName.set($any($event.target).value)"
            /><button class="btn btn-primary" type="submit" [disabled]="creatingCollection()">
              {{ creatingCollection() ? 'Criando…' : 'Criar' }}
            </button>
          </form>
        }
        @if (collections().length) {
          <div class="collections-grid">
            @for (collection of collections(); track collection.id) {
              <article class="collection-card">
                <div class="collection-card-header">
                  <div>
                    <strong>{{ collection.name }}</strong
                    ><small
                      >{{ collection.items.length }} título{{
                        collection.items.length === 1 ? '' : 's'
                      }}</small
                    >
                  </div>
                  <button
                    type="button"
                    aria-label="Excluir coleção"
                    (click)="removeCollection(collection)"
                  >
                    <i class="ph ph-trash"></i>
                  </button>
                </div>
                @if (collection.items.length) {
                  <div class="collection-posters">
                    @for (
                      item of collection.items.slice(0, 4);
                      track item.mediaType + item.tmdbId
                    ) {
                      <div class="collection-poster">
                        <a
                          [routerLink]="[
                            item.mediaType === 'SERIES' ? '/series' : '/catalog',
                            item.tmdbId,
                          ]"
                        >
                          @if (item.posterPath) {
                            <img [src]="posterUrl(item)" [alt]="item.title" loading="lazy" decoding="async" />
                          } @else {
                            <i class="ph ph-film-strip"></i>
                          }</a
                        ><button
                          type="button"
                          [attr.aria-label]="'Remover ' + item.title + ' da coleção'"
                          (click)="removeCollectionItem(collection, item)"
                        >
                          <i class="ph ph-x"></i>
                        </button>
                      </div>
                    }
                  </div>
                } @else {
                  <div class="collection-empty">
                    <i class="ph ph-squares-four"></i><span>Nenhum título ainda.</span>
                  </div>
                }
                <div class="collection-card-actions">
                  <a
                    class="collection-card-open"
                    [routerLink]="['/library/collections', collection.id]"
                    >Abrir coleção<i class="ph ph-arrow-up-right"></i></a
                  ><button
                    class="collection-card-add"
                    type="button"
                    (click)="openCollectionBrowser(collection.id)"
                  >
                    <i class="ph ph-plus"></i>Adicionar
                  </button>
                </div>
              </article>
            }
          </div>
        } @else {
          <div class="collections-empty">
            <i class="ph ph-folders"></i>
            <div>
              <strong>Crie a sua primeira coleção</strong>
              <p>Depois, procure qualquer filme ou série para colocar nela.</p>
            </div>
          </div>
        }
      </section>
      @if (collectionBrowserOpen()) {
        <div class="collection-browser-backdrop" (click)="closeCollectionBrowser()">
          <section
            class="collection-browser"
            role="dialog"
            aria-modal="true"
            aria-labelledby="collection-browser-title"
            (click)="$event.stopPropagation()"
          >
            <header>
              <div>
                <p>Adicionar à coleção</p>
                <h2 id="collection-browser-title">Escolha um título</h2>
              </div>
              <button type="button" aria-label="Fechar" (click)="closeCollectionBrowser()">
                <i class="ph ph-x"></i>
              </button>
            </header>
            <label class="collection-target"
              ><span>Adicionar em</span
              ><select [value]="selectedCollectionId() ?? ''" (change)="selectCollection($event)">
                @for (collection of collections(); track collection.id) {
                  <option [value]="collection.id">{{ collection.name }}</option>
                }
              </select></label
            ><label class="collection-search"
              ><i class="ph ph-magnifying-glass"></i
              ><input
                type="search"
                autocomplete="off"
                placeholder="Busque filmes ou séries"
                [value]="collectionSearch()"
                (input)="updateCollectionSearch($event)"
            /></label>
            @if (searchingCollection()) {
              <p class="collection-search-status">Procurando títulos…</p>
            } @else if (collectionSearch().trim().length >= 2 && !collectionResults().length) {
              <p class="collection-search-status">Nenhum título encontrado.</p>
            } @else if (collectionResults().length) {
              <div class="collection-results">
                @for (result of collectionResults(); track result.mediaType + result.tmdbId) {
                  <button type="button" (click)="addSearchResultToCollection(result)">
                    @if (result.posterPath) {
                      <img [src]="posterUrl(result)" [alt]="" loading="lazy" decoding="async" />
                    } @else {
                      <span
                        ><i
                          class="ph"
                          [class.ph-film-strip]="result.mediaType === 'MOVIE'"
                          [class.ph-television]="result.mediaType === 'SERIES'"
                        ></i
                      ></span>
                    }
                    <div>
                      <strong>{{ result.title }}</strong
                      ><small>{{
                        result.subtitle || (result.mediaType === 'MOVIE' ? 'Filme' : 'Série')
                      }}</small>
                    </div>
                    <i class="ph ph-plus-circle"></i>
                  </button>
                }
              </div>
            } @else {
              <p class="collection-search-hint">
                Digite pelo menos dois caracteres para buscar no catálogo.
              </p>
            }
          </section>
        </div>
      }
      @if (filteredFavorites().length) {
        <section class="favorites-section" aria-labelledby="favorites-title">
          <div class="section-heading">
            <div>
              <h2 id="favorites-title">Favoritos</h2>
              <p>Os títulos que você marcou para rever.</p>
            </div>
            <span>{{ filteredFavorites().length }}</span>
          </div>
          <div class="favorites-grid">
            @for (favorite of filteredFavorites(); track favorite.mediaType + favorite.tmdbId) {
              <article class="favorite-card">
                <a
                  [routerLink]="[
                    favorite.mediaType === 'SERIES' ? '/series' : '/catalog',
                    favorite.tmdbId,
                  ]"
                >
                  @if (favorite.posterPath) {
                    <img [src]="posterUrl(favorite)" loading="lazy" decoding="async" [alt]="'Pôster de ' + favorite.title" />
                  } @else {
                    <span
                      ><i
                        class="ph"
                        [class.ph-television]="favorite.mediaType === 'SERIES'"
                        [class.ph-film-strip]="favorite.mediaType === 'MOVIE'"
                      ></i
                    ></span>
                  }
                  <strong>{{ favorite.title }}</strong
                  ><small>{{
                    favorite.subtitle || (favorite.mediaType === 'SERIES' ? 'Série' : 'Filme')
                  }}</small></a
                >
                @if (collections().length) {
                  <button
                    type="button"
                    class="favorite-collection-action"
                    (click)="addFavoriteToCollection(favorite)"
                  >
                    <i class="ph ph-plus"></i>Coleção
                  </button>
                }
              </article>
            }
          </div>
        </section>
      }
      @if (filteredPlanned().length) {
        <section class="planned-section" aria-labelledby="planned-title">
          <div class="section-heading">
            <div>
              <h2 id="planned-title">Minha biblioteca</h2>
              <p>Filmes que você salvou na biblioteca.</p>
            </div>
            <span>{{ filteredPlanned().length }}</span>
          </div>
          <div class="planned-grid">
            @for (movie of filteredPlanned(); track movie.tmdbId) {
              <article class="planned-film">
                <a [routerLink]="['/catalog', movie.tmdbId]" class="planned-poster">
                  @if (movie.posterPath) {
                    <img [src]="posterUrl(movie)" loading="lazy" decoding="async" [alt]="'Pôster de ' + movie.title" />
                  } @else {
                    <i class="ph ph-film-strip"></i>
                  }
                </a>
                <div class="planned-copy">
                  <a [routerLink]="['/catalog', movie.tmdbId]">{{ movie.title }}</a>
                  <p>
                    {{ year(movie) }}
                    @if (movie.voteAverage !== null) {
                      <span><i class="ph-fill ph-star"></i>{{ movie.voteAverage.toFixed(1) }}</span>
                    }
                  </p>
                  <button
                    type="button"
                    class="remove-planned"
                    [disabled]="removingTmdbId() === movie.tmdbId"
                    (click)="removePlanned(movie)"
                  >
                    <i class="ph ph-x"></i>Remover da biblioteca
                  </button>
                </div>
              </article>
            }
          </div>
        </section>
      }
      @if (filteredPlannedSeries().length) {
        <section class="planned-section" aria-labelledby="planned-series-title">
          <div class="section-heading">
            <div>
              <h2 id="planned-series-title">Minhas séries</h2>
              <p>Séries salvas para acompanhar por temporadas.</p>
            </div>
            <span>{{ filteredPlannedSeries().length }}</span>
          </div>
          <div class="planned-grid">
            @for (series of filteredPlannedSeries(); track series.tmdbId) {
              <article class="planned-film">
                <a [routerLink]="['/series', series.tmdbId]" class="planned-poster">
                  @if (series.posterPath) {
                    <img [src]="posterUrl(series)" loading="lazy" decoding="async" [alt]="'Pôster de ' + series.name" />
                  } @else {
                    <i class="ph ph-television"></i>
                  }
                </a>
                <div class="planned-copy">
                  <a [routerLink]="['/series', series.tmdbId]">{{ series.name }}</a>
                  <p>
                    {{ seriesYear(series) }}
                    @if (series.voteAverage !== null) {
                      <span
                        ><i class="ph-fill ph-star"></i>{{ series.voteAverage.toFixed(1) }}</span
                      >
                    }
                  </p>
                  <button
                    type="button"
                    class="remove-planned"
                    [disabled]="removingSeriesId() === series.tmdbId"
                    (click)="removePlannedSeries(series)"
                  >
                    <i class="ph ph-x"></i>Remover da lista
                  </button>
                </div>
              </article>
            }
          </div>
        </section>
      }
      @if (filteredAvailableSeries().length) {
        <section class="series-files-section" aria-labelledby="series-files-title">
          <div class="section-heading">
            <div>
              <h2 id="series-files-title">Séries disponíveis</h2>
              <p>Temporadas e episódios que já estão neste computador.</p>
            </div>
            <span>{{ filteredAvailableSeries().length }}</span>
          </div>
          <div class="series-library-grid">
            @for (series of filteredAvailableSeries(); track series.tmdbId) {
              <a class="series-library-card" [routerLink]="['/series', series.tmdbId]">
                @if (series.posterPath) {
                  <img [src]="posterUrl(series)" loading="lazy" decoding="async" [alt]="'Pôster de ' + series.title" />
                } @else {
                  <span><i class="ph ph-television"></i></span>
                }
                <div>
                  <strong>{{ series.title }}</strong>
                  <p>{{ seriesSummary(series) }}</p>
                  <small>{{ size(series.sizeBytes) }} armazenados</small>
                </div></a
              >
            }
          </div>
        </section>
      }
      @if (filteredItems().length) {
        <section class="files-section" aria-labelledby="files-title">
          <div class="section-heading">
            <div>
              <h2 id="files-title">Arquivos locais</h2>
              <p>Versões que já estão disponíveis neste computador.</p>
            </div>
            <span>{{ filteredItems().length }}</span>
          </div>
          <div class="library-list">
            @for (item of filteredItems(); track item.id) {
              <article class="library-row">
                <a [routerLink]="['/catalog', item.movieTmdbId]" class="library-poster">
                  @if (item.posterPath) {
                    <img [src]="posterUrl(item)" [alt]="'Pôster de ' + item.movieTitle" />
                  } @else {
                    <i class="ph ph-film-strip"></i>
                  }
                </a>
                <div class="file-copy">
                  <a [routerLink]="['/catalog', item.movieTmdbId]">{{ item.movieTitle }}</a>
                  <p>
                    {{ quality(item) }} <span>{{ size(item.sizeBytes) }}</span>
                  </p>
                  <small>{{ item.relativePath }}</small>
                </div>
                <div class="library-actions">
                  @if (desktop()) {
                    <button
                      class="icon-button"
                      type="button"
                      [disabled]="desktopActionId() === item.id"
                      [attr.aria-label]="'Abrir ' + item.movieTitle + ' no VLC'"
                      (click)="openInVlc(item)"
                    >
                      <i class="ph ph-play"></i></button
                    ><button
                      class="icon-button"
                      type="button"
                      [disabled]="desktopActionId() === item.id"
                      [attr.aria-label]="'Mostrar ' + item.movieTitle + ' no Finder'"
                      (click)="revealInFinder(item)"
                    >
                      <i class="ph ph-folder-open"></i>
                    </button>
                  }
                  <button
                    class="icon-button delete-file"
                    type="button"
                    [disabled]="deletingId() === item.id"
                    [attr.aria-label]="'Excluir ' + item.movieTitle"
                    (click)="remove(item)"
                  >
                    <i class="ph ph-trash"></i>
                  </button>
                </div>
              </article>
            }
          </div>
        </section>
      }
      @if (
        !filteredFavorites().length &&
        !filteredItems().length &&
        !filteredPlanned().length &&
        !filteredPlannedSeries().length &&
        !filteredAvailableSeries().length
      ) {
        <section class="empty-library" aria-labelledby="empty-library-title">
          <i
            class="ph"
            [class.ph-magnifying-glass]="query()"
            [class.ph-bookmark-simple]="!query()"
            aria-hidden="true"
          ></i>
          <div>
            <h2 id="empty-library-title">
              {{ query() ? 'Nada corresponde à busca' : 'Sua coleção começa aqui' }}
            </h2>
            <p>
              {{
                query()
                  ? 'Tente outro título ou limpe a busca.'
                  : 'Abra um filme no catálogo e use “Adicionar à biblioteca” para salvá-lo aqui.'
              }}
            </p>
            @if (query()) {
              <button class="btn btn-quiet" type="button" (click)="query.set('')">
                Limpar busca
              </button>
            } @else {
              <a class="btn btn-primary" routerLink="/catalog">Explorar catálogo</a>
            }
          </div>
        </section>
      }
    }
  </div>`,
})
export class LibraryPage {
  private readonly libraryApi = inject(LibraryApiService);
  private readonly catalogApi = inject(CatalogApiService);
  private readonly desktopFile = inject(DesktopFileService);
  private readonly destroyRef = inject(DestroyRef);
  private collectionSearchVersion = 0;
  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly items = signal<LibraryItem[]>([]);
  readonly seriesFiles = signal<SeriesLibraryItem[]>([]);
  readonly plannedMovies = signal<PlannedMovie[]>([]);
  readonly plannedSeries = signal<PlannedSeries[]>([]);
  readonly favorites = signal<FavoriteItem[]>([]);
  readonly collections = signal<LibraryCollection[]>([]);
  readonly showCollectionForm = signal(false);
  readonly collectionName = signal('');
  readonly creatingCollection = signal(false);
  readonly collectionBrowserOpen = signal(false);
  readonly selectedCollectionId = signal<number | null>(null);
  readonly collectionSearch = signal('');
  readonly collectionResults = signal<CollectionSearchResult[]>([]);
  readonly searchingCollection = signal(false);
  readonly query = signal('');
  readonly errorMessage = signal('Confira a conexão com o backend e tente novamente.');
  readonly deletingId = signal<number | null>(null);
  readonly removingTmdbId = signal<number | null>(null);
  readonly removingSeriesId = signal<number | null>(null);
  readonly desktop = signal(isDesktopApp());
  readonly desktopActionId = signal<number | null>(null);
  readonly availableSeries = computed(() => {
    const groups = new Map<
      number,
      {
        tmdbId: number;
        title: string;
        posterPath: string | null;
        files: SeriesLibraryItem[];
        sizeBytes: number;
      }
    >();
    for (const file of this.seriesFiles()) {
      const group = groups.get(file.seriesTmdbId) ?? {
        tmdbId: file.seriesTmdbId,
        title: file.seriesTitle,
        posterPath: file.posterPath,
        files: [],
        sizeBytes: 0,
      };
      group.files.push(file);
      group.sizeBytes += file.sizeBytes;
      groups.set(file.seriesTmdbId, group);
    }
    return [...groups.values()];
  });
  readonly filteredItems = computed(() =>
    this.items().filter((item) =>
      this.matches([item.movieTitle, item.relativePath, this.quality(item)]),
    ),
  );
  readonly filteredAvailableSeries = computed(() =>
    this.availableSeries().filter((series) =>
      this.matches([
        series.title,
        this.seriesSummary(series),
        ...series.files.map((file) => file.relativePath),
      ]),
    ),
  );
  readonly filteredPlanned = computed(() =>
    this.plannedMovies().filter((movie) =>
      this.matches([movie.title, movie.originalTitle ?? '', this.year(movie)]),
    ),
  );
  readonly filteredPlannedSeries = computed(() =>
    this.plannedSeries().filter((series) =>
      this.matches([series.name, series.originalName ?? '', this.seriesYear(series)]),
    ),
  );
  readonly filteredFavorites = computed(() =>
    this.favorites().filter((favorite) =>
      this.matches([favorite.title, favorite.subtitle ?? '', favorite.mediaType]),
    ),
  );
  constructor() {
    this.load();
  }
  updateQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }
  openCollectionBrowser(collectionId = this.collections()[0]?.id): void {
    if (!collectionId) return;
    this.selectedCollectionId.set(collectionId);
    this.collectionSearch.set('');
    this.collectionResults.set([]);
    this.collectionBrowserOpen.set(true);
  }
  closeCollectionBrowser(): void {
    this.collectionBrowserOpen.set(false);
    this.collectionSearchVersion++;
  }
  selectCollection(event: Event): void {
    this.selectedCollectionId.set(Number((event.target as HTMLSelectElement).value));
  }
  updateCollectionSearch(event: Event): void {
    const query = (event.target as HTMLInputElement).value;
    this.collectionSearch.set(query);
    const search = query.trim();
    const version = ++this.collectionSearchVersion;
    if (search.length < 2) {
      this.collectionResults.set([]);
      this.searchingCollection.set(false);
      return;
    }
    this.searchingCollection.set(true);
    forkJoin({
      movies: this.catalogApi.search(search).pipe(map((page) => page.items)),
      series: this.catalogApi.searchSeries(search).pipe(map((page) => page.items)),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ movies, series }) => {
          if (version !== this.collectionSearchVersion) return;
          this.collectionResults.set([
            ...movies.slice(0, 6).map((movie) => this.toCollectionMovie(movie)),
            ...series.slice(0, 6).map((item) => this.toCollectionSeries(item)),
          ]);
          this.searchingCollection.set(false);
        },
        error: () => {
          if (version !== this.collectionSearchVersion) return;
          this.collectionResults.set([]);
          this.searchingCollection.set(false);
          this.errorMessage.set('Não foi possível buscar títulos agora.');
        },
      });
  }
  addSearchResultToCollection(result: CollectionSearchResult): void {
    const collectionId = this.selectedCollectionId();
    if (!collectionId) return;
    this.libraryApi
      .addToCollection(collectionId, result.mediaType, result.tmdbId, result)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.collections.update((collections) =>
            collections.map((collection) =>
              collection.id === collectionId &&
              !collection.items.some(
                (item) => item.mediaType === result.mediaType && item.tmdbId === result.tmdbId,
              )
                ? {
                    ...collection,
                    items: [{ ...result, addedAt: new Date().toISOString() }, ...collection.items],
                  }
                : collection,
            ),
          );
          this.closeCollectionBrowser();
        },
        error: () => this.errorMessage.set('Não foi possível adicionar este título à coleção.'),
      });
  }
  load(): void {
    this.state.set('loading');
    forkJoin({
      items: this.libraryApi.browse(),
      seriesFiles: this.libraryApi.browseSeries(),
      planned: this.libraryApi.planned(),
      series: this.libraryApi.plannedSeries(),
      favorites: this.libraryApi.favorites(),
      collections: this.libraryApi.collections(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ items, seriesFiles, planned, series, favorites, collections }) => {
          this.items.set(items);
          this.seriesFiles.set(seriesFiles);
          this.plannedMovies.set(planned);
          this.plannedSeries.set(series);
          this.favorites.set(favorites);
          this.collections.set(collections);
          this.state.set('ready');
        },
        error: (error: unknown) => {
          if (error instanceof HttpErrorResponse && error.status === 0)
            this.errorMessage.set('O backend não está acessível.');
          this.state.set('error');
        },
      });
  }
  createCollection(event: SubmitEvent): void {
    event.preventDefault();
    const name = this.collectionName().trim();
    if (!name) return;
    this.creatingCollection.set(true);
    this.libraryApi
      .createCollection(name)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (collection) => {
          this.collections.update((items) => [collection, ...items]);
          this.collectionName.set('');
          this.showCollectionForm.set(false);
          this.creatingCollection.set(false);
        },
        error: () => {
          this.errorMessage.set('Não foi possível criar esta coleção.');
          this.creatingCollection.set(false);
        },
      });
  }
  removeCollection(collection: LibraryCollection): void {
    if (!confirm(`Excluir a coleção “${collection.name}”? Os títulos permanecerão na Biblioteca.`))
      return;
    this.libraryApi
      .removeCollection(collection.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () =>
          this.collections.update((items) => items.filter((item) => item.id !== collection.id)),
        error: () => this.errorMessage.set('Não foi possível excluir esta coleção.'),
      });
  }
  addFavoritesToCollection(collection: LibraryCollection): void {
    const favorites = this.favorites();
    if (!favorites.length) return;
    forkJoin(
      favorites.map((favorite) =>
        this.libraryApi.addToCollection(
          collection.id,
          favorite.mediaType,
          favorite.tmdbId,
          favorite,
        ),
      ),
    )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () =>
          this.collections.update((items) =>
            items.map((item) => (item.id === collection.id ? { ...item, items: favorites } : item)),
          ),
        error: () => this.errorMessage.set('Não foi possível adicionar os favoritos à coleção.'),
      });
  }
  removeCollectionItem(collection: LibraryCollection, item: FavoriteItem): void {
    this.libraryApi
      .removeFromCollection(collection.id, item.mediaType, item.tmdbId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () =>
          this.collections.update((collections) =>
            collections.map((current) =>
              current.id === collection.id
                ? {
                    ...current,
                    items: current.items.filter(
                      (entry) => entry.mediaType !== item.mediaType || entry.tmdbId !== item.tmdbId,
                    ),
                  }
                : current,
            ),
          ),
        error: () => this.errorMessage.set('Não foi possível remover este título da coleção.'),
      });
  }
  addFavoriteToCollection(favorite: FavoriteItem): void {
    const names = this.collections()
      .map((collection) => collection.name)
      .join('\n');
    const selected = prompt(`Adicionar “${favorite.title}” a qual coleção?\n\n${names}`)?.trim();
    if (!selected) return;
    const collection = this.collections().find(
      (item) => item.name.toLocaleLowerCase() === selected.toLocaleLowerCase(),
    );
    if (!collection) {
      this.errorMessage.set('Escolha uma coleção existente.');
      return;
    }
    this.libraryApi
      .addToCollection(collection.id, favorite.mediaType, favorite.tmdbId, favorite)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () =>
          this.collections.update((items) =>
            items.map((item) =>
              item.id === collection.id &&
              !item.items.some(
                (entry) =>
                  entry.mediaType === favorite.mediaType && entry.tmdbId === favorite.tmdbId,
              )
                ? { ...item, items: [favorite, ...item.items] }
                : item,
            ),
          ),
        error: () => this.errorMessage.set('Não foi possível adicionar este título à coleção.'),
      });
  }
  posterUrl(
    item: LibraryItem | PlannedMovie | PlannedSeries | FavoriteItem | { posterPath: string | null },
  ): string {
    return tmdbImage(item.posterPath, 'poster-card');
  }
  year(movie: PlannedMovie): string {
    return movie.releaseDate ? movie.releaseDate.slice(0, 4) : 'Sem data';
  }
  seriesYear(series: PlannedSeries): string {
    return series.firstAirDate ? series.firstAirDate.slice(0, 4) : 'Sem data';
  }
  quality(item: LibraryItem): string {
    return (
      [
        item.resolutionHeight ? `${item.resolutionHeight}p` : null,
        item.sourceType,
        item.dynamicRange,
      ]
        .filter(Boolean)
        .join(' · ') || 'Versão local'
    );
  }
  size(bytes: number): string {
    return bytes >= 1_000_000_000
      ? `${(bytes / 1_000_000_000).toFixed(1)} GB`
      : `${Math.max(1, Math.round(bytes / 1_000_000))} MB`;
  }
  seriesSummary(series: { files: SeriesLibraryItem[] }): string {
    const episodes = series.files.filter((file) => file.episodeNumber !== null);
    const seasons = [
      ...new Set(
        series.files
          .map((file) => file.seasonNumber)
          .filter((season): season is number => season !== null),
      ),
    ].sort((a, b) => a - b);
    if (episodes.length)
      return episodes
        .map(
          (file) =>
            `T${String(file.seasonNumber).padStart(2, '0')}E${String(file.episodeNumber).padStart(2, '0')}`,
        )
        .join(' · ');
    return `${seasons.map((season) => `Temporada ${season}`).join(' · ')} · ${series.files.length} arquivo${series.files.length === 1 ? '' : 's'}`;
  }
  removePlanned(movie: PlannedMovie): void {
    this.removingTmdbId.set(movie.tmdbId);
    this.libraryApi
      .removePlanned(movie.tmdbId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.plannedMovies.update((movies) =>
            movies.filter((item) => item.tmdbId !== movie.tmdbId),
          );
          this.removingTmdbId.set(null);
        },
        error: () => {
          this.errorMessage.set('Não foi possível remover este filme da lista.');
          this.removingTmdbId.set(null);
        },
      });
  }
  removePlannedSeries(series: PlannedSeries): void {
    this.removingSeriesId.set(series.tmdbId);
    this.libraryApi
      .removePlannedSeries(series.tmdbId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.plannedSeries.update((values) =>
            values.filter((item) => item.tmdbId !== series.tmdbId),
          );
          this.removingSeriesId.set(null);
        },
        error: () => {
          this.errorMessage.set('Não foi possível remover esta série da lista.');
          this.removingSeriesId.set(null);
        },
      });
  }
  remove(item: LibraryItem): void {
    if (this.desktop()) {
      this.desktopFile.confirmDeletion(item.movieTitle).then((confirmed) => {
        if (confirmed) this.deleteFile(item);
      });
      return;
    }
    if (
      confirm(`Excluir o arquivo local de “${item.movieTitle}”? Esta ação não pode ser desfeita.`)
    )
      this.deleteFile(item);
  }
  openInVlc(item: LibraryItem): void {
    this.runDesktopAction(item, () => this.desktopFile.openInVlc(item.relativePath));
  }
  revealInFinder(item: LibraryItem): void {
    this.runDesktopAction(item, () => this.desktopFile.revealInFinder(item.relativePath));
  }
  private toCollectionMovie(movie: CatalogMovie): CollectionSearchResult {
    return {
      mediaType: 'MOVIE',
      tmdbId: movie.tmdbId,
      title: movie.title,
      posterPath: movie.posterPath,
      subtitle: movie.releaseDate?.slice(0, 4) ?? 'Filme',
    };
  }
  private toCollectionSeries(series: CatalogSeries): CollectionSearchResult {
    return {
      mediaType: 'SERIES',
      tmdbId: series.tmdbId,
      title: series.name,
      posterPath: series.posterPath,
      subtitle: series.firstAirDate?.slice(0, 4) ?? 'Série',
    };
  }
  private matches(values: string[]): boolean {
    const query = this.query().trim().toLocaleLowerCase();
    return !query || values.some((value) => value.toLocaleLowerCase().includes(query));
  }
  private deleteFile(item: LibraryItem): void {
    this.deletingId.set(item.id);
    this.libraryApi
      .remove(item.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.items.update((items) => items.filter((current) => current.id !== item.id));
          this.deletingId.set(null);
        },
        error: () => {
          this.errorMessage.set('Não foi possível excluir este arquivo.');
          this.deletingId.set(null);
        },
      });
  }
  private runDesktopAction(item: LibraryItem, action: () => Promise<void>): void {
    this.desktopActionId.set(item.id);
    action()
      .catch(() =>
        this.errorMessage.set(
          'Não foi possível acessar esse arquivo local. Confira se o Docker e o VLC estão ativos.',
        ),
      )
      .finally(() => this.desktopActionId.set(null));
  }
}
