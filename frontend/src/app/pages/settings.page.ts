import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { EMPTY, Subscription, expand, switchMap, timer, timeout } from 'rxjs';

import { IntegrationStatus, SystemApiService } from '../core/system-api.service';
import { LibraryApiService } from '../core/library-api.service';
import { DesktopFileService } from '../core/desktop-file.service';
import { isDesktopApp } from '../core/api-url';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-settings-page',
  imports: [DatePipe],
  styleUrl: './settings.page.scss',
  template: `
    <div class="page settings-page">
      <header class="settings-header">
        <div><p class="settings-label">Neste computador</p><h1>Configurações</h1><p>Escolha onde os novos downloads serão guardados e confira se o aplicativo está pronto para usar.</p></div>
        <button class="btn refresh-button" type="button" [disabled]="refreshDisabled()" (click)="load(true)"><i class="ph ph-arrows-clockwise"></i> Testar novamente</button>
      </header>

      <section class="readiness" [class.needs-attention]="!allConfigured()" aria-live="polite">
        <div class="readiness-icon"><i [class]="allConfigured() ? 'ph ph-check-circle' : 'ph ph-warning-circle'"></i></div>
        <div><p>Status do aplicativo</p><h2>{{ readinessTitle() }}</h2><span>{{ readinessDescription() }}</span></div>
        <strong>{{ configuredCount() }}/{{ integrationList.length }} prontos</strong>
      </section>

      <section class="settings-section storage-section" aria-labelledby="storage-title">
        <header><div><h2 id="storage-title">Pasta dos downloads</h2><p>Os próximos filmes serão organizados nesta pasta da sua biblioteca.</p></div></header>
        <div class="storage-panel">
          <label class="field"><span>Nome da pasta</span><div class="folder-input"><i class="ph ph-folder-simple"></i><span>Biblioteca /</span><input [value]="downloadDirectory()" (input)="downloadDirectory.set($any($event.target).value)" placeholder="filmes" /></div></label>
          @if (desktop()) { <button class="choose-folder" type="button" [disabled]="choosingDirectory()" (click)="chooseDownloadDirectory()"><i class="ph ph-folder-open"></i>{{ choosingDirectory() ? 'Abrindo pastas' : 'Escolher pasta' }}</button> }
          <p class="storage-help"><i class="ph ph-info"></i>Use subpastas se quiser, como <code>filmes/4k</code> ou <code>filmes/para-ver</code>.</p>
          <div class="storage-footer"><button class="btn btn-primary" type="button" [disabled]="savingStorage()" (click)="saveStorage()">{{ savingStorage() ? 'Salvando' : 'Salvar pasta' }}</button>@if (storageMessage()) { <span class="storage-message"><i class="ph ph-check-circle"></i>{{ storageMessage() }}</span> }</div>
        </div>
      </section>

      <section class="settings-section connections-section" aria-labelledby="connections-title">
        <header><div><h2 id="connections-title">O que está disponível</h2><p>Esses recursos permitem buscar filmes, encontrar opções e acompanhar seus downloads.</p></div></header>
        <div class="connection-list">
          @for (integration of integrationList; track integration.id) {
            <article><div class="connection-icon"><i [class]="integration.icon"></i></div><div><h3>{{ integration.name }}</h3><p>{{ integration.description }}</p><p class="diagnostic-help">{{ connectionHelp(integration.id) }}</p><p class="checked-at">Última verificação: {{ (result(integration.id)?.checkedAt | date:'dd/MM/yyyy HH:mm:ss') || 'ainda não realizada' }}</p></div><span class="connection-state" [class.ready]="configured(integration.id)" [class.waiting]="!configured(integration.id)"><i [class]="configured(integration.id) ? 'ph ph-check' : 'ph ph-warning'"></i>{{ connectionLabel(integration.id) }}</span></article>
          }
        </div>
      </section>
    </div>
  `,
})
export class SettingsPage {
  private readonly systemApi = inject(SystemApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly libraryApi = inject(LibraryApiService);
  private readonly desktopFile = inject(DesktopFileService);
  private request?: Subscription;
  private generation = 0;
  readonly now = signal(Date.now());
  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly integrations = signal<IntegrationStatus[]>([]);
  readonly downloadDirectory = signal('incoming');
  readonly savingStorage = signal(false);
  readonly storageMessage = signal<string | null>(null);
  readonly desktop = signal(isDesktopApp());
  readonly choosingDirectory = signal(false);
  readonly integrationList = [
    { id: 'tmdb' as const, name: 'Catálogo de filmes', description: 'Capas, sinopses, notas e detalhes.', icon: 'ph ph-film-strip' },
    { id: 'prowlarr' as const, name: 'Opções de download', description: 'Encontra versões disponíveis para os filmes.', icon: 'ph ph-binoculars' },
    { id: 'qbittorrent' as const, name: 'Downloads', description: 'Inicia e acompanha os arquivos que você escolher.', icon: 'ph ph-download-simple' },
  ];
  readonly configuredCount = computed(() => this.integrationList.filter((item) => this.configured(item.id)).length);
  readonly allConfigured = computed(() => this.state() === 'ready' && this.configuredCount() === this.integrationList.length);

  readonly checking = computed(() => this.integrations().some((item) => item.status === 'checking'));
  readonly refreshDisabled = computed(() => this.state() === 'loading' || (this.state() !== 'error' && this.checking()) || this.integrations().some((item) => item.retryAfter && Date.parse(item.retryAfter) > this.now()));

  constructor() {
    timer(0, 1000).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.now.set(Date.now()));
    this.load(); this.loadStorage();
  }
  readinessTitle(): string { return this.allConfigured() ? 'Tudo pronto' : this.state() === 'error' ? 'Não foi possível verificar' : this.state() === 'loading' || this.checking() ? 'Verificando recursos' : 'Alguns recursos precisam de atenção'; }
  readinessDescription(): string { return this.allConfigured() ? 'As três conexões foram verificadas. Isso não valida fontes de busca nem a pasta de mídia.' : this.state() === 'error' ? 'Não foi possível consultar o backend. Teste novamente; isso não indica credenciais recusadas nas integrações.' : 'Confira as orientações abaixo. Resultados valem por um minuto; use Testar novamente para atualizar.'; }
  load(refresh = false): void {
    this.request?.unsubscribe();
    const generation = ++this.generation;
    const deadline = Date.now() + 8000;
    this.state.set('loading');
    this.request = (refresh ? this.systemApi.checkIntegrations() : this.systemApi.integrations()).pipe(
      timeout(6000),
      expand((items) => items.some((item) => item.status === 'checking')
        ? timer(500).pipe(switchMap(() => this.systemApi.integrations()), timeout(Math.max(1, deadline - Date.now()))) : EMPTY),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: (items) => { if (generation === this.generation) { this.integrations.set(items); this.state.set('ready'); } },
      error: () => { if (generation === this.generation) this.state.set('error'); },
    });
  }
  result(id: IntegrationStatus['id']): IntegrationStatus | undefined { return this.integrations().find((item) => item.id === id); }
  stale(id: IntegrationStatus['id']): boolean { const expiry = this.result(id)?.expiresAt; return !!expiry && Date.parse(expiry) <= this.now(); }
  configured(id: IntegrationStatus['id']): boolean { return this.state() === 'ready' && this.result(id)?.status === 'available' && !this.stale(id); }
  connectionLabel(id: IntegrationStatus['id']): string {
    if (this.state() === 'error') return 'Não confirmado';
    if (this.state() === 'loading' || this.result(id)?.status === 'checking') return 'Verificando';
    if (this.stale(id)) return 'Desatualizado';
    switch (this.result(id)?.status) {
      case 'available': return 'Disponível';
      case 'authentication_rejected': return 'Autenticação recusada';
      case 'not_configured': return 'Não configurado';
      case 'unavailable': return 'Indisponível';
      default: return 'Desconhecido';
    }
  }
  connectionHelp(id: IntegrationStatus['id']): string {
    if (this.state() === 'error') return 'Confira se o backend está em execução e se a interface consegue acessá-lo. Depois teste novamente.';
    if (this.state() === 'loading' || this.result(id)?.status === 'checking') return 'Consulta autenticada em andamento, sem iniciar downloads.';
    if (this.stale(id)) return 'O resultado venceu. Teste novamente para confirmar a conexão.';
    const service = id === 'tmdb' ? 'TMDB' : id === 'prowlarr' ? 'Prowlarr' : 'qBittorrent';
    switch (this.result(id)?.reason) {
      case 'ok': return 'Consulta autenticada concluída com sucesso.';
      case 'missing_configuration': return `Preencha ${id === 'tmdb' ? 'o token do TMDB' : id === 'prowlarr' ? 'o endereço e a chave de API do Prowlarr' : 'o endereço, usuário e senha do qBittorrent'} na configuração do servidor e reinicie o backend.`;
      case 'authentication_rejected': return `Confira ${id === 'qbittorrent' ? 'usuário e senha' : id === 'tmdb' ? 'o token de acesso' : 'a chave de API'} do ${service} na configuração do servidor. Confira também bloqueios de acesso no serviço.`;
      case 'invalid_configuration': return `Corrija o endereço do ${service}: use http ou https, sem credenciais, parâmetros ou fragmentos na URL.`;
      case 'timeout': return `O ${service} não respondeu dentro do limite. Confira rede e carga do serviço e teste novamente.`;
      case 'connection_failed': return `Não foi possível alcançar o ${service}. Confira se está ligado, o endereço, a porta, a rede e o certificado HTTPS.`;
      case 'invalid_response': return `O endereço respondeu em um formato inesperado. Confira se aponta para a API do ${service}, incluindo eventual subpasta.`;
      case 'http_error': return `O ${service} devolveu um erro HTTP ou redirecionamento. Confira o endereço da API e o proxy do serviço.`;
      default: return 'Ainda não há um resultado conhecido. Teste novamente.';
    }
  }
  loadStorage(): void { this.libraryApi.storage().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (storage) => this.downloadDirectory.set(storage.downloadDirectory), error: () => this.storageMessage.set('Não foi possível carregar a pasta atual.') }); }
  chooseDownloadDirectory(): void { this.choosingDirectory.set(true); this.storageMessage.set(null); this.desktopFile.chooseDownloadDirectory().then((directory) => { if (directory) this.downloadDirectory.set(directory); }).catch((error: unknown) => this.storageMessage.set(error instanceof Error ? error.message : 'Não foi possível usar essa pasta.')).finally(() => this.choosingDirectory.set(false)); }
  saveStorage(): void { this.savingStorage.set(true); this.storageMessage.set(null); this.libraryApi.updateStorage(this.downloadDirectory()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (storage) => { this.downloadDirectory.set(storage.downloadDirectory); this.storageMessage.set('Pasta salva para os próximos downloads.'); this.savingStorage.set(false); }, error: () => { this.storageMessage.set('Escolha um nome de pasta válido, como filmes/4k.'); this.savingStorage.set(false); } }); }
}
