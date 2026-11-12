import { HttpErrorResponse } from '@angular/common/http';
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
      @if (actionError()) { <p class="action-error" role="alert">{{ actionError() }}</p> }
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
              <div class="download-progress"><div><strong>{{ percent(download.progress) }}</strong><span>{{ transferMeta(download) }}</span></div><div class="progress-track"><span [style.width.%]="download.progress * 100"></span></div><div class="download-actions">@if (download.status === 'PAUSED' || download.status === 'ERROR') { <button class="text-action" type="button" [disabled]="pendingIds().has(download.id)" (click)="control(download, 'resume')">Retomar</button> } @else if (canPause(download.status)) { <button class="text-action" type="button" [disabled]="pendingIds().has(download.id)" (click)="control(download, 'pause')">Pausar</button> } <button class="text-action danger-action" type="button" [disabled]="pendingIds().has(download.id)" (click)="control(download, 'cancel')">Cancelar</button></div></div>
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
  readonly actionError = signal('');
  readonly pendingIds = signal(new Set<string>());
  readonly errorMessage = signal('Confira a conexão com o backend e tente novamente.');

  constructor() { this.load(); this.subscribeToUpdates(); }
  load(showLoading = true): void { if (showLoading) this.state.set('loading'); this.downloadsApi.active().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (downloads) => { this.downloads.set(downloads); this.state.set('ready'); }, error: (error: unknown) => { if (error instanceof HttpErrorResponse && error.status === 0) this.errorMessage.set('O backend não está acessível.'); this.state.set('error'); } }); }
  posterUrl(download: DownloadSummary): string { return `https://image.tmdb.org/t/p/w185${download.posterPath}`; }
  percent(progress: number): string { return `${Math.round(progress * 100)}%`; }
  quality(download: DownloadSummary): string { return [download.resolutionHeight ? `${download.resolutionHeight}p` : null, download.sourceType, download.dynamicRange].filter(Boolean).join(' · ') || 'Qualidade não informada'; }
  status(status: string): string { return ({ REQUESTED: 'Preparando', SUBMITTING: 'Enviando', METADATA: 'Lendo metadados', QUEUED: 'Na fila', DOWNLOADING: 'Baixando', PAUSED: 'Pausado', STALLED: 'Sem conexão', CHECKING: 'Verificando', SEEDING: 'Compartilhando', COMPLETED: 'Concluído', ERROR: 'Erro' } as Record<string, string>)[status] ?? status; }
  transferMeta(download: DownloadSummary): string {
    if (download.status === 'SEEDING') return 'Concluído e compartilhando';
    if (download.status === 'COMPLETED') return 'Concluído';
    if (download.status === 'ERROR') return 'Falha na transferência. Tente retomar ou cancele esta tentativa.';
    if (download.status === 'STALLED') return 'Sem fontes disponíveis no momento';
    if (download.status === 'PAUSED') return 'Transferência pausada';
    return [download.downloadSpeedBps ? `${this.formatBytes(download.downloadSpeedBps)}/s` : null, download.etaSeconds ? `restam ${this.formatEta(download.etaSeconds)}` : null].filter(Boolean).join(' · ') || 'Aguardando atualização';
  }
  canPause(status: string): boolean { return ['METADATA', 'QUEUED', 'DOWNLOADING', 'STALLED', 'CHECKING'].includes(status); }
  control(download: DownloadSummary, action: 'pause' | 'resume' | 'cancel'): void {
    if (this.pendingIds().has(download.id)) return;
    if (action === 'cancel' && !confirm(`Cancelar “${download.movieTitle}”? O arquivo já salvo será mantido.`)) return;
    this.actionError.set('');
    this.pendingIds.update(ids => new Set(ids).add(download.id));
    this.downloadsApi.control(download.id, action).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.finishAction(download.id); this.load(false); },
      error: (error: unknown) => {
        this.finishAction(download.id);
        this.actionError.set(error instanceof HttpErrorResponse
          ? error.error?.detail || 'Não foi possível concluir esta ação.'
          : 'Não foi possível concluir esta ação.');
      }
    });
  }
  private finishAction(id: string): void {
    this.pendingIds.update(ids => { const next = new Set(ids); next.delete(id); return next; });
  }
  private subscribeToUpdates(): void {
    const source = new EventSource(apiUrl('/api/downloads/events'));
    source.addEventListener('downloads-changed', () => this.load(false));
    this.destroyRef.onDestroy(() => source.close());
  }
  private formatBytes(bytes: number): string { return bytes >= 1_000_000_000 ? `${(bytes / 1_000_000_000).toFixed(1)} GB` : `${Math.max(1, Math.round(bytes / 1_000_000))} MB`; }
  private formatEta(seconds: number): string { return seconds >= 3600 ? `${Math.ceil(seconds / 3600)}h` : `${Math.max(1, Math.ceil(seconds / 60))}min`; }
}
