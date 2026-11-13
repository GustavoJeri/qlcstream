import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { tmdbImage } from '../core/tmdb-image';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subject, catchError, map, merge, of, switchMap, timer } from 'rxjs';

import { DownloadsApiService, DownloadQueue, DownloadSummary } from '../core/downloads-api.service';
import { apiUrl } from '../core/api-url';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-downloads-page',
  imports: [DatePipe, RouterLink],
  styleUrl: './downloads.page.scss',
  template: `
    <div class="page downloads-page">
      <header class="page-heading"><div><h1>Downloads</h1><p>Acompanhe transferências e organize o que entra na biblioteca.</p></div><button class="icon-button" type="button" aria-label="Atualizar downloads" (click)="load()"><i class="ph ph-arrows-clockwise"></i></button></header>
      @if (state() === 'ready' && stale()) {
        <p class="queue-notice" role="status">
          <strong>Dados desatualizados.</strong> {{ backendUnavailable() ? 'O backend não está acessível.' : 'O qBittorrent não está respondendo.' }}
          @if (lastSuccessfulAt(); as confirmed) {
            Última sincronização {{ confirmed | date:'dd/MM/yyyy HH:mm:ss' }}.
          } @else { Aguardando a primeira sincronização. }
        </p>
      } @else if (lastSuccessfulAt(); as confirmed) {
        <p class="queue-timestamp">Última sincronização {{ confirmed | date:'dd/MM/yyyy HH:mm:ss' }}.</p>
      } @else if (state() === 'ready' && queue()?.sync?.state === 'waiting') {
        <p class="queue-notice" role="status">Aguardando a primeira sincronização com o qBittorrent.</p>
      }
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
              <div class="download-progress">
                <div><strong>{{ percent(download.progress) }}</strong><span>{{ transferMeta(download) }}</span></div>
                <div class="progress-track"><span [style.width.%]="download.progress * 100"></span></div>
                <div class="download-actions">
                  @let disabled = !engineAvailable() || pendingIds().has(download.id);
                  @if (download.canRetry) {
                    <button class="text-action retry-action" type="button" [disabled]="disabled" (click)="control(download, 'retry')">
                      <i class="ph ph-arrow-clockwise"></i>Tentar novamente
                    </button>
                  } @else if (download.status === 'PAUSED') {
                    <button class="text-action" type="button" [disabled]="disabled" (click)="control(download, 'resume')">Retomar</button>
                  } @else if (canPause(download.status)) {
                    <button class="text-action" type="button" [disabled]="disabled" (click)="control(download, 'pause')">Pausar</button>
                  }
                  @if (canPrioritize(download.status)) {
                    <span class="priority-actions" aria-label="Prioridade">
                      <button type="button" [disabled]="disabled" aria-label="Aumentar prioridade" (click)="control(download, 'priority_up')"><i class="ph ph-caret-up"></i></button>
                      <button type="button" [disabled]="disabled" aria-label="Diminuir prioridade" (click)="control(download, 'priority_down')"><i class="ph ph-caret-down"></i></button>
                    </span>
                  }
                  <button class="text-action danger-action" type="button" [disabled]="disabled" (click)="control(download, 'cancel')">Cancelar transferência</button>
                </div>
              </div>
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
  private readonly refresh = new Subject<void>();
  readonly queue = signal<DownloadQueue | null>(null);
  readonly downloads = computed(() => this.queue()?.items ?? []);
  readonly backendUnavailable = signal(false);
  readonly lastSuccessfulAt = computed(() => this.queue()?.sync.lastSuccessfulAt);
  readonly stale = computed(() => this.backendUnavailable() || this.queue()?.sync.state === 'unavailable');
  readonly engineAvailable = computed(() => !this.stale() && this.queue()?.sync.state === 'available');
  readonly actionError = signal('');
  readonly pendingIds = signal(new Set<string>());
  readonly errorMessage = signal('Confira a conexão com o backend e tente novamente.');
  constructor() {
    merge(this.refresh, timer(0, 10_000)).pipe(
      switchMap(() => this.downloadsApi.queue().pipe(
        map((queue) => ({ queue, error: null })),
        catchError((error: unknown) => of({ queue: null, error })),
      )),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(({ queue, error }) => {
      if (queue) {
        this.queue.set(queue);
        this.backendUnavailable.set(false);
        this.state.set('ready');
      } else {
        this.backendUnavailable.set(true);
        this.errorMessage.set(error instanceof HttpErrorResponse && error.status === 0
          ? 'O backend não está acessível.'
          : 'Confira a conexão com o backend e tente novamente.');
        this.state.set(this.queue() ? 'ready' : 'error');
      }
    });
    this.subscribeToUpdates();
  }

  load(): void { this.refresh.next(); }

  posterUrl(download: DownloadSummary): string { return tmdbImage(download.posterPath, 'thumbnail'); }
  percent(progress: number): string { return `${Math.round(progress * 100)}%`; }
  quality(download: DownloadSummary): string { return [download.resolutionHeight ? `${download.resolutionHeight}p` : null, download.sourceType, download.dynamicRange].filter(Boolean).join(' · ') || 'Qualidade não informada'; }
  status(status: string): string { return ({ REQUESTED: 'Preparando', SUBMITTING: 'Enviando', METADATA: 'Lendo metadados', QUEUED: 'Na fila', DOWNLOADING: 'Baixando', PAUSED: 'Pausado', STALLED: 'Sem atividade', CHECKING: 'Verificando', SEEDING: 'Compartilhando', COMPLETED: 'Concluído', ERROR: 'Erro' } as Record<string, string>)[status] ?? status; }
  transferMeta(download: DownloadSummary): string {
    if (download.engineState === 'missing') return 'Transferência ausente no qBittorrent. Cancele e escolha uma fonte novamente.';
    if (download.status === 'ERROR') {
      if (download.engineState === 'missingFiles') return 'Arquivo ausente. Verifique a pasta antes de tentar novamente.';
      return download.canRetry ? 'Falha na transferência. Tente novamente.' : 'Falha na transferência. Confira o qBittorrent.';
    }
    const description = ({
      SEEDING: 'Concluído e compartilhando',
      COMPLETED: 'Concluído',
      METADATA: 'Buscando metadados',
      QUEUED: 'Aguardando na fila',
      CHECKING: 'Verificando os arquivos',
      STALLED: 'Aguardando fontes disponíveis',
      PAUSED: 'Transferência pausada',
    } as Record<string, string>)[download.status];
    if (description) return description;
    return [
      download.downloadSpeedBps > 0 ? `${this.formatBytes(download.downloadSpeedBps)}/s` : null,
      download.etaSeconds && download.etaSeconds > 0 && download.etaSeconds < 8_640_000
        ? `restam ${this.formatEta(download.etaSeconds)}` : null,
    ].filter(Boolean).join(' · ') || 'Aguardando atualização';
  }
  canPause(status: string): boolean { return ['METADATA', 'QUEUED', 'DOWNLOADING', 'STALLED', 'CHECKING'].includes(status); }
  canPrioritize(status: string): boolean { return this.queue()?.sync.queueingEnabled === true
      && ['METADATA', 'QUEUED', 'DOWNLOADING', 'STALLED', 'PAUSED'].includes(status); }
  control(download: DownloadSummary, action: 'pause' | 'resume' | 'retry' | 'priority_up' | 'priority_down' | 'cancel'): void {
    if (!this.engineAvailable() || this.pendingIds().has(download.id)) return;
    if (action === 'cancel' && !confirm(`Cancelar a transferência de “${download.movieTitle}”? Os arquivos já baixados serão mantidos.`)) return;
    this.actionError.set('');
    this.pendingIds.update(ids => new Set(ids).add(download.id));
    this.downloadsApi.control(download.id, action).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.finishAction(download.id); this.load(); },
      error: (error: unknown) => {
        this.finishAction(download.id);
        this.actionError.set(error instanceof HttpErrorResponse
          ? error.error?.detail || error.error?.message || 'Não foi possível concluir esta ação.'
          : 'Não foi possível concluir esta ação.');
      }
    });
  }
  private finishAction(id: string): void {
    this.pendingIds.update(ids => { const next = new Set(ids); next.delete(id); return next; });
  }
  private subscribeToUpdates(): void {
    const source = new EventSource(apiUrl('/api/downloads/events'));
    source.addEventListener('downloads-changed', () => this.load());
    source.addEventListener('connected', () => this.load());
    this.destroyRef.onDestroy(() => source.close());
  }
  private formatBytes(bytes: number): string {
    if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
    if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`;
    if (bytes >= 1_000) return `${(bytes / 1_000).toFixed(1)} kB`;
    return `${bytes} B`;
  }
  private formatEta(seconds: number): string { return seconds >= 3600 ? `${Math.ceil(seconds / 3600)}h` : `${Math.max(1, Math.ceil(seconds / 60))}min`; }
}
