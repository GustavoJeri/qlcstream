import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SettingsPage } from './settings.page';

describe('Storage diagnostics in settings', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  function open() {
    const fixture = TestBed.createComponent(SettingsPage);
    http.expectOne('/api/system/integrations').flush(['tmdb', 'prowlarr', 'qbittorrent'].map(id => ({
      id, configured: true, status: 'available', reason: 'ok', checkedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 60000).toISOString(), retryAfter: null,
    })));
    http.expectOne('/api/library/storage').flush({ downloadDirectory: 'incoming' });
    return fixture;
  }
  function respond(backend: string, mapping: string, status = 'blocked') {
    const request = http.expectOne('/api/system/storage/check');
    expect(request.request.method).toBe('POST');
    request.flush({ status, backend, mapping, sharedDirectoryVerified: false, checkedAt: '2026-11-12T12:00:00Z' });
  }
  it('keeps compatible paths inconclusive and never announces ready', () => {
    const fixture = open();
    respond('read_write_verified', 'compatible_unproven', 'inconclusive');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Compartilhamento inconclusivo');
    expect(fixture.nativeElement.textContent).toContain('Leitura e escrita do backend comprovadas');
    expect(fixture.nativeElement.textContent).not.toContain('Tudo pronto');
    expect(fixture.componentInstance.connectionsAvailable()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('3/3 conexões verificadas');
  });
  it.each([
    ['missing_directory', 'A raiz ou a pasta salva não existe'],
    ['permission_denied', 'não tem as permissões necessárias'],
    ['outside_root', 'Destino fora da raiz'],
  ])('explains blocked backend %s', (reason, message) => {
    const fixture = open(); respond(reason, 'compatible_unproven'); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(message);
    expect(fixture.nativeElement.textContent).toContain('Armazenamento precisa de atenção');
    expect(fixture.componentInstance.connectionsAvailable()).toBe(true);
    expect(fixture.componentInstance.allConfigured()).toBe(false);
  });
  it('explains incompatible engine paths', () => {
    const fixture = open(); respond('read_write_verified', 'mapping_incompatible'); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Destino do qBittorrent incompatível');
  });
  it('clears old evidence when a saved destination changes and checks the saved value', () => {
    const fixture = open(); respond('read_write_verified', 'compatible_unproven', 'inconclusive');
    fixture.componentInstance.downloadDirectory.set('new'); fixture.componentInstance.saveStorage();
    expect(fixture.componentInstance.storageDiagnostic()).toBeNull();
    const save = http.expectOne('/api/library/storage');
    expect(save.request.body).toEqual({ downloadDirectory: 'new' }); save.flush({ downloadDirectory: 'new' });
    respond('missing_directory', 'compatible_unproven');
    expect(fixture.componentInstance.storageDiagnostic()?.backend).toBe('missing_directory');
  });
  it('prevents overlapping checks and reports backend communication errors', () => {
    const fixture = open(); fixture.componentInstance.checkStorage();
    http.expectOne('/api/system/storage/check').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não foi possível diagnosticar');
    expect(fixture.componentInstance.storageDiagnostic()).toBeNull();
  });
});
