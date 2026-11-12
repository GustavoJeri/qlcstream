import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { SettingsPage } from './settings.page';
import { IntegrationStatus, SystemApiService } from '../core/system-api.service';
import { LibraryApiService } from '../core/library-api.service';

describe('SettingsPage diagnostics', () => {
  const ids: IntegrationStatus['id'][] = ['tmdb', 'prowlarr', 'qbittorrent'];
  const results = (status: IntegrationStatus['status'] = 'available', reason = 'ok'): IntegrationStatus[] => ids.map((id) => ({
    id, configured: true, status, reason, checkedAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 60000).toISOString(), retryAfter: null,
  }));
  let api: { integrations: ReturnType<typeof vi.fn>; checkIntegrations: ReturnType<typeof vi.fn>; checkStorage: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    vi.useFakeTimers();
    api = { checkStorage: vi.fn(() => of({ status: 'inconclusive', backend: 'read_write_verified', mapping: 'compatible_unproven', sharedDirectoryVerified: false, checkedAt: new Date().toISOString() })), integrations: vi.fn(() => of(results())), checkIntegrations: vi.fn(() => of(results())) };
    TestBed.configureTestingModule({ providers: [
      { provide: SystemApiService, useValue: api },
      { provide: LibraryApiService, useValue: { storage: () => of({ downloadDirectory: 'incoming' }) } },
    ] });
  });
  afterEach(() => { TestBed.resetTestingModule(); vi.useRealTimers(); });

  it('requires a successful fresh check before presenting availability and expires it on screen', async () => {
    api.integrations.mockReturnValue(of(results('unknown', 'unknown')));
    const fixture = TestBed.createComponent(SettingsPage);
    const page = fixture.componentInstance;
    fixture.detectChanges();
    expect(page.allConfigured()).toBe(false);
    expect(page.connectionLabel('tmdb')).toBe('Desconhecido');
    page.load(true);
    fixture.detectChanges();
    expect(page.connectionsAvailable()).toBe(true);
    expect(fixture.nativeElement.textContent).not.toContain('Tudo pronto');
    expect(fixture.nativeElement.textContent).toContain('Última verificação:');
    await vi.advanceTimersByTimeAsync(61000);
    fixture.detectChanges();
    expect(page.allConfigured()).toBe(false);
    expect(page.connectionsAvailable()).toBe(false);
    expect(page.connectionLabel('tmdb')).toBe('Desatualizado');
    expect(fixture.nativeElement.textContent).not.toContain('Tudo pronto');
    expect(api.integrations).toHaveBeenCalledTimes(1);
  });

  it('shows independent progress, authentication failure and recovery without excessive polling', async () => {
    const items = results();
    items[0] = { ...items[0], status: 'checking', reason: 'checking', checkedAt: null };
    items[1] = { ...items[1], status: 'authentication_rejected', reason: 'authentication_rejected' };
    api.integrations.mockReturnValueOnce(of(items)).mockReturnValue(of(results()));
    const fixture = TestBed.createComponent(SettingsPage);
    const page = fixture.componentInstance;
    fixture.detectChanges();
    expect(page.connectionLabel('tmdb')).toBe('Verificando');
    expect(page.connectionLabel('prowlarr')).toBe('Autenticação recusada');
    expect(page.connectionHelp('prowlarr')).toContain('chave de API');
    expect(page.connectionLabel('qbittorrent')).toBe('Disponível');
    expect(page.refreshDisabled()).toBe(true);
    await vi.advanceTimersByTimeAsync(500);
    expect(page.connectionsAvailable()).toBe(true);
    expect(page.allConfigured()).toBe(false);
    await vi.advanceTimersByTimeAsync(5000);
    expect(api.integrations).toHaveBeenCalledTimes(2);
    page.load(true);
    expect(api.checkIntegrations).toHaveBeenCalledTimes(1);
    expect(page.connectionsAvailable()).toBe(true);
    expect(page.allConfigured()).toBe(false);
  });

  it('separates backend communication failure from integration authentication and allows retry', () => {
    api.integrations.mockReturnValue(throwError(() => new Error('private-secret')));
    const fixture = TestBed.createComponent(SettingsPage);
    const page = fixture.componentInstance;
    fixture.detectChanges();
    expect(page.connectionLabel('tmdb')).toBe('Não confirmado');
    expect(page.connectionHelp('tmdb')).toContain('backend');
    expect(fixture.nativeElement.textContent).not.toContain('private-secret');
    expect(page.refreshDisabled()).toBe(false);
    page.load(true);
    expect(page.connectionsAvailable()).toBe(true);
    expect(page.allConfigured()).toBe(false);
  });

  it('discards an older response when a newer request completes', () => {
    const old = new Subject<IntegrationStatus[]>();
    api.integrations.mockReturnValue(old);
    const page = TestBed.createComponent(SettingsPage).componentInstance;
    page.load(true);
    expect(page.connectionsAvailable()).toBe(true);
    expect(page.allConfigured()).toBe(false);
    old.next(results('authentication_rejected', 'authentication_rejected'));
    expect(page.connectionsAvailable()).toBe(true);
    expect(page.allConfigured()).toBe(false);
    expect(page.connectionLabel('tmdb')).toBe('Disponível');
  });

  it('bounds polling and makes retry available even if the last snapshot was checking', async () => {
    api.integrations.mockReturnValue(of(results('checking', 'checking')));
    const page = TestBed.createComponent(SettingsPage).componentInstance;
    await vi.advanceTimersByTimeAsync(10000);
    expect(page.state()).toBe('error');
    expect(page.refreshDisabled()).toBe(false);
    expect(api.integrations.mock.calls.length).toBeLessThanOrEqual(17);
  });

  it('respects the server retry interval and explains each unavailable reason', async () => {
    api.integrations.mockReturnValue(of(results('unavailable', 'timeout').map((item) => ({ ...item, retryAfter: new Date(Date.now() + 10000).toISOString() }))));
    const page = TestBed.createComponent(SettingsPage).componentInstance;
    expect(page.refreshDisabled()).toBe(true);
    expect(page.connectionHelp('tmdb')).toContain('limite');
    await vi.advanceTimersByTimeAsync(11000);
    expect(page.refreshDisabled()).toBe(false);
    for (const reason of ['invalid_configuration', 'connection_failed', 'invalid_response', 'http_error', 'missing_configuration']) {
      page.integrations.set(results('unavailable', reason));
      expect(page.connectionHelp('tmdb')).not.toContain('Ainda não há');
    }
  });
});
