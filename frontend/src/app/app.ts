import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { Subject, catchError, debounceTime, distinctUntilChanged, forkJoin, of, switchMap } from 'rxjs';

import { CatalogApiService, CatalogMovie, CatalogPerson, CatalogSeries } from './core/catalog-api.service';
import { isDesktopApp } from './core/api-url';
import { SetupBannerComponent } from './core/setup-banner.component';
import { DownloadNotificationsService } from './core/download-notifications.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, SetupBannerComponent],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly catalogApi = inject(CatalogApiService);
  private readonly downloadNotifications = inject(DownloadNotificationsService);
  private readonly queryChanges = new Subject<string>();

  readonly globalQuery = signal('');
  readonly desktop = signal(isDesktopApp());
  readonly maximized = signal(false);
  readonly suggestions = signal<CatalogMovie[]>([]);
  readonly peopleSuggestions = signal<CatalogPerson[]>([]);
  readonly seriesSuggestions = signal<CatalogSeries[]>([]);
  readonly autocompleteOpen = signal(false);
  readonly autocompleteState = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
  readonly notificationPanelOpen = signal(false);
  readonly notifications = this.downloadNotifications.notifications;
  readonly theme = signal<'dark' | 'light'>(
    typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark',
  );

  constructor() {
    if (this.desktop()) {
      void getCurrentWindow().isMaximized().then((value) => this.maximized.set(value));
    }
    this.downloadNotifications.start();
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.globalQuery.set(params.get('q') ?? '');
    });
    this.queryChanges.pipe(
      debounceTime(220),
      distinctUntilChanged(),
      switchMap((query) => {
        if (query.length < 2) {
          this.autocompleteState.set('idle');
          return of({ movies: [], people: [], series: [] });
        }
        this.autocompleteState.set('loading');
        return forkJoin({
          movies: this.catalogApi.search(query).pipe(catchError(() => of([]))),
          people: this.catalogApi.searchPeople(query).pipe(catchError(() => of([]))),
          series: this.catalogApi.searchSeries(query).pipe(catchError(() => of([]))),
        });
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(({ movies, people, series }) => {
      this.suggestions.set(movies.slice(0, 6));
      this.peopleSuggestions.set(people.slice(0, 3));
      this.seriesSuggestions.set(series.slice(0, 3));
      if (this.autocompleteState() !== 'error') this.autocompleteState.set('ready');
    });
  }

  toggleNotifications(): void {
    this.notificationPanelOpen.update((open) => !open);
    if (!this.notificationPanelOpen()) return;
    this.downloadNotifications.markAllRead();
  }

  clearNotifications(): void { this.downloadNotifications.clear(); }

  notificationTime(date: Date): string {
    return new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit' }).format(date);
  }

  toggleTheme(): void {
    this.theme.update((current) => current === 'dark' ? 'light' : 'dark');
  }

  updateGlobalQuery(event: Event): void {
    const query = (event.target as HTMLInputElement).value;
    this.globalQuery.set(query);
    this.autocompleteOpen.set(true);
    if (query.trim().length < 2) this.autocompleteState.set('idle');
    this.queryChanges.next(query.trim());
  }

  openAutocomplete(): void {
    this.autocompleteOpen.set(true);
  }

  closeAutocomplete(): void {
    window.setTimeout(() => this.autocompleteOpen.set(false), 140);
  }

  selectSuggestion(movie: CatalogMovie): void {
    this.globalQuery.set(movie.title);
    this.suggestions.set([]);
    this.peopleSuggestions.set([]);
    this.seriesSuggestions.set([]);
    this.autocompleteState.set('idle');
    this.autocompleteOpen.set(false);
    void this.router.navigate(['/catalog', movie.tmdbId]);
  }

  selectPerson(person: CatalogPerson): void {
    this.globalQuery.set(person.name);
    this.suggestions.set([]);
    this.peopleSuggestions.set([]);
    this.seriesSuggestions.set([]);
    this.autocompleteState.set('idle');
    this.autocompleteOpen.set(false);
    void this.router.navigate(['/people', person.tmdbId]);
  }

  selectSeries(series: CatalogSeries): void {
    this.globalQuery.set(series.name);
    this.suggestions.set([]);
    this.peopleSuggestions.set([]);
    this.seriesSuggestions.set([]);
    this.autocompleteState.set('idle');
    this.autocompleteOpen.set(false);
    void this.router.navigate(['/series', series.tmdbId]);
  }

  searchCatalog(event: Event): void {
    event.preventDefault();
    const query = this.globalQuery().trim();
    this.autocompleteOpen.set(false);
    void this.router.navigate(['/catalog'], { queryParams: { q: query || null } });
  }

  async minimizeWindow(): Promise<void> {
    if (this.desktop()) await getCurrentWindow().minimize();
  }

  async toggleMaximize(): Promise<void> {
    if (!this.desktop()) return;
    const currentWindow = getCurrentWindow();
    if (await currentWindow.isMaximized()) {
      await currentWindow.unmaximize();
      this.maximized.set(false);
    } else {
      await currentWindow.maximize();
      this.maximized.set(true);
    }
  }

  async closeWindow(): Promise<void> {
    if (this.desktop()) await getCurrentWindow().close();
  }

  async startWindowDrag(event: MouseEvent): Promise<void> {
    if (!this.desktop() || event.button !== 0) return;
    const target = event.target;
    if (target instanceof Element && target.closest('button, a, input, select, textarea')) return;
    await getCurrentWindow().startDragging();
  }
}
