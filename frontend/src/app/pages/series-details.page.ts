import { IntegrationAvailabilityService } from '../core/integration-availability.service';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, of, switchMap } from 'rxjs';

import { SearchHistoryService } from '../core/search-history.service';
import { tmdbImage } from '../core/tmdb-image';
import { CatalogApiService, SeasonDetails, SeriesDetails, SeriesEpisode, SeriesSeason } from '../core/catalog-api.service';
import { LibraryApiService } from '../core/library-api.service';
import { ReleaseOption, ReleasesApiService } from '../core/releases-api.service';
import { DownloadsApiService } from '../core/downloads-api.service';
import { DesktopFileService } from '../core/desktop-file.service';
import { isDesktopApp } from '../core/api-url';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-series-details-page',
  imports: [RouterLink],
  styleUrl: './series-details.page.scss',
  template: `
    @if (!availability.ready('prowlarr') || !availability.ready('qbittorrent')) { <p class="page" role="status">{{ availability.state() === 'loading' ? 'Conferindo as integrações…' : availability.state() === 'error' ? 'Não foi possível conferir busca e downloads.' : 'Busca, motor ou pasta dos downloads precisam de configuração. O catálogo continua disponível.' }} <a routerLink="/setup">Configurar recursos</a></p> }
    @if (details(); as data) {
      <section class="details-hero" aria-labelledby="series-title">
        @if (data.series.backdropPath) { <img [src]="backdropUrl(data.series.backdropPath)" [alt]="'Cena de ' + data.series.name" fetchpriority="high" decoding="async" /> }
        <div class="details-shade"></div>
        <div class="page details-hero-content"><a class="back-link" routerLink="/series"><i class="ph ph-arrow-left"></i>Séries</a></div>
      </section>
      <main class="page details-content">
        <div class="details-layout">
          <aside class="detail-poster">
            @if (data.series.posterPath) { <img [src]="posterUrl(data.series.posterPath)" [alt]="'Pôster de ' + data.series.name" loading="lazy" decoding="async" /> }
            @else { <span><i class="ph ph-television"></i></span> }
          </aside>
          <article class="details-copy">
            <p class="detail-kicker">{{ year(data.series.firstAirDate) }} <span>{{ data.numberOfSeasons }} temporadas</span><span><i class="ph-fill ph-star"></i>{{ rating(data.series.voteAverage) }}</span></p>
            <h1 id="series-title">{{ data.series.name }}</h1>
            @if (data.series.originalName && data.series.originalName !== data.series.name) { <p class="original-title">{{ data.series.originalName }}</p> }
            @if (data.tagline) { <p class="tagline">{{ data.tagline }}</p> }
            @if (data.genres.length) { <ul class="genre-list">@for (genre of data.genres; track genre) { <li>{{ genre }}</li> }</ul> }
            <div class="detail-actions"><button class="btn btn-quiet library-button" type="button" [disabled]="libraryAction() !== 'idle'" (click)="addToLibrary(data)"><i class="ph" [class.ph-bookmark-simple]="libraryAction() !== 'added'" [class.ph-check]="libraryAction() === 'added'"></i>{{ libraryAction() === 'added' ? 'Na minha lista' : libraryAction() === 'adding' ? 'Adicionando' : 'Adicionar à biblioteca' }}</button><button class="btn btn-quiet library-button favorite-detail" type="button" (click)="toggleFavorite(data)"><i class="ph" [class.ph-heart-fill]="isFavorite()" [class.ph-heart]="!isFavorite()"></i>{{ isFavorite() ? 'Favorito' : 'Favoritar' }}</button></div>
            <section class="overview"><h2>Sinopse</h2><p>{{ data.series.overview || 'Sinopse ainda não disponível.' }}</p></section>
          </article>
        </div>
        <div class="details-extra">
          @if (data.trailerUrl; as trailerUrl) { <section class="trailer-section" aria-labelledby="trailer-title"><div class="trailer-heading"><h2 id="trailer-title">Trailer</h2><a [href]="trailerUrl" target="_blank" rel="noreferrer" (click)="openTrailer($event, trailerUrl)">Abrir no YouTube <i class="ph ph-arrow-up-right"></i></a></div><div class="trailer-player"><iframe [src]="trailerEmbedUrl(trailerUrl)" [title]="'Trailer de ' + data.series.name" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe></div></section> }
          <section class="season-section">
            <div class="section-heading"><h2>Temporadas</h2>@if (seasonState() === 'loading') { <span>Carregando episódios</span> }</div>
            <div class="season-navigator"><div class="season-tabs" #seasonTabs (scroll)="syncSeasonSlider(seasonTabs)">@for (season of data.seasons; track season.tmdbId) { <button type="button" [class.active]="selectedSeason()?.seasonNumber === season.seasonNumber" (click)="selectSeason(data.series.tmdbId, season, $event)">T{{ season.seasonNumber }}</button> }</div>@if (data.seasons.length > 8) { <input class="season-slider" type="range" min="0" max="100" [value]="seasonSliderValue()" aria-label="Navegar pelas temporadas" (input)="moveSeasonSlider(seasonTabs, $event)" /> }</div>
            @if (selectedSeason(); as season) {
              <div class="season-copy"><strong>{{ season.name }}</strong><span>{{ season.episodeCount }} episódios</span></div>
              <button class="btn btn-primary season-download" type="button" [disabled]="!availability.ready('prowlarr')" (click)="openReleases(data, season)"><i class="ph ph-download-simple"></i>Opções de download</button>
            }
            @if (seasonDetails(); as current) {
              <div class="episode-list">@for (episode of current.episodes; track episode.tmdbId) {
                <article>@if (episode.stillPath) { <img [src]="stillUrl(episode)" [alt]="episode.name" loading="lazy" decoding="async" /> } @else { <span><i class="ph ph-play-circle"></i></span> }
                  <div><p>E{{ padded(episode.episodeNumber) }} · {{ year(episode.airDate) }}</p><h3>{{ episode.name }}</h3>@if (episode.overview) { <small>{{ episode.overview }}</small> }<button class="episode-download" type="button" [disabled]="!availability.ready('prowlarr')" (click)="openEpisodeReleases(data, selectedSeason()!, episode)"><i class="ph ph-download-simple"></i>Opções de download</button></div>
                </article>
              }</div>
            }
          </section>
          @if (data.cast.length) { <section class="cast-section"><h2>Elenco principal</h2><div class="cast-list">@for (member of data.cast; track member.tmdbId) { <a class="cast-member" [routerLink]="['/people', member.tmdbId]">@if (member.profilePath) { <img [src]="profileUrl(member.profilePath)" [alt]="member.name" loading="lazy" decoding="async" /> } @else { <span><i class="ph ph-user"></i></span> }<div><strong>{{ member.name }}</strong>@if (member.character) { <small>{{ member.character }}</small> }</div></a> }</div></section> }
        </div>
      </main>
      @if (showReleases()) {
        <div class="release-overlay" role="presentation" (click)="closeReleases()">
          <section class="release-modal" role="dialog" aria-modal="true" aria-labelledby="release-title" (click)="$event.stopPropagation()">
            <header><div><p>OPÇÕES DISPONÍVEIS</p><h2 id="release-title">{{ releaseTargetLabel() }}</h2></div><button class="icon-button" type="button" aria-label="Fechar" (click)="closeReleases()"><i class="ph ph-x"></i></button></header>
            @if (submissionMessage()) { <p class="submission-message">{{ submissionMessage() }}</p> }
            @if (releaseState() === 'loading') { <div class="release-state"><i class="ph ph-spinner-gap"></i>Procurando opções para download.</div> }
            @else if (releaseState() === 'error') { <div class="release-state error">Não foi possível encontrar opções agora. Tente novamente.</div> }
            @else if (!releases().length) { <div class="release-state">Nenhuma opção encontrada para {{ selectedEpisode() ? 'este episódio' : 'esta temporada' }}.</div> }
            @else { <div class="release-list">@for (release of releases(); track releaseKey(release)) { <article><div><strong>{{ release.title }}</strong><p>{{ release.indexer || 'Fonte' }} · {{ release.seeders ?? 0 }} pessoas compartilhando · {{ size(release.size) }}</p></div><div class="release-actions"><button class="btn btn-primary" type="button" [disabled]="!availability.ready('qbittorrent') || !acquisitionRef(release) || submittingRelease() === acquisitionRef(release)" (click)="enqueue(data, selectedSeason()!, selectedEpisode(), release)">@if (submittingRelease() === acquisitionRef(release)) { Preparando… } @else { Baixar }</button></div></article> }</div> }
          </section>
        </div>
      }
    } @else { <div class="page details-skeleton" aria-label="Carregando detalhes"><div class="skeleton-hero"></div><div class="skeleton-body"><span class="skeleton-poster"></span><div class="skeleton-copy"><i></i><b></b><em></em><em></em><div><small></small><small></small><small></small></div></div></div><div class="skeleton-lines"><i></i><i></i><i></i></div></div> }
  `,
})
export class SeriesDetailsPage {
  private readonly api = inject(CatalogApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly libraryApi = inject(LibraryApiService);
  private readonly releasesApi = inject(ReleasesApiService);
  private readonly downloadsApi = inject(DownloadsApiService);
  private readonly desktopFile = inject(DesktopFileService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly destroyRef = inject(DestroyRef);
  private readonly searchHistory = inject(SearchHistoryService);
  readonly details = signal<SeriesDetails | null>(null);
  readonly selectedSeason = signal<SeriesSeason | null>(null);
  readonly seasonSliderValue = signal(0);
  readonly seasonDetails = signal<SeasonDetails | null>(null);
  readonly seasonState = signal<'idle' | 'loading'>('idle');
  readonly libraryAction = signal<'idle' | 'adding' | 'added'>('idle');
  readonly isFavorite = signal(false);
  readonly showReleases = signal(false);
  readonly selectedEpisode = signal<SeriesEpisode | null>(null);
  readonly releases = signal<ReleaseOption[]>([]);
  readonly releaseState = signal<'loading' | 'ready' | 'error'>('ready');
  readonly submittingRelease = signal<string | null>(null);
  readonly submissionMessage = signal<string | null>(null);

  readonly availability = inject(IntegrationAvailabilityService);

  constructor() {
    this.availability.refresh();
    this.route.paramMap.pipe(switchMap(params => this.api.seriesDetails(Number(params.get('tmdbId'))).pipe(catchError(() => of(null)))), takeUntilDestroyed(this.destroyRef)).subscribe(data => {
      this.details.set(data); this.libraryAction.set('idle'); this.closeReleases();
      if (data) this.searchHistory.rememberTitle({ mediaType: 'SERIES', tmdbId: data.series.tmdbId, title: data.series.name, subtitle: data.series.firstAirDate?.slice(0, 4) ?? 'Série', posterPath: data.series.posterPath });
      if (data) this.libraryApi.isSeriesPlanned(data.series.tmdbId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(value => this.libraryAction.set(value.planned ? 'added' : 'idle'));
      if (data) this.libraryApi.favorites().pipe(takeUntilDestroyed(this.destroyRef)).subscribe(items => this.isFavorite.set(items.some(item => item.mediaType === 'SERIES' && item.tmdbId === data.series.tmdbId)));
      const first = data?.seasons[0]; if (data && first) this.selectSeason(data.series.tmdbId, first);
    });
  }

  addToLibrary(data: SeriesDetails): void { this.libraryAction.set('adding'); this.libraryApi.addPlannedSeries({ tmdbId: data.series.tmdbId, name: data.series.name, originalName: data.series.originalName, posterPath: data.series.posterPath, firstAirDate: data.series.firstAirDate, voteAverage: data.series.voteAverage, addedAt: '' }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => this.libraryAction.set('added'), error: () => this.libraryAction.set('idle') }); }
  toggleFavorite(data: SeriesDetails): void { const favorite = this.isFavorite(); const request = favorite ? this.libraryApi.removeFavorite('SERIES', data.series.tmdbId) : this.libraryApi.addFavorite('SERIES', data.series.tmdbId, { title: data.series.name, posterPath: data.series.posterPath, subtitle: this.year(data.series.firstAirDate) }); request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => this.isFavorite.set(!favorite) }); }
  openReleases(data: SeriesDetails, season: SeriesSeason): void { this.selectedEpisode.set(null); this.openReleaseSearch(data, season, null); }
  openEpisodeReleases(data: SeriesDetails, season: SeriesSeason, episode: SeriesEpisode): void { this.selectedEpisode.set(episode); this.openReleaseSearch(data, season, episode); }
  closeReleases(): void { this.showReleases.set(false); this.releases.set([]); this.submittingRelease.set(null); this.selectedEpisode.set(null); }
  enqueue(data: SeriesDetails, season: SeriesSeason, episode: SeriesEpisode | null, release: ReleaseOption): void { const acquisitionRef = this.acquisitionRef(release); if (!acquisitionRef) return; this.submittingRelease.set(acquisitionRef); this.submissionMessage.set(null); const title = release.title.toLowerCase(); const resolution = title.includes('2160p') ? 2160 : title.includes('1080p') ? 1080 : title.includes('720p') ? 720 : null; const source = title.includes('web-dl') ? 'WEB-DL' : title.includes('webrip') ? 'WEBRip' : title.includes('bluray') ? 'BluRay' : null; const dynamicRange = title.includes('hdr') ? 'HDR' : null; this.downloadsApi.enqueueSeries({ seriesTmdbId: data.series.tmdbId, seriesTitle: data.series.name, posterPath: data.series.posterPath, seasonNumber: season.seasonNumber, episodeNumber: episode?.episodeNumber ?? null, releaseTitle: release.title, acquisitionRef, indexerName: release.indexer, resolutionHeight: resolution, sourceType: source, dynamicRange }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => { this.submissionMessage.set('Download adicionado. Acompanhe o andamento em Downloads.'); this.submittingRelease.set(null); }, error: (error: unknown) => { this.submissionMessage.set(error instanceof HttpErrorResponse ? error.error?.detail || 'Não foi possível iniciar este download. Tente novamente.' : 'Não foi possível iniciar este download. Tente novamente.'); this.submittingRelease.set(null); } }); }
  releaseTargetLabel(): string { const season = this.selectedSeason(); const episode = this.selectedEpisode(); if (!season) return ''; return `${this.details()?.series.name ?? ''} · T${this.padded(season.seasonNumber)}${episode ? `E${this.padded(episode.episodeNumber)}` : ''}`; }
  private openReleaseSearch(data: SeriesDetails, season: SeriesSeason, episode: SeriesEpisode | null): void { this.showReleases.set(true); this.releases.set([]); this.releaseState.set('loading'); this.submissionMessage.set(null); const seasonCode = `S${season.seasonNumber.toString().padStart(2, '0')}`; const episodeCode = episode ? `E${episode.episodeNumber.toString().padStart(2, '0')}` : ''; this.releasesApi.searchSeries(`${data.series.name} ${seasonCode}${episodeCode}`).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: values => { this.releases.set(values.slice(0, 8)); this.releaseState.set('ready'); }, error: () => this.releaseState.set('error') }); }
  selectSeason(seriesId: number, season: SeriesSeason, event?: Event): void { const target = event?.currentTarget; if (target instanceof HTMLElement) target.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' }); if (this.selectedSeason()?.seasonNumber === season.seasonNumber) return; this.selectedSeason.set(season); this.seasonDetails.set(null); this.seasonState.set('loading'); this.api.seasonDetails(seriesId, season.seasonNumber).pipe(catchError(() => of(null)), takeUntilDestroyed(this.destroyRef)).subscribe(value => { this.seasonDetails.set(value); this.seasonState.set('idle'); }); }
  syncSeasonSlider(container: HTMLElement): void { const limit = container.scrollWidth - container.clientWidth; this.seasonSliderValue.set(limit > 0 ? Math.round((container.scrollLeft / limit) * 100) : 0); }
  moveSeasonSlider(container: HTMLElement, event: Event): void { const value = Number((event.target as HTMLInputElement).value); const limit = container.scrollWidth - container.clientWidth; container.scrollTo({ left: (limit * value) / 100, behavior: 'smooth' }); this.seasonSliderValue.set(value); }
  trailerEmbedUrl(trailerUrl: string): SafeResourceUrl { const videoId = new URL(trailerUrl).searchParams.get('v'); return this.sanitizer.bypassSecurityTrustResourceUrl(`https://www.youtube-nocookie.com/embed/${videoId ?? ''}?rel=0&cc_lang_pref=pt-BR&cc_load_policy=1`); }
  openTrailer(event: MouseEvent, trailerUrl: string): void { if (!isDesktopApp()) return; event.preventDefault(); void this.desktopFile.openExternalUrl(trailerUrl); }
  acquisitionRef(release: ReleaseOption): string { return release.magnetUrl || release.downloadUrl || ''; }
  releaseKey(release: ReleaseOption): string { return this.acquisitionRef(release) || release.infoUrl || release.title; }
  size(bytes: number | null): string { return bytes ? `${(bytes / 1_000_000_000).toFixed(1)} GB` : 'Tamanho não informado'; }
  backdropUrl(path: string): string { return tmdbImage(path, 'backdrop-hero'); }
  posterUrl(path: string): string { return tmdbImage(path, 'poster-detail'); }
  profileUrl(path: string): string { return tmdbImage(path, 'cast'); }
  stillUrl(episode: SeriesEpisode): string { return tmdbImage(episode.stillPath, 'episode'); }
  year(value: string | null): string { return value?.slice(0, 4) ?? 'Sem data'; }
  rating(value: number | null): string { return value?.toFixed(1) ?? '—'; }
  padded(value: number): string { return value.toString().padStart(2, '0'); }
}
