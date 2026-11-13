import { Location } from '@angular/common';
import { IntegrationAvailabilityService } from '../core/integration-availability.service';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { SearchHistoryService } from '../core/search-history.service';
import { tmdbImage } from '../core/tmdb-image';
import { CatalogApiService, CatalogMovie, MovieDetails } from '../core/catalog-api.service';
import { DownloadsApiService } from '../core/downloads-api.service';
import { LibraryApiService } from '../core/library-api.service';
import { ReleaseOption, ReleasesApiService } from '../core/releases-api.service';
import { DesktopFileService } from '../core/desktop-file.service';
import { isDesktopApp } from '../core/api-url';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-movie-details-page',
  imports: [RouterLink],
  styleUrl: './movie-details.page.scss',
  template: `
    @if (!availability.ready('prowlarr') || !availability.ready('qbittorrent')) { <p class="page" role="status">{{ availability.state() === 'loading' ? 'Conferindo as integrações…' : availability.state() === 'error' ? 'Não foi possível conferir busca e downloads.' : 'Busca, motor ou pasta dos downloads precisam de configuração. O catálogo continua disponível.' }} <a routerLink="/setup">Configurar recursos</a></p> }
    @switch (state()) {
      @case ('loading') {
        <div class="page details-skeleton" aria-label="Carregando detalhes"><div class="skeleton-hero"></div><div class="skeleton-body"><span class="skeleton-poster"></span><div class="skeleton-copy"><i></i><b></b><em></em><em></em><div><small></small><small></small><small></small></div></div></div><div class="skeleton-lines"><i></i><i></i><i></i></div></div>
      }
      @case ('error') {
        <div class="page details-state details-error"><i class="ph ph-warning-circle"></i><div><strong>Não foi possível carregar este filme.</strong><p>{{ errorMessage() }}</p><button class="btn btn-quiet" type="button" (click)="backToCatalog()">Voltar ao catálogo</button></div></div>
      }
      @default {
        @if (movie(); as details) {
          <section class="details-hero" aria-labelledby="movie-title">
            @if (details.backdropPath) { <img [src]="backdropUrl(details)" [alt]="'Cena de ' + details.title" fetchpriority="high" decoding="async" /> }
            <div class="details-shade"></div>
            <div class="page details-hero-content"><button class="back-link" type="button" (click)="backToCatalog()"><i class="ph ph-arrow-left"></i>Catálogo</button></div>
          </section>

          <main class="page details-content">
            <div class="details-layout">
              <aside class="detail-poster">
                @if (details.posterPath) { <img [src]="posterUrl(details)" [alt]="'Pôster de ' + details.title" loading="lazy" decoding="async" /> }
                @else { <span><i class="ph ph-film-strip"></i></span> }
              </aside>
              <article class="details-copy">
                <p class="detail-kicker">{{ year(details) }} @if (details.runtimeMinutes) { <span>{{ runtime(details.runtimeMinutes) }}</span> } <span><i class="ph-fill ph-star"></i>{{ rating(details) }}</span></p>
                <h1 id="movie-title">{{ details.title }}</h1>
                @if (details.originalTitle && details.originalTitle !== details.title) { <p class="original-title">{{ details.originalTitle }}</p> }
                @if (details.tagline) { <p class="tagline">{{ details.tagline }}</p> }
                @if (details.genres.length) { <ul class="genre-list" aria-label="Gêneros">@for (genre of details.genres; track genre) { <li>{{ genre }}</li> }</ul> }
                <div class="detail-actions"><button class="btn btn-primary release-button" type="button" [disabled]="!availability.ready('prowlarr')" (click)="openReleases(details)"><i class="ph ph-download-simple"></i>Opções de download</button><button class="btn btn-quiet library-button" type="button" [disabled]="libraryAction() === 'adding' || libraryAction() === 'added'" (click)="addToLibrary(details)"><i class="ph" [class.ph-bookmark-simple]="libraryAction() !== 'added'" [class.ph-check]="libraryAction() === 'added'"></i>{{ libraryAction() === 'added' ? 'Na minha lista' : libraryAction() === 'adding' ? 'Adicionando' : 'Adicionar à biblioteca' }}</button><button class="btn btn-quiet library-button favorite-detail" type="button" (click)="toggleFavorite(details)"><i class="ph" [class.ph-heart-fill]="isFavorite()" [class.ph-heart]="!isFavorite()"></i>{{ isFavorite() ? 'Favorito' : 'Favoritar' }}</button></div>
                <section class="overview" aria-labelledby="overview-title"><h2 id="overview-title">Sinopse</h2><p>{{ details.overview || 'Sinopse ainda não disponível.' }}</p></section>
              </article>
            </div>
            <div class="details-extra">
              @if (details.director || details.writers.length) { <section class="credits-section" aria-labelledby="credits-title"><h2 id="credits-title">Ficha técnica</h2><dl>@if (details.director) { <div><dt>Direção</dt><dd>{{ details.director }}</dd></div> } @if (details.writers.length) { <div><dt>Roteiro</dt><dd>{{ details.writers.join(', ') }}</dd></div> }</dl></section> }
              @if (details.trailerUrl; as trailerUrl) { <section class="trailer-section" aria-labelledby="trailer-title"><div class="trailer-heading"><h2 id="trailer-title">Trailer</h2><a [href]="trailerUrl" target="_blank" rel="noreferrer" (click)="openTrailer($event, trailerUrl)">Abrir no YouTube <i class="ph ph-arrow-up-right"></i></a></div><div class="trailer-player"><iframe [src]="trailerEmbedUrl(trailerUrl)" [title]="'Trailer de ' + details.title" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe></div></section> }
              @if (details.cast.length) { <section class="cast-section" aria-labelledby="cast-title"><h2 id="cast-title">Elenco principal</h2><div class="cast-list">@for (member of details.cast; track member.tmdbId) { <a class="cast-member" [routerLink]="['/people', member.tmdbId]" [attr.aria-label]="'Ver detalhes de ' + member.name">@if (member.profilePath) { <img [src]="profileUrl(member.profilePath)" [alt]="member.name" loading="lazy" decoding="async" /> } @else { <span><i class="ph ph-user"></i></span> }<div><strong>{{ member.name }}</strong>@if (member.character) { <small>{{ member.character }}</small> }</div></a> }</div></section> }
              @if (details.recommendations.length) { <section class="recommendations-section" aria-labelledby="recommendations-title"><h2 id="recommendations-title">Você também pode gostar</h2><div class="recommendations-grid">@for (recommendation of details.recommendations; track recommendation.tmdbId) { <a [routerLink]="['/catalog', recommendation.tmdbId]">@if (recommendation.posterPath) { <img [src]="recommendationPosterUrl(recommendation)" [alt]="'Pôster de ' + recommendation.title" loading="lazy" decoding="async" /> } @else { <span><i class="ph ph-film-strip"></i></span> }<strong>{{ recommendation.title }}</strong><small>{{ year(recommendation) }}</small></a> }</div></section> }
            </div>
          </main>
          @if (showReleases()) { <div class="release-overlay" role="presentation" (click)="closeReleases()"><section class="release-modal" role="dialog" aria-modal="true" aria-labelledby="release-title" (click)="$event.stopPropagation()"><header><div><p>OPÇÕES DISPONÍVEIS</p><h2 id="release-title">{{ details.title }}</h2></div><button class="icon-button" type="button" aria-label="Fechar" (click)="closeReleases()"><i class="ph ph-x"></i></button></header>@if (submissionMessage()) { <p class="submission-message">{{ submissionMessage() }}</p> } @if (releaseState() === 'loading') { <div class="release-state"><i class="ph ph-spinner-gap"></i>Procurando opções para download.</div> } @else if (releaseState() === 'error') { <div class="release-state error">Não foi possível encontrar opções agora. Tente novamente.</div> } @else if (!releases().length) { <div class="release-state">Nenhuma opção encontrada para este título.</div> } @else { <div class="release-list">@for (release of releases(); track releaseKey(release)) { <article><div><strong>{{ release.title }}</strong><p>{{ release.indexer || 'Fonte' }} · {{ release.seeders ?? 0 }} pessoas compartilhando · {{ size(release.size) }}</p></div><div class="release-actions"><button class="btn btn-primary" type="button" [disabled]="!availability.ready('qbittorrent') || !acquisitionRef(release) || submittingRelease() === acquisitionRef(release)" (click)="enqueue(details, release)">@if (submittingRelease() === acquisitionRef(release)) { Preparando… } @else { Baixar }</button></div></article> }</div> }</section></div> }
        }
      }
    }
  `,
})
export class MovieDetailsPage {
  private readonly catalogApi = inject(CatalogApiService);
  private readonly location = inject(Location);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly releasesApi = inject(ReleasesApiService);
  private readonly downloadsApi = inject(DownloadsApiService);
  private readonly libraryApi = inject(LibraryApiService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly desktopFile = inject(DesktopFileService);
  private readonly searchHistory = inject(SearchHistoryService);

  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly movie = signal<MovieDetails | null>(null);
  readonly errorMessage = signal('Confira a conexão com o TMDB e tente novamente.');
  readonly showReleases = signal(false);
  readonly releaseState = signal<'loading' | 'ready' | 'error'>('ready');
  readonly releases = signal<ReleaseOption[]>([]);
  readonly submittingRelease = signal<string | null>(null);
  readonly submissionMessage = signal<string | null>(null);
  readonly libraryAction = signal<'idle' | 'adding' | 'added'>('idle');
  readonly isFavorite = signal(false);

  readonly availability = inject(IntegrationAvailabilityService);

  constructor() {
    this.availability.refresh();
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const tmdbId = Number(params.get('tmdbId'));
      if (!Number.isSafeInteger(tmdbId) || tmdbId <= 0) {
        this.errorMessage.set('O identificador do filme é inválido.');
        this.state.set('error');
        return;
      }
      this.load(tmdbId);
    });
  }

  backToCatalog(): void {
    this.location.back();
  }

  backdropUrl(movie: MovieDetails): string {
    return tmdbImage(movie.backdropPath, 'backdrop-hero');
  }

  posterUrl(movie: MovieDetails): string {
    return tmdbImage(movie.posterPath, 'poster-detail');
  }

  profileUrl(path: string): string { return tmdbImage(path, 'cast'); }

  recommendationPosterUrl(movie: MovieDetails['recommendations'][number]): string { return tmdbImage(movie.posterPath, 'poster-detail'); }

  trailerEmbedUrl(trailerUrl: string): SafeResourceUrl {
    const videoId = new URL(trailerUrl).searchParams.get('v');
    return this.sanitizer.bypassSecurityTrustResourceUrl(`https://www.youtube-nocookie.com/embed/${videoId ?? ''}?rel=0&cc_lang_pref=pt-BR&cc_load_policy=1`);
  }

  openTrailer(event: MouseEvent, trailerUrl: string): void {
    if (!isDesktopApp()) return;
    event.preventDefault();
    void this.desktopFile.openExternalUrl(trailerUrl);
  }

  year(movie: CatalogMovie): string {
    return movie.releaseDate ? movie.releaseDate.slice(0, 4) : 'Sem data';
  }

  runtime(minutes: number): string {
    return `${Math.floor(minutes / 60)}h ${minutes % 60}min`;
  }

  rating(movie: MovieDetails): string {
    return movie.voteAverage == null ? 'N/D' : movie.voteAverage.toFixed(1);
  }
  openReleases(movie: MovieDetails): void { this.showReleases.set(true); this.releaseState.set('loading'); this.submissionMessage.set(null); const query = `${movie.originalTitle || movie.title} ${this.year(movie)}`; this.releasesApi.search(query, this.year(movie)).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (releases) => { this.releases.set(releases); this.releaseState.set('ready'); }, error: () => this.releaseState.set('error') }); }
  closeReleases(): void { this.showReleases.set(false); }
  size(bytes: number | null): string { return bytes ? `${(bytes / 1_000_000_000).toFixed(1)} GB` : 'Tamanho não informado'; }
  enqueue(movie: MovieDetails, release: ReleaseOption): void {
    const acquisitionRef = this.acquisitionRef(release);
    if (!acquisitionRef) return;
    this.submittingRelease.set(acquisitionRef);
    this.submissionMessage.set(null);
    const title = release.title.toLowerCase();
    const resolution = title.includes('2160p') ? 2160 : title.includes('1080p') ? 1080 : title.includes('720p') ? 720 : null;
    const source = title.includes('web-dl') ? 'WEB-DL' : title.includes('webrip') ? 'WEBRip' : title.includes('bluray') ? 'BluRay' : null;
    const dynamicRange = title.includes('hdr') ? 'HDR' : null;
    this.downloadsApi.enqueue({ movieTmdbId: movie.tmdbId, releaseTitle: release.title, acquisitionRef, indexerName: release.indexer, resolutionHeight: resolution, sourceType: source, dynamicRange }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => { this.submissionMessage.set('Download adicionado. Acompanhe o andamento em Downloads.'); this.submittingRelease.set(null); }, error: (error: unknown) => { this.submissionMessage.set(error instanceof HttpErrorResponse ? error.error?.detail || 'Não foi possível iniciar este download. Tente novamente.' : 'Não foi possível iniciar este download. Tente novamente.'); this.submittingRelease.set(null); } });
  }
  addToLibrary(movie: MovieDetails): void {
    this.libraryAction.set('adding');
    this.libraryApi.addPlanned(movie.tmdbId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.libraryAction.set('added'),
      error: () => this.libraryAction.set('idle'),
    });
  }
  toggleFavorite(movie: MovieDetails): void { const favorite = this.isFavorite(); const request = favorite ? this.libraryApi.removeFavorite('MOVIE', movie.tmdbId) : this.libraryApi.addFavorite('MOVIE', movie.tmdbId, { title: movie.title, posterPath: movie.posterPath, subtitle: this.year(movie) }); request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => this.isFavorite.set(!favorite) }); }
  acquisitionRef(release: ReleaseOption): string { return release.magnetUrl || release.downloadUrl || ''; }
  releaseKey(release: ReleaseOption): string { return this.acquisitionRef(release) || release.infoUrl || release.title; }

  private load(tmdbId: number): void {
    this.state.set('loading');
    this.catalogApi.details(tmdbId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (details) => {
        this.movie.set(details);
        this.searchHistory.rememberTitle({ mediaType: 'MOVIE', tmdbId: details.tmdbId, title: details.title, subtitle: this.year(details), posterPath: details.posterPath });
        this.state.set('ready');
        this.libraryApi.isPlanned(tmdbId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: (status) => this.libraryAction.set(status.planned ? 'added' : 'idle'),
        });
        this.libraryApi.favorites().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (items) => this.isFavorite.set(items.some((item) => item.mediaType === 'MOVIE' && item.tmdbId === tmdbId)) });
      },
      error: (error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 404) {
          this.errorMessage.set('O filme não foi encontrado no TMDB.');
        } else if (error instanceof HttpErrorResponse && error.status === 503) {
          this.errorMessage.set('A integração TMDB não está configurada.');
        }
        this.state.set('error');
      },
    });
  }
}
