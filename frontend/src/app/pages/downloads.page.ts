import { HttpErrorResponse } from '@angular/common/http';
import { tmdbImage } from '../core/tmdb-image';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { DownloadsApiService, DownloadSummary } from '../core/downloads-api.service';
import { apiUrl } from '../core/api-url';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-downloads-page',
  imports: [RouterLink],
  styleUrl: './downloads.page.scss',
  template: `
    <div class="page downloads-page">
      <header class="page-heading"><div><h1>Downloads</h1><p>Acompanhe transferências e organize o que entra na biblioteca.</p></div><button class="icon-button" type="button" aria-label="Atualizar downloads" (click)="load()"><i class="ph ph-arrows-clockwise"></i></button></header>
      @if (state() === 'loading') {
        <section class="downloads-state" aria-live="polite"><i class="ph ph-spinner-gap"></i><p>Consultando transferências.</p></section>
      } @else if (state() === 'error') {
        <section class="downloads-state downloads-error"><i class="ph ph-warning-circle"></i><div><strong>Não foi possível consultar os downloads.</strong><p>{{ errorMessage() }}</p><button class="btn btn-quiet" type="button" (click)="load()">Tentar novamente</button></div></section>
      } @else if (downloads().length) {
        <section class="download-list" aria-label="Downloads ativos">
          @for (download of downloads(); track download.id) {
            <article class="download-row">
              <a [routerLink]="[download.mediaType === 'SERIES' ? '/series' : '/catalog', download.movieTmdbId]" class="download-poster" [attr.aria-label]="'Abrir ' + download.movieTitle">
                @if (download.posterPath) { <img [src]="posterUrl(download)" [alt]="'Pôster de ' + download.movieTitle" loading="lazy" decoding="async" /> } @else { <i class="ph" [class.ph-television]="download.mediaType === 'SERIES'" [class.ph-film-strip]="download.mediaType !== 'SERIES'"></i> }
              </a>
              <div class="download-info"><a [routerLink]="[download.mediaType === 'SERIES' ? '/series' : '/catalog', download.movieTmdbId]">{{ download.movieTitle }}</a><p>{{ download.releaseTitle }}</p><div class="download-tags"><span>{{ quality(download) }}</span><span>{{ status(download.status) }}</span></div></div>
              <div class="download-progress"><div><strong>{{ percent(download.progress) }}</strong><span>{{ transferMeta(download) }}</span></div><div class="progress-track"><span [style.width.%]="download.progress * 100"></span></div><div class="download-actions">@if (download.status === 'ERROR') { <button class="text-action retry-action" type="button" [disabled]="controllingId() === download.id" (click)="control(download, 'retry')"><i class="ph ph-arrow-clockwise"></i>Tentar novamente</button> } @else if (download.status === 'PAUSED') { <button class="text-action" type="button" [disabled]="controllingId() === download.id" (click)="control(download, 'resume')">Retomar</button> } @else if (canPause(download.status)) { <button class="text-action" type="button" [disabled]="controllingId() === download.id" (click)="control(download, 'pause')">Pausar</button> } @if (canPrioritize(download.status)) { <span class="priority-actions" aria-label="Prioridade"><button type="button" [disabled]="controllingId() === download.id" aria-label="Aumentar prioridade" (click)="control(download, 'priority_up')"><i class="ph ph-caret-up"></i></button><button type="button" [disabled]="controllingId() === download.id" aria-label="Diminuir prioridade" (click)="control(download, 'priority_down')"><i class="ph ph-caret-down"></i></button></span> } <button class="text-action danger-action" type="button" [disabled]="controllingId() === download.id" (click)="control(download, 'cancel')">Cancelar</button></div></div>
            </article>
          }
        </section>
      } @else {
        <section class="empty-downloads" aria-labelledby="empty-downloads-title">
          <i class="ph ph-download-simple" aria-hidden="true"></i>
          <div><h2 id="empty-downloads-title">Nenhum download em andamento</h2><p>Quando uma opção for enviada ao motor de downloads, o progresso aparecerá aqui.</p><a routerLink="/catalog" class="btn btn-primary">Explorar catálogo</a></div>
        </section>
      }
    </div>
  `,
})
export class DownloadsPage {
  private readonly downloadsApi = inject(DownloadsApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly downloads = signal<DownloadSummary[]>([]);
  readonly errorMessage = signal('Confira a conexão com o backend e tente novamente.');
  readonly controllingId = signal<string | null>(null);

  constructor() { this.load(); this.subscribeToUpdates(); }
  load(showLoading = true): void { if (showLoading) this.state.set('loading'); this.downloadsApi.active().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (downloads) => { this.downloads.set(downloads); this.state.set('ready'); }, error: (error: unknown) => { if (error instanceof HttpErrorResponse && error.status === 0) this.errorMessage.set('O backend não está acessível.'); this.state.set('error'); } }); }
  posterUrl(download: DownloadSummary): string { return tmdbImage(download.posterPath, 'thumbnail'); }
  percent(progress: number): string { return `${Math.round(progress * 100)}%`; }
  quality(download: DownloadSummary): string { return [download.resolutionHeight ? `${download.resolutionHeight}p` : null, download.sourceType, download.dynamicRange].filter(Boolean).join(' · ') || 'Qualidade não informada'; }
  status(status: string): string { return ({ REQUESTED: 'Preparando', SUBMITTING: 'Enviando', METADATA: 'Lendo metadados', QUEUED: 'Na fila', DOWNLOADING: 'Baixando', PAUSED: 'Pausado', STALLED: 'Sem conexão', CHECKING: 'Verificando', SEEDING: 'Compartilhando', COMPLETED: 'Concluído', ERROR: 'Erro' } as Record<string, string>)[status] ?? status; }
  transferMeta(download: DownloadSummary): string {
    if (download.status === 'SEEDING') return 'Concluído e compartilhando';
    if (download.status === 'COMPLETED') return 'Concluído';
    return [download.downloadSpeedBps ? `${this.formatBytes(download.downloadSpeedBps)}/s` : null, download.etaSeconds ? `restam ${this.formatEta(download.etaSeconds)}` : null].filter(Boolean).join(' · ') || 'Aguardando atualização';
  }
  canPause(status: string): boolean { return ['METADATA', 'QUEUED', 'DOWNLOADING', 'STALLED', 'CHECKING'].includes(status); }
  canPrioritize(status: string): boolean { return ['METADATA', 'QUEUED', 'DOWNLOADING', 'STALLED', 'PAUSED'].includes(status); }
  control(download: DownloadSummary, action: 'pause' | 'resume' | 'retry' | 'priority_up' | 'priority_down' | 'cancel'): void {
    if (this.controllingId() === download.id) return;
    if (action === 'cancel' && !confirm(`Cancelar “${download.movieTitle}”? O arquivo já salvo será mantido.`)) return;
    this.controllingId.set(download.id);
    this.downloadsApi.control(download.id, action).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: () => { this.controllingId.set(null); this.load(false); }, error: (error: unknown) => { this.controllingId.set(null); this.errorMessage.set(error instanceof HttpErrorResponse && error.error?.message ? error.error.message : 'Não foi possível concluir esta ação.'); } });
  }
  private subscribeToUpdates(): void {
    const source = new EventSource(apiUrl('/api/downloads/events'));
    source.addEventListener('downloads-changed', () => this.load(false));
    this.destroyRef.onDestroy(() => source.close());
  }
  private formatBytes(bytes: number): string { return bytes >= 1_000_000_000 ? `${(bytes / 1_000_000_000).toFixed(1)} GB` : `${Math.max(1, Math.round(bytes / 1_000_000))} MB`; }
  private formatEta(seconds: number): string { return seconds >= 3600 ? `${Math.ceil(seconds / 3600)}h` : `${Math.max(1, Math.ceil(seconds / 60))}min`; }
}
