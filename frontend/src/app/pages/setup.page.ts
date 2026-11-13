import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin, switchMap, timeout } from 'rxjs';
import { IntegrationCandidate, IntegrationId, IntegrationSettingsState, StorageDiagnostic, SystemApiService } from '../core/system-api.service';
import { LibraryApiService } from '../core/library-api.service';

@Component({
  selector: 'app-setup-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink],
  styleUrl: './setup.page.scss',
  template: `
    <div class="page setup">
      <header><p>Primeira configuração</p><h1>Prepare o QLC Stream</h1><p>Configure o catálogo agora e complete os downloads quando quiser. As escolhas salvas são retomadas pelo servidor.</p></header>
      @if (loading()) { <p role="status">Consultando configurações existentes…</p> }
      @else if (loadFailed()) { <p role="alert">Não foi possível consultar o servidor. Confira o backend e tente novamente.</p><button class="btn" (click)="load()">Tentar novamente</button> }
      @else {
        <nav aria-label="Etapas da configuração">
          @for (title of titles; track title; let i = $index) { <button class="btn" [disabled]="busy()" [attr.aria-current]="step() === i ? 'step' : null" (click)="select(i)">{{ i + 1 }}. {{ title }}</button> }
        </nav>
        @if (!keyReady()) {
          <section aria-labelledby="key-title"><h2 id="key-title">Proteja as credenciais neste servidor</h2><p>Autorize a criação da chave para guardar credenciais protegidas. Nenhum segredo será criado no navegador. Guarde uma cópia segura da chave junto ao seu plano de backup.</p><button class="btn btn-primary" [disabled]="busy()" (click)="provision()">Criar chave de proteção</button><p>A chave nunca é substituída por esta ação. Se o servidor informar falha, confira as permissões da pasta protegida ou restaure a chave original. <a href="https://github.com/GustavoJeri/qlcstream/blob/develop/docs/configuracao-integracoes.md" target="_blank" rel="noopener noreferrer">Como fazer backup e recuperar</a></p></section>
        }
        @if (step() < 3) {
          <section aria-labelledby="integration-title"><h2 id="integration-title">{{ titles[step()] }}</h2><p>{{ instructions[step()] }}</p>
            <a [href]="helpLinks[step()]" target="_blank" rel="noopener noreferrer">Onde obter as credenciais</a>
            <p>{{ current()?.configured ? 'Já existe uma configuração. Você pode mantê-la e continuar.' : 'Esta integração ainda precisa de configuração.' }} Fonte atual: {{ current()?.source === 'environment' ? 'ambiente da instalação' : 'aplicativo' }}.</p>
            @if (step() === 1) { <p>Escolha e habilite os indexadores na interface do Prowlarr. O assistente não cadastra contas nem escolhe fontes por você.</p> }
            <label>Endereço do serviço<input [ngModel]="baseUrl" (ngModelChange)="baseUrl = $event; edited()" [disabled]="busy()" spellcheck="false" autocomplete="off" /></label>
            <p>Em Docker, use o nome interno do serviço. Fora do Docker, use o endereço acessível pelo backend, não necessariamente pelo navegador.</p>
            @if (step() === 2) { <label>Usuário<input [ngModel]="username" (ngModelChange)="username = $event; edited()" [disabled]="busy()" autocomplete="off" [placeholder]="current()?.usernamePresent ? 'Omitir preserva o usuário salvo' : 'Usuário do qBittorrent'" /></label> }
            <label>{{ step() === 0 ? 'Token de leitura do TMDB' : step() === 1 ? 'Chave da API do Prowlarr' : 'Senha do qBittorrent' }}<input type="password" [ngModel]="secret" (ngModelChange)="secret = $event; edited()" [disabled]="busy()" autocomplete="new-password" [placeholder]="current()?.secretPresent ? 'Omitir preserva o segredo salvo' : 'Informe a credencial'" /></label>
            <p>Segredos salvos não são exibidos. Deixar este campo vazio preserva o valor atual. Um novo valor substitui o anterior somente ao salvar.</p>
            @if (current()?.source === 'environment') { <label class="confirmation"><input type="checkbox" [(ngModel)]="adopt" [disabled]="busy()" />Quero guardar esta integração pelo aplicativo. O ambiente original será preservado, mas deixará de ter precedência nesta integração.</label> }
            <div class="actions"><button class="btn" [disabled]="busy()" (click)="test()">Testar sem salvar</button><button class="btn btn-primary" [disabled]="busy() || !keyReady() || (current()?.source === 'environment' && !adopt)" (click)="save()">Salvar e continuar</button><button class="btn" [disabled]="busy()" (click)="select(step() + 1)">{{ current()?.configured ? 'Manter e continuar' : 'Configurar depois' }}</button></div>
          </section>
        } @else {
          <section aria-labelledby="folder-title"><h2 id="folder-title">Pasta dos downloads</h2><label>Subpasta da biblioteca<input [(ngModel)]="directory" [disabled]="busy()" placeholder="incoming" /></label><p>Use uma pasta existente dentro da biblioteca montada no servidor. Esta ação não move arquivos nem cria diretórios. Se ela não existir, peça ao administrador da instalação para prepará-la.</p><button class="btn" [disabled]="busy()" (click)="saveFolder()">Salvar e diagnosticar pasta</button><a class="btn" routerLink="/settings">Revisar os diagnósticos</a>
            @if (storage(); as result) { <p role="status">{{ folderHelp(result) }}</p> }
          </section>
        }
        @if (busy()) { <p role="status">Aguarde a conclusão da operação…</p> }
        @if (message()) { <p role="status" aria-live="polite">{{ message() }}</p> }
        <footer><button class="btn" [disabled]="busy() || step() === 0" (click)="select(step() - 1)">Voltar</button><a class="btn btn-primary" routerLink="/catalog">{{ catalogReady() ? 'Usar o catálogo' : 'Explorar o aplicativo e configurar depois' }}</a><p>Busca e downloads podem ser configurados depois. Segredos digitados e não salvos são descartados ao sair ou trocar de etapa.</p></footer>
      }
    </div>
  `,
})
export class SetupPage {
  private readonly api = inject(SystemApiService);
  private readonly library = inject(LibraryApiService);
  private readonly destroy = inject(DestroyRef);
  readonly titles = ['Catálogo', 'Busca de fontes', 'Motor de downloads', 'Pasta'];
  readonly instructions = ['Obtenha o token de leitura da API do TMDB na sua conta. Ele permite consultar capas, sinopses e detalhes.', 'No Prowlarr, conclua a configuração de acesso e copie a chave em Settings → General.', 'No qBittorrent, configure o acesso à interface Web e informe o mesmo usuário e senha.'];
  readonly helpLinks = ['https://www.themoviedb.org/settings/api', 'https://prowlarr.com/docs/', 'https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-(qBittorrent-5.0)'];
  readonly ids: IntegrationId[] = ['tmdb', 'prowlarr', 'qbittorrent'];
  readonly rows = signal<IntegrationSettingsState[]>([]);
  readonly step = signal(0);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly busy = signal(false);
  readonly keyReady = signal(false);
  readonly message = signal('');
  readonly storage = signal<StorageDiagnostic | null>(null);
  baseUrl = '';
  username = '';
  secret = '';
  directory = '';
  adopt = false;

  constructor() { this.load(); this.destroy.onDestroy(() => { this.secret = ''; this.username = ''; }); }
  current(): IntegrationSettingsState | undefined { return this.rows().find(row => row.id === this.ids[this.step()]); }
  catalogReady(): boolean { return this.rows().some(row => row.id === 'tmdb' && row.configured); }
  load(): void {
    this.loading.set(true); this.loadFailed.set(false);
    forkJoin({ rows: this.api.integrationSettings(), key: this.api.integrationKey(), folder: this.library.storage() }).pipe(timeout(8000), takeUntilDestroyed(this.destroy)).subscribe({
      next: ({ rows, key, folder }) => { this.rows.set(rows); this.keyReady.set(key.ready); this.directory = folder.downloadDirectory; this.loading.set(false); const pending = this.ids.findIndex(id => !rows.find(row => row.id === id)?.configured); this.select(pending < 0 ? 3 : pending); },
      error: () => { this.loading.set(false); this.loadFailed.set(true); },
    });
  }
  select(index: number): void {
    if (this.busy()) return;
    this.step.set(Math.max(0, Math.min(index, 3))); this.secret = ''; this.username = ''; this.adopt = false; this.message.set('');
    this.baseUrl = this.current()?.baseUrl || ['https://api.themoviedb.org/3', 'http://prowlarr:9696', 'http://qbittorrent:8080'][this.step()] || '';
  }
  edited(): void { this.message.set('Valores alterados. Teste novamente antes de salvar se desejar confirmar a conexão.'); }
  private candidate(): IntegrationCandidate {
    return { baseUrl: this.baseUrl, ...(this.username ? { username: this.username } : {}), ...(this.secret ? { secret: { action: 'replace' as const, value: this.secret } } : {}) };
  }
  provision(): void {
    if (this.busy()) return;
    this.busy.set(true); this.message.set('');
    this.api.provisionIntegrationKey().pipe(timeout(8000), takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({ next: result => { this.keyReady.set(result.ready); this.message.set('Chave protegida criada ou já existente. Faça uma cópia segura no servidor antes de depender destas configurações.'); }, error: () => this.message.set('Não foi possível preparar a proteção. Confira as permissões da pasta protegida ou restaure a chave original; nenhuma chave existente será substituída.') });
  }
  test(): void {
    if (this.busy() || this.step() > 2) return;
    this.busy.set(true); this.message.set('');
    this.api.testIntegration(this.ids[this.step()], this.candidate()).pipe(timeout(8000), takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({ next: result => this.message.set(this.reason(result.reason)), error: () => this.message.set('O teste não foi concluído. Confira o backend, o endereço e os campos. Nada foi salvo.') });
  }
  save(): void {
    if (this.busy() || this.step() > 2 || !this.keyReady() || (this.current()?.source === 'environment' && !this.adopt)) return;
    this.busy.set(true); this.message.set('');
    this.api.updateIntegration(this.ids[this.step()], { ...this.candidate(), source: 'application' }).pipe(timeout(8000), takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      next: row => { this.rows.update(rows => rows.map(old => old.id === row.id ? row : old)); this.secret = ''; this.busy.set(false); this.select(this.step() + 1); this.message.set('Configuração salva no servidor. Salvar não confirma disponibilidade; consulte os diagnósticos para verificar novamente.'); },
      error: () => this.message.set('Não foi possível confirmar a gravação. Seus campos foram preservados. Confira a proteção, o backend e os valores antes de tentar novamente.'),
    });
  }
  saveFolder(): void {
    if (this.busy()) return;
    this.busy.set(true); this.storage.set(null); this.message.set('');
    this.library.updateStorage(this.directory).pipe(switchMap(saved => { this.directory = saved.downloadDirectory; return this.api.checkStorage(); }), timeout(12000), takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({ next: result => this.storage.set(result), error: () => this.message.set('Não foi possível confirmar a pasta e seu diagnóstico. Confira o destino salvo em Configurações antes de tentar novamente.') });
  }
  reason(reason: string): string {
    return ({ ok: 'Conexão autenticada confirmada. Nada foi salvo.', authentication_rejected: 'Credencial recusada. Confira o token, chave, usuário ou senha e teste novamente. Nada foi salvo.', missing_configuration: 'Preencha os campos obrigatórios e teste novamente. Nada foi salvo.', invalid_configuration: 'Confira o endereço HTTP/HTTPS e os campos. Nada foi salvo.', connection_failed: 'Não foi possível alcançar o serviço. Confira se está ligado e se o endereço é acessível pelo backend. Nada foi salvo.', timeout: 'O serviço demorou a responder. Confira a conexão e tente novamente. Nada foi salvo.' } as Record<string, string>)[reason] ?? 'O serviço não confirmou a conexão. Confira o endereço e a configuração e teste novamente. Nada foi salvo.';
  }
  folderHelp(result: StorageDiagnostic): string {
    if (result.backend !== 'read_write_verified') return 'A pasta não passou na verificação de leitura e escrita. Confira se existe dentro da biblioteca e se o backend tem permissão. Revise o diagnóstico em Configurações.';
    if (result.mapping === 'mapping_incompatible') return 'A pasta é acessível, mas o destino do motor é incompatível. Confira seus volumes e preferências.';
    return 'Leitura e escrita confirmadas no backend. O compartilhamento físico com o motor permanece inconclusivo. Confira os volumes e o diagnóstico em Configurações antes de baixar.';
  }
}
