import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { EMPTY, Subscription, expand, switchMap, timer, timeout } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { StorageDiagnostic, IntegrationStatus, SystemApiService } from '../core/system-api.service';
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
        <strong>{{ configuredCount() }}/{{ integrationList.length }} conexões verificadas</strong>
      </section>

      <section class="settings-section storage-section" aria-labelledby="storage-title">
        <header><div><h2 id="storage-title">Pasta dos downloads</h2><p>Os próximos filmes serão organizados nesta pasta da sua biblioteca.</p></div></header>
        <div class="storage-panel">
          <label class="field"><span>Nome da pasta</span><div class="folder-input"><i class="ph ph-folder-simple"></i><span>Biblioteca /</span><input [value]="downloadDirectory()" (input)="downloadDirectory.set($any($event.target).value)" placeholder="filmes" /></div></label>
          @if (desktop()) { <button class="choose-folder" type="button" [disabled]="choosingDirectory()" (click)="chooseDownloadDirectory()"><i class="ph ph-folder-open"></i>{{ choosingDirectory() ? 'Abrindo pastas' : 'Escolher pasta' }}</button> }
          <p class="storage-help"><i class="ph ph-info"></i>Use subpastas se quiser, como <code>filmes/4k</code> ou <code>filmes/para-ver</code>.</p>
          <div class="storage-footer"><button class="btn btn-primary" type="button" [disabled]="savingStorage() || checkingStorage()" (click)="saveStorage()">{{ savingStorage() ? 'Salvando' : 'Salvar pasta' }}</button>@if (storageMessage()) { <span class="storage-message"><i class="ph ph-check-circle"></i>{{ storageMessage() }}</span> }</div>
        </div>
        <div class="storage-diagnostic" aria-live="polite">
          <button class="btn" type="button" [disabled]="checkingStorage() || savingStorage()" (click)="checkStorage()">{{ checkingStorage() ? 'Verificando pasta' : 'Diagnosticar pasta salva' }}</button>
          <h3>{{ storageTitle() }}</h3>
          <p>{{ backendHelp() }}</p>
          <p>{{ mappingHelp() }}</p>
          @if (storageDiagnostic(); as diagnostic) { <p>Verificado em {{ diagnostic.checkedAt | date:'dd/MM/yyyy HH:mm:ss' }}. Resultado pontual; teste novamente após alterar volumes ou permissões.</p> }
          <p>O teste usa a pasta salva. Alterar o nome acima só afeta os próximos downloads; os arquivos anteriores permanecem na biblioteca.</p>
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
  readonly storageDiagnostic = signal<StorageDiagnostic | null>(null);
  readonly checkingStorage = signal(false);
  readonly storageCheckFailed = signal(false);
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
  readonly connectionsAvailable = computed(() => this.state() === 'ready' && this.configuredCount() === this.integrationList.length);
  readonly allConfigured = computed(() => this.connectionsAvailable() && this.storageDiagnostic()?.sharedDirectoryVerified === true);
  readonly checking = computed(() => this.integrations().some((item) => item.status === 'checking'));
  readonly refreshDisabled = computed(() => this.state() === 'loading' || (this.state() !== 'error' && this.checking()) || this.integrations().some((item) => item.retryAfter && Date.parse(item.retryAfter) > this.now()));

  constructor() {
    timer(0, 1000).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.now.set(Date.now()));
    this.load(); this.loadStorage(); this.checkStorage();
  }
  readinessTitle(): string { return this.allConfigured() ? 'Tudo pronto' : this.state() === 'error' ? 'Não foi possível verificar' : this.state() === 'loading' || this.checking() ? 'Verificando recursos' : 'Alguns recursos precisam de atenção'; }
  readinessDescription(): string { return this.state() === 'error' ? 'Não foi possível consultar o backend. Teste novamente; isso não indica credenciais recusadas nas integrações.' : this.connectionsAvailable() ? 'As três conexões foram verificadas. Confira abaixo o diagnóstico da pasta; o compartilhamento físico ainda não foi comprovado.' : 'Confira as orientações abaixo. Resultados das conexões valem por um minuto; use Testar novamente para atualizar. A pasta tem diagnóstico separado.'; }
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
  checkStorage(): void {
    if (this.checkingStorage() || this.savingStorage()) return;
    this.storageDiagnostic.set(null); this.storageCheckFailed.set(false); this.checkingStorage.set(true);
    this.systemApi.checkStorage().pipe(timeout(10000), takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result) => { this.storageDiagnostic.set(result); this.checkingStorage.set(false); },
      error: () => { this.storageCheckFailed.set(true); this.checkingStorage.set(false); },
    });
  }
  storageTitle(): string {
    if (this.checkingStorage()) return 'Verificando armazenamento';
    if (this.storageCheckFailed()) return 'Não foi possível diagnosticar';
    return this.storageDiagnostic()?.status === 'blocked' ? 'Armazenamento precisa de atenção' : 'Compartilhamento inconclusivo';
  }
  backendHelp(): string {
    if (this.storageCheckFailed()) return 'Confira o acesso ao backend e tente novamente.';
    switch (this.storageDiagnostic()?.backend) {
      case 'read_write_verified': return 'Leitura e escrita do backend comprovadas na pasta salva. O arquivo exclusivo do teste foi removido.';
      case 'missing_directory': return 'A raiz ou a pasta salva não existe. Confira o volume e crie a pasta no local correto. Nenhum torrent ou registro foi removido.';
      case 'permission_denied': return 'O backend não tem as permissões necessárias. Confira leitura, escrita e acesso às pastas para o usuário do contêiner.';
      case 'outside_root': return 'Destino fora da raiz da biblioteca. Use uma pasta relativa interna e confira links simbólicos.';
      case 'not_directory': return 'O destino não é uma pasta. Confira a raiz e o diretório salvo.';
      case 'configuration_unavailable': return 'Não foi possível ler a configuração salva. Confira o banco e tente novamente.';
      case 'io_error': return 'Não foi possível concluir o teste com segurança. Confira o volume, espaço livre e permissões.';
      default: return 'Aguardando o teste de leitura e escrita da pasta salva.';
    }
  }
  mappingHelp(): string {
    switch (this.storageDiagnostic()?.mapping) {
      case 'mapping_incompatible': return 'Destino do qBittorrent incompatível: a pasta padrão ou temporária está fora de /downloads. Confira essas preferências e os volumes; o teste não altera o motor.';
      case 'compatible_unproven': return 'Destinos do qBittorrent compatíveis com /downloads. Compartilhamento físico inconclusivo: confira se backend e motor montam a mesma pasta do computador. A API não comprova essa equivalência.';
      case 'engine_not_configured': return 'Configure o acesso ao qBittorrent no servidor para comparar os destinos. Compartilhamento inconclusivo.';
      case 'engine_authentication_failed': return 'Confira usuário e senha do qBittorrent no servidor. Compartilhamento inconclusivo.';
      case 'engine_unavailable': return 'Não foi possível consultar as preferências do qBittorrent. Confira endereço, rede e serviço. Compartilhamento inconclusivo.';
      default: return '';
    }
  }
  loadStorage(): void { this.libraryApi.storage().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (storage) => this.downloadDirectory.set(storage.downloadDirectory), error: () => this.storageMessage.set('Não foi possível carregar a pasta atual.') }); }
  chooseDownloadDirectory(): void { this.choosingDirectory.set(true); this.storageMessage.set(null); this.desktopFile.chooseDownloadDirectory().then((directory) => { if (directory) this.downloadDirectory.set(directory); }).catch((error: unknown) => this.storageMessage.set(error instanceof Error ? error.message : 'Não foi possível usar essa pasta.')).finally(() => this.choosingDirectory.set(false)); }
  saveStorage(): void { if (this.checkingStorage() || this.savingStorage()) return; this.storageDiagnostic.set(null); this.savingStorage.set(true); this.storageMessage.set(null); this.libraryApi.updateStorage(this.downloadDirectory()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (storage) => { this.downloadDirectory.set(storage.downloadDirectory); this.storageMessage.set('Pasta salva para os próximos downloads.'); this.savingStorage.set(false); this.checkStorage(); }, error: () => { this.storageMessage.set('Escolha um nome de pasta válido, como filmes/4k.'); this.savingStorage.set(false); } }); }
}
