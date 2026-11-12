import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { timeout } from 'rxjs';
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
        <button class="icon-button refresh-button" type="button" [disabled]="state() === 'loading'" aria-label="Atualizar status" (click)="load()"><i class="ph ph-arrows-clockwise"></i></button>
      </header>

      <section class="readiness" [class.needs-attention]="!allConfigured()" aria-live="polite">
        <div class="readiness-icon"><i [class]="allConfigured() ? 'ph ph-check-circle' : 'ph ph-warning-circle'"></i></div>
        <div><p>Status do aplicativo</p><h2>{{ readinessTitle() }}</h2><span>{{ readinessDescription() }}</span></div>
        <strong>{{ configuredCount() }}/{{ integrationList.length }} integrações configuradas</strong>
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
            <article><div class="connection-icon"><i [class]="integration.icon"></i></div><div><h3>{{ integration.name }}</h3><p>{{ integration.description }}</p></div><span class="connection-state" [class.ready]="configured(integration.id)" [class.waiting]="!configured(integration.id)"><i [class]="configured(integration.id) ? 'ph ph-check' : 'ph ph-warning'"></i>{{ connectionLabel(integration.id) }}</span></article>
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
  readonly allConfigured = computed(() => this.state() === 'ready' && this.configuredCount() === this.integrationList.length && this.storageDiagnostic()?.sharedDirectoryVerified === true);

  constructor() { this.load(); this.loadStorage(); this.checkStorage(); }
  readinessTitle(): string { return this.allConfigured() ? 'Tudo pronto' : this.state() === 'loading' ? 'Verificando recursos' : 'Alguns recursos precisam de atenção'; }
  readinessDescription(): string { return this.allConfigured() ? 'Você já pode explorar filmes, escolher uma versão e acompanhar seus downloads.' : this.state() === 'error' ? 'Não foi possível confirmar o status agora. Atualize para tentar novamente.' : 'Confira o diagnóstico da pasta. Campos preenchidos nas integrações não comprovam conexão nem armazenamento compartilhado.'; }
  load(): void { this.state.set('loading'); this.systemApi.integrations().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: (integrations) => { this.integrations.set(integrations); this.state.set('ready'); }, error: () => this.state.set('error') }); }
  configured(id: IntegrationStatus['id']): boolean { return this.integrations().some((integration) => integration.id === id && integration.configured); }
  connectionLabel(id: IntegrationStatus['id']): string { return this.state() === 'loading' ? 'Verificando' : this.configured(id) ? 'Disponível' : 'Indisponível'; }
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
